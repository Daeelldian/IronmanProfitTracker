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
    void materialBreakdownNormalizesToLargestCompactedTiers() {
        // 2,000 Enchanted Diamonds = 320,000 raw-equivalent Diamonds.
        long[] diamond = ProfitTrackerMath.compactTierCounts(2_000L * 160L, 25_600L, 160L, 1L);
        assertEquals(12L, diamond[0]);
        assertEquals(80L, diamond[1]);
        assertEquals(0L, diamond[2]);

        // Gemstone families must be compacted independently. Combining these two families first
        // would incorrectly create one Fine Gemstone.
        long[] familyA = ProfitTrackerMath.compactTierCounts(6_399L, 6_400L, 80L, 1L);
        long[] familyB = ProfitTrackerMath.compactTierCounts(1L, 6_400L, 80L, 1L);
        assertEquals(0L, familyA[0] + familyB[0]);
        assertEquals(79L, familyA[1] + familyB[1]);
        assertEquals(80L, familyA[2] + familyB[2]);
    }

    @Test
    void saturatingCountersNeverWrap() {
        assertEquals(Long.MAX_VALUE, ProfitTrackerMath.saturatingAdd(Long.MAX_VALUE - 5L, 10L));
        assertEquals(Long.MAX_VALUE, ProfitTrackerMath.saturatingMultiply(Long.MAX_VALUE, 2L));
        assertEquals(2_280L, ProfitTrackerMath.saturatingMultiply(285L, 8L));
    }
    @Test
    void explicitBuybacksAccumulateWithinOneSacksInterval() {
        BuybackCorrelationLedger ledger = new BuybackCorrelationLedger(120_000L);
        String item = "enchanted end stone";

        ledger.recordBuyback(item, 64L, 100_000L);
        ledger.recordBuyback(item, 36L, 100_500L);

        assertEquals(100L, ledger.consumeDirect(item, 300L, 101_000L));
        ledger.finishSacksInterval();
        assertEquals(0L, ledger.consumeDirect(item, 200L, 102_000L));
    }

    @Test
    void unmatchedBuybackCannotPoisonLaterMiningAfterIntervalCloses() {
        BuybackCorrelationLedger ledger = new BuybackCorrelationLedger(120_000L);
        ledger.recordBuyback("diamond", 64L, 100_000L);

        ledger.finishSacksInterval();
        assertEquals(0L, ledger.consumeDirect("diamond", 100L, 101_000L));
        assertEquals(64L, ledger.pendingAmount("diamond", 101_000L));
    }

    @Test
    void lobbyBoundaryDisablesDirectBuybackButPreservesManualTransferEvidence() {
        BuybackCorrelationLedger ledger = new BuybackCorrelationLedger(120_000L);
        ledger.recordBuyback("enchanted end stone", 64L, 100_000L);

        ledger.onConnectionBoundary();
        assertEquals(0L, ledger.consumeDirect("enchanted end stone", 64L, 101_000L));
        assertEquals(64L, ledger.acknowledgeManualDeposit("enchanted end stone", 64L, 110_000L));
        assertEquals(0L, ledger.pendingAmount("enchanted end stone", 110_000L));
    }

    @Test
    void manualSackDepositSubtractsOnlyTransferredQuantityFromMixedReward() {
        ManualSackTransferLedger ledger = new ManualSackTransferLedger(45_000L);
        ledger.recordDeposit("enchanted end stone", 100L, 100_000L);

        assertEquals(100L, ledger.consume("enchanted end stone", 300L, 101_000L));
        assertEquals(0L, ledger.consume("enchanted end stone", 200L, 102_000L));
    }

    @Test
    void manualSackDepositDoesNotAffectOtherItems() {
        ManualSackTransferLedger ledger = new ManualSackTransferLedger(45_000L);
        ledger.recordDeposit("enchanted diamond block", 4L, 100_000L);

        assertEquals(0L, ledger.consume("diamond", 500L, 101_000L));
        assertEquals(4L, ledger.consume("enchanted diamond block", 4L, 101_000L));
    }

    @Test
    void staleManualDepositCannotSuppressMuchLaterMining() {
        ManualSackTransferLedger ledger = new ManualSackTransferLedger(45_000L);
        ledger.recordDeposit("diamond", 500L, 100_000L);

        assertEquals(0L, ledger.consume("diamond", 500L, 145_001L));
    }

    @Test
    void userReportedMixedBuybackScenarioKeepsRealProfit() {
        BuybackCorrelationLedger buybacks = new BuybackCorrelationLedger(120_000L);
        String item = "enchanted end stone";
        buybacks.recordBuyback(item, 64L, 100_000L);
        buybacks.recordBuyback(item, 36L, 100_200L);

        long sacksAmount = 300L;
        long boughtBack = buybacks.consumeDirect(item, sacksAmount, 101_000L);
        long legitimate = sacksAmount - boughtBack;

        assertEquals(100L, boughtBack);
        assertEquals(200L, legitimate);
    }

    @Test
    void randomChestCleanupDepositProducesZeroEligibleProfitForDepositedPart() {
        ManualSackTransferLedger transfers = new ManualSackTransferLedger(45_000L);
        transfers.recordDeposit("enchanted diamond block", 4L, 100_000L);

        long sacksAmount = 4L;
        long deposited = transfers.consume("enchanted diamond block", sacksAmount, 101_000L);
        assertEquals(0L, sacksAmount - deposited);
    }

}