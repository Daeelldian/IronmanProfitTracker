package ironmanprofittracker.daeelldian.client;

import ironmanprofittracker.daeelldian.ProfitTrackerMath;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves short-lived causes around sack additions (Supercraft and stash transfer)
 * before rewards reach the session engine.
 */
final class RewardCorrelationTracker {
    private static final long CRAFT_WINDOW_MS = 8_000L;
    private static final long STASH_CONTEXT_MS = 15_000L;

    private final List<PendingCraft> pendingCrafts = new ArrayList<>();
    private boolean stashContextArmed;
    private long stashContextUntilMs;

    void reset() {
        pendingCrafts.clear();
        consumeStashContext();
    }

    void onSupercraft(String itemName, long amount, long nowMs) {
        if (amount <= 0L || itemName == null || itemName.isBlank()) return;
        purge(nowMs);
        pendingCrafts.add(new PendingCraft(normalizeName(itemName), amount, nowMs));
        ProfitTrackerDebug.info("Supercraft pending: " + itemName.trim() + " x" + amount);
    }

    void onStashMessage(String text, long nowMs) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!lower.contains("from this stash to your sacks")) {
            ProfitTrackerDebug.trace("Observed stash message without arming sacks context: " + text);
            return;
        }

        stashContextArmed = true;
        stashContextUntilMs = nowMs + STASH_CONTEXT_MS;
        ProfitTrackerDebug.info("Stash-to-sacks context armed for the next sack addition.");
    }

    ResolvedEvent resolve(ParsedSacksEvent event) {
        long nowMs = event.timestampMs();
        purge(nowMs);

        boolean stash = stashContextArmed && nowMs <= stashContextUntilMs;
        // A stash-transfer marker refers to the next sack addition, not a 15-second mode.
        if (stash) consumeStashContext();

        Map<ProfitSource, ParsedSacksEvent.SourceReward> filtered = new EnumMap<>(ProfitSource.class);
        List<ParsedSacksEvent.RewardLine> filteredBonuses = new ArrayList<>();
        int suppressedLines = 0;

        for (Map.Entry<ProfitSource, ParsedSacksEvent.SourceReward> entry : event.rewards().entrySet()) {
            List<ParsedSacksEvent.RewardLine> keptLines = new ArrayList<>();
            long keptItems = 0L;
            long keptProfit = 0L;

            for (ParsedSacksEvent.RewardLine line : entry.getValue().lines()) {
                if (consumeMatchingCraft(line)) {
                    suppressedLines++;
                    ProfitTrackerDebug.trace("Craft correlation suppressed " + line.itemName() + " x" + line.amount());
                    continue;
                }
                keptLines.add(line);
                keptItems = ProfitTrackerMath.saturatingAdd(keptItems, line.amount());
                keptProfit = ProfitTrackerMath.saturatingAdd(keptProfit, line.profit());
            }

            if (keptItems > 0L && keptProfit > 0L) {
                filtered.put(entry.getKey(), new ParsedSacksEvent.SourceReward(keptItems, keptProfit, keptLines));
            }
        }

        for (ParsedSacksEvent.RewardLine bonusLine : event.miningBonusLines()) {
            if (consumeMatchingCraft(bonusLine)) {
                suppressedLines++;
                ProfitTrackerDebug.trace("Craft correlation suppressed mining bonus "
                        + bonusLine.itemName() + " x" + bonusLine.amount());
                continue;
            }
            filteredBonuses.add(bonusLine);
        }

        ParsedSacksEvent effective = new ParsedSacksEvent(
                event.timestampMs(),
                event.addition(),
                event.reportedItemCount(),
                event.accountingWindowMs(),
                filtered,
                filteredBonuses
        );
        return new ResolvedEvent(effective, stash ? RewardContext.STASH : RewardContext.NORMAL, suppressedLines);
    }

    void tick(long nowMs) {
        purge(nowMs);
    }

    private boolean consumeMatchingCraft(ParsedSacksEvent.RewardLine line) {
        String normalizedItem = normalizeName(line.itemName());
        Iterator<PendingCraft> iterator = pendingCrafts.iterator();
        while (iterator.hasNext()) {
            PendingCraft craft = iterator.next();
            if (craft.amount == line.amount() && craft.normalizedItemName.equals(normalizedItem)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void purge(long nowMs) {
        Iterator<PendingCraft> iterator = pendingCrafts.iterator();
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().timestampMs > CRAFT_WINDOW_MS) iterator.remove();
        }
        if (stashContextArmed && nowMs > stashContextUntilMs) consumeStashContext();
    }

    private void consumeStashContext() {
        stashContextArmed = false;
        stashContextUntilMs = 0L;
    }

    private static String normalizeName(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('×', 'x')
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    record ResolvedEvent(ParsedSacksEvent event, RewardContext context, int suppressedLines) {}
    private record PendingCraft(String normalizedItemName, long amount, long timestampMs) {}
}
