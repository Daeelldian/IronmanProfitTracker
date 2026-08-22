package ironmanprofittracker.daeelldian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProfitTrackerMathTest {
    @Test
    void profitPerHourUsesElapsedMillisecondsExactly() {
        assertEquals(2_000_000.0, ProfitTrackerMath.profitPerHour(1_000_000L, 30L * 60_000L), 0.0001);
        assertEquals(1_000_000.0, ProfitTrackerMath.profitPerHour(1_000_000L, 60L * 60_000L), 0.0001);
    }

    @Test
    void invalidProfitOrDurationProducesZeroRate() {
        assertEquals(0.0, ProfitTrackerMath.profitPerHour(0L, 60_000L));
        assertEquals(0.0, ProfitTrackerMath.profitPerHour(100L, 0L));
        assertEquals(0.0, ProfitTrackerMath.profitPerHour(-100L, 60_000L));
    }

    @Test
    void sackWindowDefinesActivityStartWithoutDoubleCounting() {
        assertEquals(70_000L, ProfitTrackerMath.activityWindowStartMs(100_000L, 30_000L));
        assertEquals(88_000L, ProfitTrackerMath.activityWindowStartMs(100_000L, 12_000L));
        assertEquals(0L, ProfitTrackerMath.activityWindowStartMs(10_000L, 30_000L));
        assertEquals(45_000L, ProfitTrackerMath.durationMs(70_000L, 115_000L));
    }


    @Test
    void pauseOverlapIsClippedToSessionBoundary() {
        assertEquals(30_000L, ProfitTrackerMath.overlapMs(70_000L, 160_000L, 100_000L, 130_000L));
        assertEquals(5_000L, ProfitTrackerMath.overlapMs(70_000L, 105_000L, 100_000L, 130_000L));
        assertEquals(0L, ProfitTrackerMath.overlapMs(70_000L, 90_000L, 100_000L, 130_000L));
    }

    @Test
    void activeDurationExcludesAfkPauseWindow() {
        long sessionStart = 10_000L;
        long pauseStart = sessionStart + 30L * 60_000L;
        long now = pauseStart + 30L * 60_000L;

        long wallDuration = ProfitTrackerMath.durationMs(sessionStart, now);
        long paused = ProfitTrackerMath.overlapMs(sessionStart, now, pauseStart, now);
        long activeDuration = wallDuration - paused;

        assertEquals(30L * 60_000L, activeDuration);
        assertEquals(2_000_000.0, ProfitTrackerMath.profitPerHour(1_000_000L, activeDuration), 0.0001);
    }

    @Test
    void saturatingCountersNeverWrap() {
        assertEquals(Long.MAX_VALUE, ProfitTrackerMath.saturatingAdd(Long.MAX_VALUE - 5L, 10L));
        assertEquals(Long.MAX_VALUE, ProfitTrackerMath.saturatingMultiply(Long.MAX_VALUE, 2L));
        assertEquals(2_280L, ProfitTrackerMath.saturatingMultiply(285L, 8L));
    }
}
