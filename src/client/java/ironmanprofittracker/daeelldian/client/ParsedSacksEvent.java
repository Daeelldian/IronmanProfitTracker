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
        Map<ProfitSource, SourceReward> rewards
) {
    public ParsedSacksEvent {
        if (rewards == null || rewards.isEmpty()) {
            rewards = Map.of();
        } else {
            EnumMap<ProfitSource, SourceReward> copy = new EnumMap<>(ProfitSource.class);
            copy.putAll(rewards);
            rewards = Collections.unmodifiableMap(copy);
        }
    }

    /** Start of the acquisition window represented by this sack notification. */
    public long activityStartMs() {
        return ProfitTrackerMath.activityWindowStartMs(timestampMs, accountingWindowMs);
    }

    public record SourceReward(long itemCount, long profit, List<RewardLine> lines) {
        public SourceReward { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record RewardLine(String itemName, long amount, long npcSellPrice) {
        public long profit() { return ProfitTrackerMath.saturatingMultiply(amount, npcSellPrice); }
    }
}
