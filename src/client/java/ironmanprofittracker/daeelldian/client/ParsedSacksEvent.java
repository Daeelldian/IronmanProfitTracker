package ironmanprofittracker.daeelldian.client;

import ironmanprofittracker.daeelldian.ProfitTrackerMath;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record ParsedSacksEvent(
        long timestampMs,
        boolean addition,
        long reportedItemCount,
        long accountingWindowMs,
        Map<ProfitSource, SourceReward> rewards,
        List<RewardLine> miningBonusLines
) {
    public ParsedSacksEvent {
        if (rewards == null || rewards.isEmpty()) {
            rewards = Map.of();
        } else {
            EnumMap<ProfitSource, SourceReward> copy = new EnumMap<>(ProfitSource.class);
            copy.putAll(rewards);
            rewards = Collections.unmodifiableMap(copy);
        }
        miningBonusLines = miningBonusLines == null ? List.of() : List.copyOf(miningBonusLines);
    }

    /** Start of the acquisition window represented by this sack notification. */
    public long activityStartMs() {
        return ProfitTrackerMath.activityWindowStartMs(timestampMs, accountingWindowMs);
    }

    public long miningBonusItemCount() {
        long count = 0L;
        for (RewardLine line : miningBonusLines) count = ProfitTrackerMath.saturatingAdd(count, line.amount());
        return count;
    }

    public long miningBonusProfitFor(ProfitSource source) {
        if (source == null || !source.isMining()) return 0L;
        long value = 0L;
        for (RewardLine line : miningBonusLines) {
            MiningBonus bonus = MiningBonus.fromItemName(line.itemName());
            if (bonus != null && bonus.appliesTo(source)) {
                value = ProfitTrackerMath.saturatingAdd(value, line.profit());
            }
        }
        return value;
    }

    public record SourceReward(long itemCount, long profit, List<RewardLine> lines) {
        public SourceReward { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record RewardLine(
            String itemName,
            long amount,
            long npcSellPrice,
            String materialFamily,
            long baseUnitsPerItem
    ) {
        public RewardLine(String itemName, long amount, long npcSellPrice) {
            this(itemName, amount, npcSellPrice, "", 0L);
        }

        public long profit() { return ProfitTrackerMath.saturatingMultiply(amount, npcSellPrice); }
    }
}
