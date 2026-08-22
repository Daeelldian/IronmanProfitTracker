package ironmanprofittracker.daeelldian.client;

/** Monotonic clock for all tracker intervals and rate calculations. */
public final class ProfitTrackerClock {
    private ProfitTrackerClock() {}

    public static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
