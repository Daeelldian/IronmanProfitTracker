package ironmanprofittracker.daeelldian.client;

import ironmanprofittracker.daeelldian.BuybackCorrelationLedger;
import ironmanprofittracker.daeelldian.ManualSackTransferLedger;
import ironmanprofittracker.daeelldian.ProfitTrackerMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves short-lived non-profit causes around sack additions (Supercraft, stash transfer,
 * explicit NPC buybacks, and manual inventory-to-sacks deposits) before rewards reach the session
 * engine.
 */
final class RewardCorrelationTracker {
    private static final long CRAFT_WINDOW_MS = 8_000L;
    private static final long STASH_CONTEXT_MS = 15_000L;
    private static final long NPC_BUYBACK_GRACE_MS = 3_000L;
    private static final long BUYBACK_RETENTION_MS = 2L * 60_000L;
    private static final long MANUAL_TRANSFER_RETENTION_MS = 45_000L;
    private static final long SACKS_UI_GRACE_MS = 1_000L;

    private final List<PendingCraft> pendingCrafts = new ArrayList<>();
    private final BuybackCorrelationLedger explicitBuybacks = new BuybackCorrelationLedger(BUYBACK_RETENTION_MS);
    private final ManualSackTransferLedger manualTransfers = new ManualSackTransferLedger(MANUAL_TRANSFER_RETENTION_MS);
    private final Map<String, Long> lastTrackedInventory = new HashMap<>();

    private boolean stashContextArmed;
    private long stashContextUntilMs;
    private long npcBuybackContextUntilMs;
    private long sacksUiContextUntilMs;
    private boolean inventorySnapshotReady;

    /**
     * Session/lobby reset. Explicit buyback history intentionally survives a lobby switch so a
     * bought-back item deposited in the new lobby can still be recognized. Manual-deposit evidence
     * is local to the current connection and is cleared at the boundary.
     */
    void reset() {
        pendingCrafts.clear();
        consumeStashContext();
        npcBuybackContextUntilMs = 0L;
        sacksUiContextUntilMs = 0L;
        inventorySnapshotReady = false;
        lastTrackedInventory.clear();
        manualTransfers.clear();
        explicitBuybacks.onConnectionBoundary();
    }

    void onSupercraft(String itemName, long amount, long nowMs) {
        if (amount <= 0L || itemName == null || itemName.isBlank()) return;
        purge(nowMs);
        pendingCrafts.add(new PendingCraft(normalizeName(itemName), amount, nowMs));
        ProfitTrackerDebug.info("Supercraft pending: " + itemName.trim() + " x" + amount);
    }

    void onBuyback(String itemName, long amount, long nowMs) {
        if (amount <= 0L || itemName == null || itemName.isBlank()) return;
        purge(nowMs);
        String key = normalizeName(itemName);
        explicitBuybacks.recordBuyback(key, amount, nowMs);
        // Direct buyback suppression is deliberately narrow. If the item remains in inventory and
        // is deposited later (even after a lobby switch), the inventory-transfer detector handles it.
        npcBuybackContextUntilMs = Math.max(npcBuybackContextUntilMs, nowMs + NPC_BUYBACK_GRACE_MS);
        ProfitTrackerDebug.info("Explicit buyback pending for current Sacks interval: "
                + itemName.trim() + " x" + amount + ".");
    }

    void onStashMessage(String text, long nowMs) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!lower.contains("from this stash to your sacks")) {
            ProfitTrackerDebug.trace("Observed stash message without arming sacks context: " + text);
            return;
        }

        stashContextArmed = true;
        stashContextUntilMs = nowMs + STASH_CONTEXT_MS;
        ProfitTrackerDebug.info("Stash-to-sacks context armed for the next sack addition.");
    }

    void observeNpcShop(boolean buybackCapableShopOpen, long nowMs) {
        if (buybackCapableShopOpen) {
            npcBuybackContextUntilMs = Math.max(npcBuybackContextUntilMs, nowMs + NPC_BUYBACK_GRACE_MS);
        }
    }

    /**
     * Samples the player's tracked inventory. A decrease while a Sacks management UI is active is
     * treated as a manual deposit, not earned material. The cursor stack is included so merely
     * picking an item up with the mouse is not mistaken for a deposit.
     */
    void observeClientInventory(long nowMs) {
        observeClientInventory(nowMs, Set.of());
    }

    /**
     * Event-boundary snapshot. Even if a resource-pack/custom menu title hides the word "Sack",
     * a same-item inventory decrease arriving with the Sacks addition is strong transfer evidence.
     */
    void observeClientInventoryForSacksEvent(ParsedSacksEvent event) {
        if (event == null) return;
        Set<String> eventKeys = new HashSet<>();
        for (ParsedSacksEvent.SourceReward reward : event.rewards().values()) {
            for (ParsedSacksEvent.RewardLine line : reward.lines()) eventKeys.add(normalizeName(line.itemName()));
        }
        for (ParsedSacksEvent.RewardLine line : event.miningBonusLines()) eventKeys.add(normalizeName(line.itemName()));
        observeClientInventory(event.timestampMs(), eventKeys);
    }

    private void observeClientInventory(long nowMs, Set<String> eventKeys) {
        purge(nowMs);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            inventorySnapshotReady = false;
            lastTrackedInventory.clear();
            return;
        }

        boolean sacksScreenOpen = SacksUiContext.isSacksScreenOpen();
        if (sacksScreenOpen) {
            sacksUiContextUntilMs = Math.max(sacksUiContextUntilMs, nowMs + SACKS_UI_GRACE_MS);
        }
        boolean transferContext = sacksScreenOpen || nowMs <= sacksUiContextUntilMs;

        Map<String, Long> current = snapshotTrackedInventory(minecraft);
        if (inventorySnapshotReady) {
            for (Map.Entry<String, Long> previous : lastTrackedInventory.entrySet()) {
                long nowCount = current.getOrDefault(previous.getKey(), 0L);
                long removed = previous.getValue() - nowCount;
                if (removed <= 0L) continue;
                if (!transferContext && !eventKeys.contains(previous.getKey())) continue;

                manualTransfers.recordDeposit(previous.getKey(), removed, nowMs);
                long matchedBuyback = explicitBuybacks.acknowledgeManualDeposit(previous.getKey(), removed, nowMs);
                ProfitTrackerDebug.info("Detected manual Sacks deposit: " + previous.getKey() + " x" + removed
                        + (matchedBuyback > 0L ? " (" + matchedBuyback + " matched prior buyback)" : "") + ".");
            }
        }

        lastTrackedInventory.clear();
        lastTrackedInventory.putAll(current);
        inventorySnapshotReady = true;
    }

    /** Negative Sacks messages are never profit, but they still close the explicit-buyback interval. */
    void onNonProfitSacksEvent(long nowMs) {
        purge(nowMs);
        explicitBuybacks.finishSacksInterval();
    }

    ResolvedEvent resolve(ParsedSacksEvent event) {
        long nowMs = event.timestampMs();
        purge(nowMs);

        boolean stash = stashContextArmed && nowMs <= stashContextUntilMs;
        if (stash) consumeStashContext();

        boolean directBuybackContext = nowMs <= npcBuybackContextUntilMs;
        Map<ProfitSource, ParsedSacksEvent.SourceReward> filtered = new EnumMap<>(ProfitSource.class);
        List<ParsedSacksEvent.RewardLine> filteredBonuses = new ArrayList<>();
        int suppressedCraftLines = 0;
        long suppressedManualTransferItems = 0L;
        long suppressedExplicitBuybackItems = 0L;

        for (Map.Entry<ProfitSource, ParsedSacksEvent.SourceReward> entry : event.rewards().entrySet()) {
            List<ParsedSacksEvent.RewardLine> keptLines = new ArrayList<>();
            long keptItems = 0L;
            long keptProfit = 0L;

            for (ParsedSacksEvent.RewardLine line : entry.getValue().lines()) {
                if (consumeMatchingCraft(line)) {
                    suppressedCraftLines++;
                    ProfitTrackerDebug.trace("Craft correlation suppressed " + line.itemName() + " x" + line.amount());
                    continue;
                }

                Adjustment manual = suppressManualTransfer(line, nowMs);
                suppressedManualTransferItems = ProfitTrackerMath.saturatingAdd(
                        suppressedManualTransferItems, manual.suppressedAmount());
                if (manual.line() == null) continue;

                Adjustment buyback = directBuybackContext
                        ? suppressExplicitBuyback(manual.line(), nowMs)
                        : new Adjustment(manual.line(), 0L);
                suppressedExplicitBuybackItems = ProfitTrackerMath.saturatingAdd(
                        suppressedExplicitBuybackItems, buyback.suppressedAmount());
                if (buyback.line() == null) continue;

                keptLines.add(buyback.line());
                keptItems = ProfitTrackerMath.saturatingAdd(keptItems, buyback.line().amount());
                keptProfit = ProfitTrackerMath.saturatingAdd(keptProfit, buyback.line().profit());
            }

            if (keptItems > 0L && keptProfit > 0L) {
                filtered.put(entry.getKey(), new ParsedSacksEvent.SourceReward(keptItems, keptProfit, keptLines));
            }
        }

        for (ParsedSacksEvent.RewardLine bonusLine : event.miningBonusLines()) {
            if (consumeMatchingCraft(bonusLine)) {
                suppressedCraftLines++;
                ProfitTrackerDebug.trace("Craft correlation suppressed mining bonus "
                        + bonusLine.itemName() + " x" + bonusLine.amount());
                continue;
            }

            Adjustment manual = suppressManualTransfer(bonusLine, nowMs);
            suppressedManualTransferItems = ProfitTrackerMath.saturatingAdd(
                    suppressedManualTransferItems, manual.suppressedAmount());
            if (manual.line() == null) continue;

            Adjustment buyback = directBuybackContext
                    ? suppressExplicitBuyback(manual.line(), nowMs)
                    : new Adjustment(manual.line(), 0L);
            suppressedExplicitBuybackItems = ProfitTrackerMath.saturatingAdd(
                    suppressedExplicitBuybackItems, buyback.suppressedAmount());
            if (buyback.line() != null) filteredBonuses.add(buyback.line());
        }

        // Explicit buyback chat is scoped to this one Sacks reporting interval. Any unmatched
        // quantity becomes historical-only evidence and cannot suppress later normal mining.
        explicitBuybacks.finishSacksInterval();

        ParsedSacksEvent effective = new ParsedSacksEvent(
                event.timestampMs(),
                event.addition(),
                event.reportedItemCount(),
                event.accountingWindowMs(),
                filtered,
                filteredBonuses
        );
        return new ResolvedEvent(
                effective,
                stash ? RewardContext.STASH : RewardContext.NORMAL,
                suppressedCraftLines,
                suppressedManualTransferItems,
                suppressedExplicitBuybackItems,
                directBuybackContext
        );
    }

    void tick(long nowMs) {
        purge(nowMs);
    }

    private Adjustment suppressManualTransfer(ParsedSacksEvent.RewardLine line, long nowMs) {
        String key = normalizeName(line.itemName());
        long transferred = manualTransfers.consume(key, line.amount(), nowMs);
        if (transferred <= 0L) return new Adjustment(line, 0L);

        long remaining = line.amount() - transferred;
        ProfitTrackerDebug.trace("Manual Sacks transfer suppressed " + line.itemName() + " x" + transferred
                + (remaining > 0L ? "; kept x" + remaining : ""));
        return new Adjustment(withAmount(line, remaining), transferred);
    }

    private Adjustment suppressExplicitBuyback(ParsedSacksEvent.RewardLine line, long nowMs) {
        String key = normalizeName(line.itemName());
        long pending = explicitBuybacks.pendingAmount(key, nowMs);
        if (pending <= 0L) return new Adjustment(line, 0L);

        // If the full matching buyback quantity is visibly still in the player inventory, it did
        // not cause this Sacks addition. Leave the reward untouched and let a later manual deposit
        // consume the historical buyback evidence instead.
        long directCandidate = Math.min(pending, line.amount());
        if (directCandidate > 0L && lastTrackedInventory.getOrDefault(key, 0L) >= directCandidate) {
            ProfitTrackerDebug.trace("Explicit buyback still present in inventory; not suppressing Sacks reward for "
                    + line.itemName() + " x" + directCandidate + ".");
            return new Adjustment(line, 0L);
        }

        long buyback = explicitBuybacks.consumeDirect(key, line.amount(), nowMs);
        if (buyback <= 0L) return new Adjustment(line, 0L);

        long remaining = line.amount() - buyback;
        ProfitTrackerDebug.trace("Explicit buyback correlation suppressed " + line.itemName() + " x" + buyback
                + (remaining > 0L ? "; kept x" + remaining : ""));
        return new Adjustment(withAmount(line, remaining), buyback);
    }

    private static ParsedSacksEvent.RewardLine withAmount(ParsedSacksEvent.RewardLine line, long amount) {
        if (amount <= 0L) return null;
        return new ParsedSacksEvent.RewardLine(
                line.itemName(),
                amount,
                line.npcSellPrice(),
                line.materialFamily(),
                line.baseUnitsPerItem()
        );
    }

    private boolean consumeMatchingCraft(ParsedSacksEvent.RewardLine line) {
        String normalizedItem = normalizeName(line.itemName());
        Iterator<PendingCraft> iterator = pendingCrafts.iterator();
        while (iterator.hasNext()) {
            PendingCraft craft = iterator.next();
            if (craft.amount == line.amount() && craft.normalizedItemName.equals(normalizedItem)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private Map<String, Long> snapshotTrackedInventory(Minecraft minecraft) {
        Map<String, Long> counts = new HashMap<>();
        var inventory = minecraft.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            addTrackedStack(counts, inventory.getItem(slot));
        }
        if (minecraft.screen instanceof AbstractContainerScreen<?> screen) {
            addTrackedStack(counts, screen.getMenu().getCarried());
        }
        return counts;
    }

    private static void addTrackedStack(Map<String, Long> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 0) return;
        String key = ProfitSource.canonicalTrackedItemKey(stack.getHoverName().getString());
        if (key == null) return;
        counts.merge(key, (long) stack.getCount(), ProfitTrackerMath::saturatingAdd);
    }

    private void purge(long nowMs) {
        Iterator<PendingCraft> iterator = pendingCrafts.iterator();
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().timestampMs > CRAFT_WINDOW_MS) iterator.remove();
        }
        manualTransfers.purge(nowMs);
        explicitBuybacks.purge(nowMs);
        if (stashContextArmed && nowMs > stashContextUntilMs) consumeStashContext();
        if (nowMs > npcBuybackContextUntilMs) npcBuybackContextUntilMs = 0L;
        if (nowMs > sacksUiContextUntilMs) sacksUiContextUntilMs = 0L;
    }

    private void consumeStashContext() {
        stashContextArmed = false;
        stashContextUntilMs = 0L;
    }

    private static String normalizeName(String value) {
        return ProfitSource.normalizeItemName(value);
    }

    record ResolvedEvent(
            ParsedSacksEvent event,
            RewardContext context,
            int suppressedCraftLines,
            long suppressedManualTransferItems,
            long suppressedExplicitBuybackItems,
            boolean directBuybackContext
    ) {}

    private record PendingCraft(String normalizedItemName, long amount, long timestampMs) {}
    private record Adjustment(ParsedSacksEvent.RewardLine line, long suppressedAmount) {}
}
