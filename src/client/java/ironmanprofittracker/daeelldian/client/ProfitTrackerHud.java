package ironmanprofittracker.daeelldian.client;

import ironmanprofittracker.daeelldian.IronmanProfitTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.Locale;

/** Lightweight HUD renderer. Expensive/formatted values are snapshotted at 4 Hz, not every frame. */
public final class ProfitTrackerHud {
    private static final int PAD = 5;
    private static final int ICON_SIZE = 16;
    private static final int TEXT_X = 22;
    private static final int VALUE_GAP = 8;
    private static final int VALUE_MIN_X = 105;
    private static final int LINE_HEIGHT = 10;
    private static final int MATERIAL_ROW_HEIGHT = 18;
    private static final int MATERIAL_ENTRY_GAP = 5;
    private static final int MATERIAL_TEXT_ICON_GAP = 2;
    private static final long SNAPSHOT_INTERVAL_MS = 250L;

    private static long lastSnapshotMs;
    private static HudSnapshot cachedSnapshot;
    private static boolean disabledAfterRenderFailure;

    private ProfitTrackerHud() {}

    public static void render(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        if (disabledAfterRenderFailure) return;
        try {
            renderInternal(graphics);
        } catch (RuntimeException exception) {
            disabledAfterRenderFailure = true;
            IronmanProfitTracker.LOGGER.error(
                    "IPT HUD rendering failed and has been disabled for this game session to prevent repeated frame-time errors.",
                    exception
            );
        }
    }

    private static void renderInternal(GuiGraphicsExtractor graphics) {
        ProfitTrackerConfig config = IronmanProfitTrackerClient.CONFIG;
        ProfitTrackerState state = IronmanProfitTrackerClient.STATE;
        if (!config.showTracker || !state.isSessionActive()) return;

        Minecraft minecraft = Minecraft.getInstance();
        ProfitSource source = state.getSource();
        if (minecraft.player == null || minecraft.options.hideGui || source == null) return;

        HudSnapshot snapshot = snapshot(source, state);
        Layout layout = getLayout(minecraft, config, source, snapshot);
        int x = (int) Math.round(config.x * graphics.guiWidth());
        int y = (int) Math.round(config.y * graphics.guiHeight());
        float scale = (float) config.scale;

        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x, y);
            graphics.pose().scale(scale, scale);
            graphics.fill(-PAD, -PAD, layout.width + PAD, layout.height + PAD, 0xAA101010);
            graphics.outline(-PAD, -PAD, layout.width + PAD * 2, layout.height + PAD * 2, source.getHudAccentColor());
            graphics.item(source.getIconStack(), 0, 0);

            String trackingText = snapshot.paused ? "Tracking (PAUSED)" : "Tracking";
            int trackingColor = snapshot.paused ? 0xFFFF5555 : 0xFFAAAAAA;
            graphics.text(minecraft.font, trackingText, TEXT_X, 0, trackingColor, true);
            graphics.text(minecraft.font, source.getDisplayName(), TEXT_X, 10, source.getHudAccentColor(), true);

            int line = 2;
            if (config.showProfit) line = text(graphics, minecraft, snapshot.profit, "profit", line, layout.valueX);
            if (config.showProfitPerHour) line = text(graphics, minecraft, snapshot.profitPerHour, "profit/h", line, layout.valueX);
            if (config.showHighestProfit) line = text(graphics, minecraft, snapshot.highestProfit, "best session", line, layout.valueX);
            if (config.showHighestProfitPerHour) line = text(graphics, minecraft, snapshot.highestProfitPerHour, "best rate", line, layout.valueX);

            if (!snapshot.materials.isEmpty()) {
                drawMaterials(graphics, minecraft, snapshot.materials, line * LINE_HEIGHT + 1);
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static HudSnapshot snapshot(ProfitSource source, ProfitTrackerState state) {
        long nowMs = ProfitTrackerClock.nowMs();
        boolean paused = state.isSessionPaused();
        if (cachedSnapshot == null
                || cachedSnapshot.source != source
                || cachedSnapshot.paused != paused
                || nowMs - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS) {
            cachedSnapshot = new HudSnapshot(
                    source,
                    paused,
                    format(state.getProfit()),
                    format(state.getProfitPerHour()) + "/h",
                    format(state.getHighestProfit()),
                    format(state.getHighestProfitPerHour()) + "/h",
                    state.getMaterialBreakdown()
            );
            lastSnapshotMs = nowMs;
        }
        return cachedSnapshot;
    }

    private static int text(GuiGraphicsExtractor graphics, Minecraft minecraft, String value, String label, int line, int valueX) {
        int y = line * LINE_HEIGHT;
        graphics.text(minecraft.font, label, 3, y, 0xFFAAAAAA, false);
        graphics.text(minecraft.font, value, valueX, y, 0xFFFFFFFF, true);
        return line + 1;
    }

    private static void drawMaterials(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft,
            List<ProfitSource.MaterialDisplayEntry> materials,
            int y
    ) {
        int x = 3;
        for (ProfitSource.MaterialDisplayEntry entry : materials) {
            String count = formatItemCount(entry.count());
            graphics.text(minecraft.font, count, x, y + 4, 0xFFFFFFFF, true);
            x += minecraft.font.width(count) + MATERIAL_TEXT_ICON_GAP;
            graphics.item(entry.iconStack(), x, y);
            x += ICON_SIZE + MATERIAL_ENTRY_GAP;
        }
    }

    private static Layout getLayout(Minecraft minecraft, ProfitTrackerConfig config, ProfitSource source, HudSnapshot snapshot) {
        int valueX = VALUE_MIN_X;
        if (config.showProfit) valueX = Math.max(valueX, 3 + minecraft.font.width("profit") + VALUE_GAP);
        if (config.showProfitPerHour) valueX = Math.max(valueX, 3 + minecraft.font.width("profit/h") + VALUE_GAP);
        if (config.showHighestProfit) valueX = Math.max(valueX, 3 + minecraft.font.width("best session") + VALUE_GAP);
        if (config.showHighestProfitPerHour) valueX = Math.max(valueX, 3 + minecraft.font.width("best rate") + VALUE_GAP);

        String trackingText = snapshot.paused ? "Tracking (PAUSED)" : "Tracking";
        int width = Math.max(ICON_SIZE, TEXT_X + minecraft.font.width(trackingText));
        width = Math.max(width, TEXT_X + minecraft.font.width(source.getDisplayName()));
        if (config.showProfit) width = Math.max(width, valueX + minecraft.font.width(snapshot.profit));
        if (config.showProfitPerHour) width = Math.max(width, valueX + minecraft.font.width(snapshot.profitPerHour));
        if (config.showHighestProfit) width = Math.max(width, valueX + minecraft.font.width(snapshot.highestProfit));
        if (config.showHighestProfitPerHour) width = Math.max(width, valueX + minecraft.font.width(snapshot.highestProfitPerHour));
        if (!snapshot.materials.isEmpty()) width = Math.max(width, materialRowWidth(minecraft, snapshot.materials));

        int lines = visibleTextLines(config);
        int height = lines * LINE_HEIGHT + 2;
        if (!snapshot.materials.isEmpty()) height += MATERIAL_ROW_HEIGHT;
        return new Layout(width, height, valueX);
    }

    private static int materialRowWidth(Minecraft minecraft, List<ProfitSource.MaterialDisplayEntry> materials) {
        int width = 3;
        for (ProfitSource.MaterialDisplayEntry entry : materials) {
            width += minecraft.font.width(formatItemCount(entry.count()))
                    + MATERIAL_TEXT_ICON_GAP + ICON_SIZE + MATERIAL_ENTRY_GAP;
        }
        return Math.max(0, width - MATERIAL_ENTRY_GAP);
    }

    private static int visibleTextLines(ProfitTrackerConfig config) {
        return 2
                + (config.showProfit ? 1 : 0)
                + (config.showProfitPerHour ? 1 : 0)
                + (config.showHighestProfit ? 1 : 0)
                + (config.showHighestProfitPerHour ? 1 : 0);
    }

    public static int getPreviewWidth(ProfitTrackerConfig config) {
        return (int) Math.ceil(getPreviewRawWidth(config) * config.scale);
    }

    public static int getPreviewHeight(ProfitTrackerConfig config) {
        return (int) Math.ceil(getPreviewRawHeight(config) * config.scale);
    }

    static int getPreviewRawHeight(ProfitTrackerConfig config) {
        ProfitSource source = previewSource();
        return visibleTextLines(config) * LINE_HEIGHT + 2
                + (source.hasMaterialBreakdown() ? MATERIAL_ROW_HEIGHT : 0);
    }

    static int getPreviewRawWidth(ProfitTrackerConfig config) {
        Minecraft minecraft = Minecraft.getInstance();
        ProfitSource source = previewSource();

        int valueX = VALUE_MIN_X;
        int width = Math.max(ICON_SIZE, TEXT_X + minecraft.font.width("Tracking (PAUSED)"));
        width = Math.max(width, TEXT_X + minecraft.font.width(source.getDisplayName()));
        if (config.showProfit) width = Math.max(width, valueX + minecraft.font.width("888.88M"));
        if (config.showProfitPerHour) width = Math.max(width, valueX + minecraft.font.width("888.88M/h"));
        if (config.showHighestProfit) width = Math.max(width, valueX + minecraft.font.width("888.88M"));
        if (config.showHighestProfitPerHour) width = Math.max(width, valueX + minecraft.font.width("888.88M/h"));
        if (source.hasMaterialBreakdown()) width = Math.max(width, materialRowWidth(minecraft, source.previewMaterials()));
        return width;
    }

    static void renderPreviewMaterials(GuiGraphicsExtractor graphics, Minecraft minecraft, ProfitSource source, int y) {
        if (source == null || !source.hasMaterialBreakdown()) return;
        List<ProfitSource.MaterialDisplayEntry> materials = IronmanProfitTrackerClient.STATE.isSessionActive()
                && IronmanProfitTrackerClient.STATE.getSource() == source
                ? IronmanProfitTrackerClient.STATE.getMaterialBreakdown()
                : source.previewMaterials();
        drawMaterials(graphics, minecraft, materials, y);
    }

    private static ProfitSource previewSource() {
        ProfitSource source = IronmanProfitTrackerClient.STATE.getSource();
        return source == null ? ProfitSource.DIAMOND_MINING : source;
    }

    private static String format(double value) {
        return ProfitTrackerState.formatCoins(value);
    }

    private static String formatItemCount(long count) {
        return String.format(Locale.ROOT, "%,d", Math.max(0L, count));
    }

    private record Layout(int width, int height, int valueX) {}

    private record HudSnapshot(
            ProfitSource source,
            boolean paused,
            String profit,
            String profitPerHour,
            String highestProfit,
            String highestProfitPerHour,
            List<ProfitSource.MaterialDisplayEntry> materials
    ) {}
}
