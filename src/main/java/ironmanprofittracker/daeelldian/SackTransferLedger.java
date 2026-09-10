package ironmanprofittracker.daeelldian;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * Small, Minecraft-independent ledger for correlating sack removals with later returns.
 *
 * <p>Outside a known transaction context, only an exact short-lived reversal is consumed.
 * Inside a known NPC transaction context, partial returns are allowed and may consume older
 * removals (up to the configured history limit). This keeps the generic fallback conservative
 * while still handling NPC buyback correctly.</p>
 */
public final class SackTransferLedger {
    private final long historyMs;
    private final long exactReturnWindowMs;
    private final List<Entry> removals = new ArrayList<>();

    public SackTransferLedger(long historyMs, long exactReturnWindowMs) {
        this.historyMs = Math.max(0L, historyMs);
        this.exactReturnWindowMs = Math.max(0L, exactReturnWindowMs);
    }

    public void clear() {
        removals.clear();
    }

    public void recordRemoval(String key, long amount, long nowMs) {
        if (key == null || key.isBlank() || amount <= 0L) return;
        purge(nowMs);
        removals.add(new Entry(key, amount, nowMs));
    }

    /**
     * Returns how much of an incoming addition should be treated as a return/redeposit rather than
     * newly-earned material.
     */
    public long consumeReturn(String key, long amount, long nowMs, boolean transactionContext) {
        if (key == null || key.isBlank() || amount <= 0L) return 0L;
        purge(nowMs);

        if (!transactionContext) {
            // Conservative fallback: only suppress an exact reversal soon after a removal.
            for (int i = removals.size() - 1; i >= 0; i--) {
                Entry entry = removals.get(i);
                if (!entry.key.equals(key)) continue;
                if (nowMs - entry.timestampMs > exactReturnWindowMs) continue;
                if (entry.remaining != amount) continue;
                removals.remove(i);
                return amount;
            }
            return 0L;
        }

        // In a positively identified NPC/buyback context, partial buybacks are expected.
        long remainingToMatch = amount;
        long matched = 0L;
        ListIterator<Entry> iterator = removals.listIterator(removals.size());
        while (iterator.hasPrevious() && remainingToMatch > 0L) {
            Entry entry = iterator.previous();
            if (!entry.key.equals(key)) continue;

            long take = Math.min(entry.remaining, remainingToMatch);
            entry.remaining -= take;
            remainingToMatch -= take;
            matched += take;
            if (entry.remaining <= 0L) iterator.remove();
        }
        return matched;
    }

    public void purge(long nowMs) {
        removals.removeIf(entry -> nowMs - entry.timestampMs > historyMs);
    }

    private static final class Entry {
        private final String key;
        private long remaining;
        private final long timestampMs;

        private Entry(String key, long remaining, long timestampMs) {
            this.key = key;
            this.remaining = remaining;
            this.timestampMs = timestampMs;
        }
    }
}
