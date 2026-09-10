package ironmanprofittracker.daeelldian;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * Minecraft-independent ledger for explicit Hypixel NPC buyback messages.
 *
 * <p>A buyback is allowed to directly suppress only the next Sacks reporting interval while it is
 * still in the immediate NPC transaction window. If that direct interval passes (or a lobby
 * boundary occurs), the record is retained only as short-lived historical evidence so a later
 * manual inventory-to-sacks transfer can retire it without poisoning future mining rewards.</p>
 */
public final class BuybackCorrelationLedger {
    private final long retentionMs;
    private final List<Entry> entries = new ArrayList<>();

    public BuybackCorrelationLedger(long retentionMs) {
        this.retentionMs = Math.max(0L, retentionMs);
    }

    public void clear() {
        entries.clear();
    }

    public void recordBuyback(String key, long amount, long nowMs) {
        if (key == null || key.isBlank() || amount <= 0L) return;
        purge(nowMs);
        entries.add(new Entry(key, amount, nowMs, true));
    }

    /**
     * Consumes an explicit buyback only while direct correlation is still allowed for the current
     * Sacks reporting interval.
     */
    public long consumeDirect(String key, long amount, long nowMs) {
        if (key == null || key.isBlank() || amount <= 0L) return 0L;
        purge(nowMs);
        return consume(key, amount, true);
    }

    /**
     * Retires buyback history when an independently detected manual sack deposit moves the same
     * item. This prevents the old buyback from matching a later normal mining reward.
     */
    public long acknowledgeManualDeposit(String key, long amount, long nowMs) {
        if (key == null || key.isBlank() || amount <= 0L) return 0L;
        purge(nowMs);
        return consume(key, amount, false);
    }

    /** The next Sacks notification closes the direct buyback interval for all unmatched entries. */
    public void finishSacksInterval() {
        for (Entry entry : entries) entry.directEligible = false;
    }

    /** A lobby/server boundary also ends direct correlation, but retained history survives briefly. */
    public void onConnectionBoundary() {
        finishSacksInterval();
    }

    public void purge(long nowMs) {
        entries.removeIf(entry -> nowMs - entry.timestampMs > retentionMs);
    }

    public long pendingAmount(String key, long nowMs) {
        if (key == null || key.isBlank()) return 0L;
        purge(nowMs);
        long total = 0L;
        for (Entry entry : entries) {
            if (entry.key.equals(key)) total = ProfitTrackerMath.saturatingAdd(total, entry.remaining);
        }
        return total;
    }

    private long consume(String key, long amount, boolean requireDirectEligible) {
        long remaining = amount;
        long matched = 0L;
        ListIterator<Entry> iterator = entries.listIterator(entries.size());
        while (iterator.hasPrevious() && remaining > 0L) {
            Entry entry = iterator.previous();
            if (!entry.key.equals(key)) continue;
            if (requireDirectEligible && !entry.directEligible) continue;

            long take = Math.min(entry.remaining, remaining);
            entry.remaining -= take;
            remaining -= take;
            matched = ProfitTrackerMath.saturatingAdd(matched, take);
            if (entry.remaining <= 0L) iterator.remove();
        }
        return matched;
    }

    private static final class Entry {
        private final String key;
        private long remaining;
        private final long timestampMs;
        private boolean directEligible;

        private Entry(String key, long remaining, long timestampMs, boolean directEligible) {
            this.key = key;
            this.remaining = remaining;
            this.timestampMs = timestampMs;
            this.directEligible = directEligible;
        }
    }
}
