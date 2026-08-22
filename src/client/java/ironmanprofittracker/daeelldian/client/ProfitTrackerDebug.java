package ironmanprofittracker.daeelldian.client;

import ironmanprofittracker.daeelldian.IronmanProfitTracker;

/**
 * Event-driven diagnostics. DEBUG/TRACE write to latest.log only and are never
 * emitted from frame rendering or continuously polled getters.
 */
public final class ProfitTrackerDebug {
    public enum Level { OFF, ERRORS, DEBUG, TRACE }

    private ProfitTrackerDebug() {}

    public static void trace(String message) {
        if (enabled(Level.TRACE)) IronmanProfitTracker.LOGGER.info("[IPT TRACE] {}", message);
    }

    public static void info(String message) {
        if (enabled(Level.DEBUG)) IronmanProfitTracker.LOGGER.info("[IPT DEBUG] {}", message);
    }

    public static void error(String stage, RuntimeException exception) {
        if (!enabled(Level.ERRORS)) return;
        String detail = exception == null
                ? "unknown error"
                : exception.getClass().getSimpleName() + ": " + exception.getMessage();
        IronmanProfitTracker.LOGGER.error("[IPT ERROR] {} -> {}", stage, detail, exception);
    }

    public static void error(String stage, String detail) {
        if (enabled(Level.ERRORS)) IronmanProfitTracker.LOGGER.error("[IPT ERROR] {} -> {}", stage, detail);
    }

    public static boolean enabled(Level required) {
        return level().ordinal() >= required.ordinal();
    }

    private static Level level() {
        ProfitTrackerConfig config = IronmanProfitTrackerClient.CONFIG;
        return config == null || config.debugLevel == null ? Level.OFF : config.debugLevel;
    }
}
