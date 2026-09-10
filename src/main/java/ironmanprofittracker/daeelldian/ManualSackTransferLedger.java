package ironmanprofittracker.daeelldian;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * Quantity ledger for player-inventory -> Sacks transfers detected from inventory deltas while a
 * Sacks management screen is active. These quantities are transfers, not newly-earned profit.
 */
public final class ManualSackTransferLedger {
    private final long retentionMs;
    private final List<Entry> pending = new ArrayList<>();

    public ManualSackTransferLedger(long retentionMs) {
        this.retentionMs = Math.max(0L, retentionMs);
    }

    public void clear() {
        pending.clear();
    }

    public void recordDeposit(String key, long amount, long nowMs) {
        if (key == null || key.isBlank() || amount <= 0L) return;
        purge(nowMs);
        pending.add(new Entry(key, amount, nowMs));
    }

    /** Returns the exact portion of an incoming Sacks item line known to be a manual transfer. */
    public long consume(String key, long amount, long nowMs) {
        if (key == null || key.isBlank() || amount <= 0L) return 0L;
        purge(nowMs);

        long remaining = amount;
        long matched = 0L;
        ListIterator<Entry> iterator = pending.listIterator(pending.size());
        while (iterator.hasPrevious() && remaining > 0L) {
            Entry entry = iterator.previous();
            if (!entry.key.equals(key)) continue;

            long take = Math.min(entry.remaining, remaining);
            entry.remaining -= take;
            remaining -= take;
            matched = ProfitTrackerMath.saturatingAdd(matched, take);
            if (entry.remaining <= 0L) iterator.remove();
        }
        return matched;
    }

    public void purge(long nowMs) {
        pending.removeIf(entry -> nowMs - entry.timestampMs > retentionMs);
    }

    public long pendingAmount(String key, long nowMs) {
        if (key == null || key.isBlank()) return 0L;
        purge(nowMs);
        long total = 0L;
        for (Entry entry : pending) {
            if (entry.key.equals(key)) total = ProfitTrackerMath.saturatingAdd(total, entry.remaining);
        }
        return total;
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
