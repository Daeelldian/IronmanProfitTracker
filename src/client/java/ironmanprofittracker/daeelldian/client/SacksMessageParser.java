package ironmanprofittracker.daeelldian.client;

import ironmanprofittracker.daeelldian.ProfitTrackerMath;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Hypixel GAME messages and leaves source/session decisions to ProfitTrackerState. */
public final class SacksMessageParser {
    private static final long FALLBACK_SACK_WINDOW_MS = 30_000L;
    private static final Pattern SACKS = Pattern.compile(
            "(?s)^\\[Sacks\\]\\s*([+-])([0-9][0-9,]*)\\s+items?\\.?\\s*(?:\\(Last\\s+([0-9][0-9,]*)s\\.\\))?\\s*$"
    );
    private static final Pattern SUPERCRAFT = Pattern.compile(
            "(?i)^You\\s+Supercrafted\\s+(.+?)\\s+x([0-9][0-9,]*)!\\s*$"
    );

    private SacksMessageParser() {}

    public static void process(Component message, ProfitTrackerState state) {
        if (message == null || state == null) return;
        String text = message.getString().trim();

        // Most GAME messages are unrelated. Route by cheap prefixes before allocating regex matchers
        // or reading the monotonic clock.
        if (text.startsWith("You Supercrafted ")) {
            Matcher supercraft = SUPERCRAFT.matcher(text);
            if (supercraft.matches()) {
                state.onSupercraft(
                        supercraft.group(1),
                        parseNumber(supercraft.group(2)),
                        ProfitTrackerClock.nowMs()
                );
            }
            return;
        }

        if ((text.contains("stash") || text.contains("Stash")) && isStashMessage(text)) {
            state.onStashMessage(text, ProfitTrackerClock.nowMs());
            return;
        }

        if (!text.startsWith("[Sacks]")) return;
        Matcher sacks = SACKS.matcher(text);
        if (!sacks.matches()) return;

        long nowMs = ProfitTrackerClock.nowMs();
        boolean addition = "+".equals(sacks.group(1));
        long reportedItemCount = parseNumber(sacks.group(2));
        long reportedWindowMs = sacks.group(3) == null
                ? 0L
                : ProfitTrackerMath.saturatingMultiply(parseNumber(sacks.group(3)), 1_000L);
        long accountingWindowMs = reportedWindowMs > 0L ? reportedWindowMs : FALLBACK_SACK_WINDOW_MS;
        if (!addition) {
            ProfitTrackerDebug.trace("Ignoring sack removal: " + text);
            return;
        }

        try {
            Set<String> uniqueHoverTexts = collectHoverTexts(message);
            Map<ProfitSource, LinkedHashMap<String, ParsedSacksEvent.RewardLine>> parsedBySource = new EnumMap<>(ProfitSource.class);
            LinkedHashMap<String, ParsedSacksEvent.RewardLine> parsedMiningBonuses = new LinkedHashMap<>();

            for (String hoverText : uniqueHoverTexts) {
                for (ProfitSource source : ProfitSource.values()) {
                    List<ProfitSource.ParsedReward> parsedRewards = source.parseRewards(hoverText);
                    if (parsedRewards.isEmpty()) continue;

                    LinkedHashMap<String, ParsedSacksEvent.RewardLine> sourceLines =
                            parsedBySource.computeIfAbsent(source, ignored -> new LinkedHashMap<>());
                    for (ProfitSource.ParsedReward parsed : parsedRewards) {
                        if (parsed.amount() <= 0 || parsed.profit() <= 0) continue;
                        String key = parsed.itemName().toLowerCase(Locale.ROOT) + "|" + parsed.amount() + "|" + parsed.npcSellPrice();
                        ParsedSacksEvent.RewardLine previous = sourceLines.putIfAbsent(
                                key,
                                new ParsedSacksEvent.RewardLine(
                                        parsed.itemName(),
                                        parsed.amount(),
                                        parsed.npcSellPrice(),
                                        parsed.materialFamily(),
                                        parsed.baseUnitsPerItem()
                                )
                        );
                        if (previous != null) {
                            ProfitTrackerDebug.trace(
                                    "Suppressed duplicate semantic reward: " + parsed.itemName() + " x" + parsed.amount()
                            );
                        }
                    }
                }

                for (ParsedSacksEvent.RewardLine bonusLine : MiningBonus.parseRewards(hoverText)) {
                    String key = bonusLine.itemName().toLowerCase(Locale.ROOT) + "|"
                            + bonusLine.amount() + "|" + bonusLine.npcSellPrice();
                    ParsedSacksEvent.RewardLine previous = parsedMiningBonuses.putIfAbsent(key, bonusLine);
                    if (previous != null) {
                        ProfitTrackerDebug.trace(
                                "Suppressed duplicate mining bonus reward: "
                                        + bonusLine.itemName() + " x" + bonusLine.amount()
                        );
                    }
                }
            }

            Map<ProfitSource, ParsedSacksEvent.SourceReward> rewards = new EnumMap<>(ProfitSource.class);
            long totalParsedItems = 0L;
            for (Map.Entry<ProfitSource, LinkedHashMap<String, ParsedSacksEvent.RewardLine>> entry : parsedBySource.entrySet()) {
                long count = 0L;
                long profit = 0L;
                for (ParsedSacksEvent.RewardLine line : entry.getValue().values()) {
                    count = ProfitTrackerMath.saturatingAdd(count, line.amount());
                    profit = ProfitTrackerMath.saturatingAdd(profit, line.profit());
                }
                if (count <= 0 || profit <= 0) continue;
                totalParsedItems = ProfitTrackerMath.saturatingAdd(totalParsedItems, count);
                rewards.put(entry.getKey(), new ParsedSacksEvent.SourceReward(count, profit, List.copyOf(entry.getValue().values())));
            }

            for (ParsedSacksEvent.RewardLine bonusLine : parsedMiningBonuses.values()) {
                totalParsedItems = ProfitTrackerMath.saturatingAdd(totalParsedItems, bonusLine.amount());
            }

            if (reportedItemCount > 0 && totalParsedItems > reportedItemCount) {
                ProfitTrackerDebug.error(
                        "sack reward sanity check",
                        "parsed " + totalParsedItems + " tracked items from a +" + reportedItemCount
                                + " sack event; entire tracked reward ignored"
                );
                rewards.clear();
                parsedMiningBonuses.clear();
            }

            ProfitTrackerDebug.trace(
                    "Sacks parser: reportedItems=" + reportedItemCount
                            + " windowMs=" + accountingWindowMs
                            + (reportedWindowMs > 0L ? "" : " (fallback)")
                            + " hoverPayloads=" + uniqueHoverTexts.size()
                            + " parsedTrackedItems=" + totalParsedItems
            );
            state.onSacksEvent(new ParsedSacksEvent(
                    nowMs,
                    true,
                    reportedItemCount,
                    accountingWindowMs,
                    rewards,
                    List.copyOf(parsedMiningBonuses.values())
            ));
        } catch (RuntimeException exception) {
            ProfitTrackerDebug.error("sack/event parsing", exception);
        }
    }

    private static Set<String> collectHoverTexts(Component message) {
        Set<String> unique = new LinkedHashSet<>();
        for (Component component : message.toFlatList()) {
            Style style = component.getStyle();
            HoverEvent hover = style == null ? null : style.getHoverEvent();
            if (!(hover instanceof HoverEvent.ShowText showText)) continue;
            String value = showText.value().getString();
            if (value != null && !value.isBlank()) unique.add(normalizeHover(value));
        }
        return unique;
    }

    private static String normalizeHover(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static boolean isStashMessage(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("successfully transfered your items from this stash to your sacks")
                || lower.contains("successfully transferred your items from this stash to your sacks")
                || lower.startsWith("from stash:")
                || lower.contains("you picked up all items from your") && lower.contains("stash");
    }

    private static long parseNumber(String value) {
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
