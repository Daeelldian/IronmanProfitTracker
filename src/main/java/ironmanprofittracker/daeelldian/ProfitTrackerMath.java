package ironmanprofittracker.daeelldian;

/** Pure timing/arithmetic helpers kept independent from Minecraft so critical calculations are unit-testable. */
public final class ProfitTrackerMath {
    private static final double MILLIS_PER_HOUR = 3_600_000.0;

    private ProfitTrackerMath() {}

    /** Converts a sack notification endpoint plus its acquisition window into an activity start. */
    public static long activityWindowStartMs(long measurementAtMs, long windowMs) {
        if (measurementAtMs <= 0L) return 0L;
        long safeWindowMs = Math.max(0L, windowMs);
        return safeWindowMs >= measurementAtMs ? 0L : measurementAtMs - safeWindowMs;
    }

    public static long durationMs(long startMs, long endMs) {
        if (endMs <= startMs) return 1L;
        return endMs - startMs;
    }


    /** Returns the overlap of two half-open millisecond intervals, or zero when they do not overlap. */
    public static long overlapMs(long rangeStartMs, long rangeEndMs, long intervalStartMs, long intervalEndMs) {
        long start = Math.max(rangeStartMs, intervalStartMs);
        long end = Math.min(rangeEndMs, intervalEndMs);
        return Math.max(0L, end - start);
    }

    public static double profitPerHour(long profit, long durationMs) {
        if (profit <= 0L || durationMs <= 0L) return 0.0;
        double rate = profit * MILLIS_PER_HOUR / (double) durationMs;
        return Double.isFinite(rate) && rate >= 0.0 ? rate : 0.0;
    }

    /** Adds non-negative counters without ever wrapping into a negative value. */
    public static long saturatingAdd(long left, long right) {
        if (left <= 0L) return Math.max(0L, right);
        if (right <= 0L) return left;
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    /** Multiplies non-negative counters without overflow. */
    public static long saturatingMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }
}
