package ironmanprofittracker.daeelldian.client;

import ironmanprofittracker.daeelldian.ProfitTrackerMath;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Session engine: source candidates, anti-contamination, stash/craft correlation and timing. */
public final class ProfitTrackerState {
    private static final long INACTIVITY_MS = 40_000L;
    private static final long CANDIDATE_WINDOW_MS = 25_000L;
    private static final long MIN_START_VALUE = 10_000L;
    private static final long STRONG_SINGLE_EVENT_VALUE = 50_000L;
    private static final int MIN_EVENTS = 2;

    private final ProfitTrackerRecords records = ProfitTrackerRecords.load();
    private final RewardCorrelationTracker correlationTracker = new RewardCorrelationTracker();
    private final Deque<Component> pendingChatMessages = new ArrayDeque<>();

    private ProfitSource source;
    private long profit;
    private long sessionStartedAtMs;
    private long lastActivityAtMs;
    private double liveProfitPerHour;
    private boolean sessionActive;
    private boolean sessionPaused;
    private long pausedAtMs;
    private final List<PauseInterval> pauseIntervals = new ArrayList<>();

    private Candidate candidate;
    private boolean connectionReadyForPendingChat;

    /**
     * Hypixel lobby transfers can produce JOIN without a matching DISCONNECT event.
     * JOIN is therefore also a hard session boundary.
     */
    public void onServerJoin(long nowMs) {
        if (sessionActive) {
            ProfitTrackerDebug.info("Server join detected with active session; finalizing before runtime reset.");
            endSession(nowMs, SessionEndReason.SERVER_CHANGE, true);
        }
        resetRuntimeState(false);
        connectionReadyForPendingChat = true;
        flushPendingChatMessages();
    }

    public void endSessionFromServerChange(long nowMs) {
        // Do not inject the end message into a chat instance that is about to be discarded.
        connectionReadyForPendingChat = false;
        endSession(nowMs, SessionEndReason.SERVER_CHANGE, true);
    }

    private void resetRuntimeState(boolean clearPendingMessages) {
        source = null;
        profit = 0L;
        sessionStartedAtMs = 0L;
        lastActivityAtMs = 0L;
        liveProfitPerHour = 0.0;
        sessionActive = false;
        sessionPaused = false;
        pausedAtMs = 0L;
        pauseIntervals.clear();
        candidate = null;
        correlationTracker.reset();
        if (clearPendingMessages) pendingChatMessages.clear();
    }

    public void onSupercraft(String itemName, long amount, long nowMs) {
        correlationTracker.onSupercraft(itemName, amount, nowMs);
    }

    public void onStashMessage(String text, long nowMs) {
        correlationTracker.onStashMessage(text, nowMs);
    }

    public void onSacksEvent(ParsedSacksEvent event) {
        if (event == null || !event.addition()) return;

        RewardCorrelationTracker.ResolvedEvent resolved = correlationTracker.resolve(event);
        if (resolved.suppressedLines() > 0) {
            ProfitTrackerDebug.info("Suppressed " + resolved.suppressedLines() + " crafted sack reward line(s).");
        }

        ParsedSacksEvent effectiveEvent = resolved.event();
        if (effectiveEvent.rewards().isEmpty()) {
            ProfitTrackerDebug.trace("Sacks addition contained no eligible configured reward after correlation.");
            return;
        }

        logEventSummary(effectiveEvent, resolved.context());
        if (resolved.context() == RewardContext.STASH) {
            handleStash(effectiveEvent);
        } else {
            handleNormal(effectiveEvent);
        }
    }

    private void logEventSummary(ParsedSacksEvent event, RewardContext context) {
        ProfitTrackerDebug.info("Sacks event classified as " + context + ".");
        if (!ProfitTrackerDebug.enabled(ProfitTrackerDebug.Level.TRACE)) return;

        long parsedItems = 0L;
        long parsedProfit = 0L;
        StringBuilder details = new StringBuilder();
        for (Map.Entry<ProfitSource, ParsedSacksEvent.SourceReward> entry : event.rewards().entrySet()) {
            ParsedSacksEvent.SourceReward reward = entry.getValue();
            parsedItems = ProfitTrackerMath.saturatingAdd(parsedItems, reward.itemCount());
            parsedProfit = ProfitTrackerMath.saturatingAdd(parsedProfit, reward.profit());
            if (!details.isEmpty()) details.append("; ");
            details.append(entry.getKey().getDisplayName())
                    .append(" items=").append(reward.itemCount())
                    .append(" profit=").append(reward.profit());
            for (ParsedSacksEvent.RewardLine line : reward.lines()) {
                details.append(" [").append(line.itemName())
                        .append(" x").append(line.amount())
                        .append(" @").append(line.npcSellPrice()).append(']');
            }
        }
        ProfitTrackerDebug.trace(
                "Sacks summary: reportedItems=" + event.reportedItemCount()
                        + " accountingWindowMs=" + event.accountingWindowMs()
                        + " activityStartMs=" + event.activityStartMs()
                        + " parsedItems=" + parsedItems
                        + " parsedProfit=" + parsedProfit
                        + " context=" + context
                        + " -> " + details
        );
    }

    private void handleStash(ParsedSacksEvent event) {
        Map<ProfitSource, Long> recoveredBySource = new EnumMap<>(ProfitSource.class);
        for (Map.Entry<ProfitSource, ParsedSacksEvent.SourceReward> entry : event.rewards().entrySet()) {
            long recovered = entry.getValue().profit();
            if (recovered <= 0L) continue;
            recoveredBySource.put(entry.getKey(), recovered);
        }
        records.addRecoveredProfits(recoveredBySource);
        recoveredBySource.forEach(this::sendStashMessage);
    }

    private void handleNormal(ParsedSacksEvent event) {
        if (sessionActive) {
            ParsedSacksEvent.SourceReward current = event.rewards().get(source);
            if (current != null && current.profit() > 0) creditCurrent(current, event);

            // One sack notification can contain several tracked sources. Consider only the strongest
            // competing source so candidate selection is deterministic rather than enum-order dependent.
            ProfitSource bestCompetitor = null;
            ParsedSacksEvent.SourceReward bestCompetingReward = null;
            for (Map.Entry<ProfitSource, ParsedSacksEvent.SourceReward> entry : event.rewards().entrySet()) {
                if (entry.getKey() == source) continue;
                if (bestCompetingReward == null || entry.getValue().profit() > bestCompetingReward.profit()) {
                    bestCompetitor = entry.getKey();
                    bestCompetingReward = entry.getValue();
                }
            }
            if (bestCompetitor != null) considerSwitch(bestCompetitor, bestCompetingReward, event);
            return;
        }

        ProfitSource best = null;
        ParsedSacksEvent.SourceReward bestReward = null;
        for (Map.Entry<ProfitSource, ParsedSacksEvent.SourceReward> entry : event.rewards().entrySet()) {
            if (bestReward == null || entry.getValue().profit() > bestReward.profit()) {
                best = entry.getKey();
                bestReward = entry.getValue();
            }
        }
        if (best != null) considerStart(best, bestReward, event);
    }

    private void creditCurrent(ParsedSacksEvent.SourceReward reward, ParsedSacksEvent event) {
        long nowMs = event.timestampMs();
        resumeIfPaused(event.activityStartMs(), nowMs);
        profit = ProfitTrackerMath.saturatingAdd(profit, reward.profit());
        lastActivityAtMs = nowMs;
        liveProfitPerHour = rateAt(lastActivityAtMs);
        ProfitTrackerDebug.trace(
                "Credited " + source.getDisplayName() + " +" + reward.profit()
                        + " coins; sampledRate=" + liveProfitPerHour
        );
    }

    private void considerStart(ProfitSource candidateSource, ParsedSacksEvent.SourceReward reward, ParsedSacksEvent event) {
        if (reward.profit() <= 0) return;
        updateCandidate(candidateSource, reward, event);
        ProfitTrackerDebug.trace(
                "Start candidate " + candidateSource.getDisplayName() + ": "
                        + candidate.profit + " coins / " + candidate.events + " events."
        );
        if (confirmed(candidate)) startSessionFromCandidate();
    }

    private void considerSwitch(ProfitSource candidateSource, ParsedSacksEvent.SourceReward reward, ParsedSacksEvent event) {
        if (reward.profit() <= 0) return;
        updateCandidate(candidateSource, reward, event);
        ProfitTrackerDebug.trace(
                "Switch candidate " + candidateSource.getDisplayName() + ": "
                        + candidate.profit + " coins / " + candidate.events + " events."
        );
        if (confirmed(candidate)) switchToCandidate();
    }

    private void updateCandidate(ProfitSource candidateSource, ParsedSacksEvent.SourceReward reward, ParsedSacksEvent event) {
        long observedAtMs = event.timestampMs();
        long activityStartMs = event.activityStartMs();
        if (candidate == null
                || candidate.source != candidateSource
                || observedAtMs - candidate.firstObservedAtMs > CANDIDATE_WINDOW_MS) {
            candidate = new Candidate(candidateSource, activityStartMs, observedAtMs, reward.profit(), 1);
            return;
        }
        candidate.profit = ProfitTrackerMath.saturatingAdd(candidate.profit, reward.profit());
        candidate.events++;
        candidate.lastObservedAtMs = observedAtMs;
    }

    private static boolean confirmed(Candidate candidate) {
        return candidate.profit >= MIN_START_VALUE
                && (candidate.events >= MIN_EVENTS || candidate.profit >= STRONG_SINGLE_EVENT_VALUE);
    }

    private void startSessionFromCandidate() {
        Candidate confirmed = candidate;
        candidate = null;
        beginSession(confirmed);
        sendSessionStartedMessage(source);
        ProfitTrackerDebug.info(
                "Session confirmed: " + source.getDisplayName()
                        + " initialProfit=" + profit
                        + " initialDurationMs=" + getSessionDurationMs(lastActivityAtMs)
                        + " sampledRate=" + liveProfitPerHour
        );
    }

    private void switchToCandidate() {
        Candidate confirmed = candidate;
        candidate = null;
        ProfitSource oldSource = source;

        // End the old source at the first competing notification timestamp, matching the old
        // session's last observable reward boundary. The new source can still begin at that
        // notification's acquisition-window start; mixed sack batches may legitimately overlap.
        endSession(confirmed.firstObservedAtMs, SessionEndReason.SOURCE_SWITCH, false);
        beginSession(confirmed);
        sendSessionSwitchedMessage(oldSource, source);
        ProfitTrackerDebug.info(
                "Source switch confirmed: " + oldSource.getDisplayName() + " -> " + source.getDisplayName()
        );
    }

    private void beginSession(Candidate confirmed) {
        source = confirmed.source;
        profit = confirmed.profit;
        sessionStartedAtMs = confirmed.firstActivityAtMs;
        lastActivityAtMs = confirmed.lastObservedAtMs;
        sessionActive = true;
        sessionPaused = false;
        pausedAtMs = 0L;
        pauseIntervals.clear();
        liveProfitPerHour = rateAt(lastActivityAtMs);
    }

    public void tick(long nowMs) {
        flushPendingChatMessages();
        correlationTracker.tick(nowMs);

        if (candidate != null && nowMs - candidate.lastObservedAtMs > CANDIDATE_WINDOW_MS) {
            ProfitTrackerDebug.info("Source candidate expired: " + candidate.source.getDisplayName());
            candidate = null;
        }

        if (sessionActive && !sessionPaused && lastActivityAtMs > 0 && nowMs - lastActivityAtMs >= INACTIVITY_MS) {
            sessionPaused = true;
            // The 40-second threshold is only a detection window. Once inactivity is confirmed,
            // treat the pause as beginning at the last tracked reward so going AFK does not
            // depress the session's active-time profit/hour.
            pausedAtMs = Math.max(sessionStartedAtMs, lastActivityAtMs);
            ProfitTrackerDebug.info(
                    "Session paused after 40s without tracked activity: " + source.getDisplayName()
                            + " activeDurationMs=" + getSessionDurationMs(nowMs)
            );
        }
    }

    private void resumeIfPaused(long activityStartMs, long measurementAtMs) {
        if (!sessionPaused) return;

        // The sack message tells us how far back this credited batch was accumulated. Only the
        // idle gap before that window is paused; the acquisition window itself is active time.
        long resumeAtMs = Math.max(pausedAtMs, Math.min(measurementAtMs, activityStartMs));
        if (pausedAtMs > 0L && resumeAtMs > pausedAtMs) {
            pauseIntervals.add(new PauseInterval(pausedAtMs, resumeAtMs));
        }
        sessionPaused = false;
        pausedAtMs = 0L;
        ProfitTrackerDebug.info(
                "Session resumed: " + source.getDisplayName()
                        + " activityWindowStartMs=" + activityStartMs
                        + " activeDurationMs=" + getSessionDurationMs(measurementAtMs)
        );
    }

    private long getSessionDurationMs(long endAtMs) {
        if (sessionStartedAtMs <= 0L) return 0L;

        long wallDurationMs = ProfitTrackerMath.durationMs(sessionStartedAtMs, endAtMs);
        long pausedDurationMs = 0L;
        for (PauseInterval interval : pauseIntervals) {
            pausedDurationMs = ProfitTrackerMath.saturatingAdd(
                    pausedDurationMs,
                    ProfitTrackerMath.overlapMs(sessionStartedAtMs, endAtMs, interval.startMs, interval.endMs)
            );
        }
        if (sessionPaused && pausedAtMs > 0L) {
            pausedDurationMs = ProfitTrackerMath.saturatingAdd(
                    pausedDurationMs,
                    ProfitTrackerMath.overlapMs(sessionStartedAtMs, endAtMs, pausedAtMs, endAtMs)
            );
        }

        // Keep a positive duration for rate calculations while never allowing paused intervals
        // to subtract more than the wall-clock span.
        return Math.max(1L, wallDurationMs - Math.min(wallDurationMs, pausedDurationMs));
    }

    private double rateAt(long measurementAtMs) {
        if (!sessionActive || profit <= 0L) return 0.0;
        return ProfitTrackerMath.profitPerHour(profit, getSessionDurationMs(measurementAtMs));
    }

    private void endSession(long endAtMs, SessionEndReason reason, boolean queueMessage) {
        if (!sessionActive || source == null) return;

        ProfitSource endedSource = source;
        long endedProfit = profit;

        // Finalize at the earlier of the logical session boundary and the last credited sack
        // measurement. Time for which IPT has no corresponding reward sample must not create a
        // fake final rate decrease.
        long accountingEndAtMs = lastActivityAtMs > 0L
                ? Math.min(endAtMs, lastActivityAtMs)
                : endAtMs;
        long durationMs = getSessionDurationMs(accountingEndAtMs);
        double perHour = ProfitTrackerMath.profitPerHour(endedProfit, durationMs);

        sessionActive = false;
        sessionPaused = false;
        records.updateSession(endedSource, endedProfit, perHour);

        ProfitTrackerDebug.info(
                "Session ended: source=" + endedSource.getDisplayName()
                        + " profit=" + endedProfit
                        + " durationMs=" + durationMs
                        + " accountingEndAtMs=" + accountingEndAtMs
                        + " rate=" + perHour
                        + " reason=" + reason
        );

        if (queueMessage) {
            pendingChatMessages.addLast(buildSessionEndedMessage(endedSource, durationMs, endedProfit, perHour));
        }
    }

    private void flushPendingChatMessages() {
        if (!connectionReadyForPendingChat || pendingChatMessages.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui == null || minecraft.player == null) return;
        while (!pendingChatMessages.isEmpty()) {
            minecraft.gui.getChat().addClientSystemMessage(pendingChatMessages.removeFirst());
        }
    }

    private void sendSessionStartedMessage(ProfitSource sessionSource) {
        send(Component.literal("[IPT] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Session started").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(sessionSource.getDisplayName()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  •  " + formatCoins(profit) + " detected").withStyle(ChatFormatting.GRAY)));
    }

    private void sendSessionSwitchedMessage(ProfitSource oldSource, ProfitSource newSource) {
        send(Component.literal("[IPT] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Source switched").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(oldSource.getDisplayName()).withStyle(ChatFormatting.RED))
                .append(Component.literal(" → ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(newSource.getDisplayName()).withStyle(ChatFormatting.GREEN)));
    }

    private void sendStashMessage(ProfitSource stashSource, long recoveredProfit) {
        send(Component.literal("[IPT] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Stash recovered").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(stashSource.getDisplayName()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  +" + formatCoins(recoveredProfit)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" lifetime profit").withStyle(ChatFormatting.GRAY)));
    }

    private Component buildSessionEndedMessage(ProfitSource endedSource, long durationMs, long totalProfit, double perHour) {
        MutableComponent message = Component.literal("---------------------\n").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("[IPT] ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("Session ended").withStyle(ChatFormatting.RED))
                .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(endedSource.getDisplayName()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("\n  Duration: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatDuration(durationMs)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("\n  Profit: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatCoins(totalProfit)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\n  Profit/h: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatCoins(perHour) + "/h").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\n---------------------").withStyle(ChatFormatting.DARK_GRAY));
        return message;
    }

    private static void send(Component component) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null && minecraft.player != null) {
            minecraft.gui.getChat().addClientSystemMessage(component);
        }
    }

    private static String formatDuration(long ms) {
        long totalMinutes = Math.max(0L, ms) / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m";
        return Math.max(1L, ms / 1000L) + "s";
    }

    public static String formatCoins(double coins) {
        double abs = Math.abs(coins);
        if (abs >= 1_000_000_000) return String.format(Locale.ROOT, "%.2fB", coins / 1_000_000_000.0);
        if (abs >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", coins / 1_000_000.0);
        if (abs >= 1_000) return String.format(Locale.ROOT, "%.2fk", coins / 1_000.0);
        return String.format(Locale.ROOT, "%.0f", coins);
    }

    public ProfitSource getSource() {
        return source;
    }

    public long getProfit() {
        return profit;
    }

    public long getHighestProfit() {
        return source == null ? 0L : records.getHighestProfit(source);
    }

    public double getProfitPerHour() {
        if (!sessionActive || profit <= 0) return 0.0;
        return liveProfitPerHour;
    }

    public double getHighestProfitPerHour() {
        return source == null ? 0.0 : records.getHighestProfitPerHour(source);
    }

    public boolean isSessionActive() {
        return sessionActive;
    }

    public boolean isSessionPaused() {
        return sessionActive && sessionPaused;
    }

    public ProfitTrackerRecords getRecords() {
        return records;
    }

    public void clearStatistics(ProfitSource sourceToClear) {
        records.clear(sourceToClear);
    }

    private static final class PauseInterval {
        private final long startMs;
        private final long endMs;

        private PauseInterval(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static final class Candidate {
        private final ProfitSource source;
        private final long firstActivityAtMs;
        private final long firstObservedAtMs;
        private long lastObservedAtMs;
        private long profit;
        private int events;

        private Candidate(
                ProfitSource source,
                long firstActivityAtMs,
                long observedAtMs,
                long profit,
                int events
        ) {
            this.source = source;
            this.firstActivityAtMs = firstActivityAtMs;
            this.firstObservedAtMs = observedAtMs;
            this.lastObservedAtMs = observedAtMs;
            this.profit = profit;
            this.events = events;
        }
    }

    private enum SessionEndReason { SERVER_CHANGE, SOURCE_SWITCH }
}
