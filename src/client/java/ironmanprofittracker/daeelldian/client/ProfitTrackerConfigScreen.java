package ironmanprofittracker.daeelldian.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Mod Menu configuration screen using vanilla widgets for focus, narration and keyboard input. */
public final class ProfitTrackerConfigScreen extends Screen {
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;
    private final ProfitTrackerConfig config = IronmanProfitTrackerClient.CONFIG;

    private Button hudButton;
    private Button profitButton;
    private Button rateButton;
    private Button bestSessionButton;
    private Button bestRateButton;
    private Button debugButton;

    private boolean dragging;
    private double dragOffsetX;
    private double dragOffsetY;

    public ProfitTrackerConfigScreen(Screen parent) {
        super(Component.literal("Ironman Profit Tracker"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int bottom = height - 46;
        int togglesTop = Math.max(68, height - 112);

        hudButton = addRenderableWidget(Button.builder(hudLabel(), button -> {
            config.showTracker = !config.showTracker;
            saveAndRefresh();
        }).bounds(12, 32, 150, BUTTON_HEIGHT).build());

        profitButton = addRenderableWidget(Button.builder(toggleLabel("Profit", config.showProfit), button -> {
            config.showProfit = !config.showProfit;
            saveAndRefresh();
        }).bounds(12, togglesTop, 120, BUTTON_HEIGHT).build());

        rateButton = addRenderableWidget(Button.builder(toggleLabel("Profit / hour", config.showProfitPerHour), button -> {
            config.showProfitPerHour = !config.showProfitPerHour;
            saveAndRefresh();
        }).bounds(138, togglesTop, 120, BUTTON_HEIGHT).build());

        bestSessionButton = addRenderableWidget(Button.builder(toggleLabel("Best session", config.showHighestProfit), button -> {
            config.showHighestProfit = !config.showHighestProfit;
            saveAndRefresh();
        }).bounds(12, togglesTop + 24, 120, BUTTON_HEIGHT).build());

        bestRateButton = addRenderableWidget(Button.builder(toggleLabel("Best rate", config.showHighestProfitPerHour), button -> {
            config.showHighestProfitPerHour = !config.showHighestProfitPerHour;
            saveAndRefresh();
        }).bounds(138, togglesTop + 24, 120, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.literal("Size -"), button -> {
            config.scale -= 0.1;
            config.save();
        }).bounds(12, bottom, 70, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.literal("Size +"), button -> {
            config.scale += 0.1;
            config.save();
        }).bounds(88, bottom, 70, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.literal("Reset position"), button -> {
            config.resetPosition();
            config.save();
        }).bounds(164, bottom, 110, BUTTON_HEIGHT).build());

        debugButton = addRenderableWidget(Button.builder(debugLabel(), button -> {
            cycleDebug();
            saveAndRefresh();
        }).bounds(Math.max(12, width - 218), bottom, 100, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width - 112, bottom, 100, BUTTON_HEIGHT).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);

        int px = (int) Math.round(config.x * width);
        int py = (int) Math.round(config.y * height);
        int rawWidth = ProfitTrackerHud.getPreviewRawWidth(config);
        int rawHeight = ProfitTrackerHud.getPreviewRawHeight(config);

        ProfitSource source = IronmanProfitTrackerClient.STATE.getSource();
        if (source == null) source = ProfitSource.DIAMOND_MINING;
        ProfitTrackerState state = IronmanProfitTrackerClient.STATE;

        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(px, py);
            graphics.pose().scale((float) config.scale, (float) config.scale);
            graphics.fill(-6, -6, rawWidth + 6, rawHeight + 6, 0xCC101010);
            graphics.outline(-6, -6, rawWidth + 12, rawHeight + 12, source.getHudAccentColor());
            graphics.item(source.getIconStack(), 0, 0);
            graphics.text(font, "Tracking", 22, 0, 0xFFAAAAAA, true);
            graphics.text(font, source.getDisplayName(), 22, 10, source.getHudAccentColor(), true);

            int line = 2;
            if (config.showProfit) {
                line = previewLine(graphics, line, ProfitTrackerState.formatCoins(state.getProfit()), "profit");
            }
            if (config.showProfitPerHour) {
                line = previewLine(graphics, line, ProfitTrackerState.formatCoins(state.getProfitPerHour()) + "/h", "profit/h");
            }
            if (config.showHighestProfit) {
                line = previewLine(graphics, line, ProfitTrackerState.formatCoins(state.getHighestProfit()), "best session");
            }
            if (config.showHighestProfitPerHour) {
                previewLine(graphics, line, ProfitTrackerState.formatCoins(state.getHighestProfitPerHour()) + "/h", "best rate");
            }
        } finally {
            graphics.pose().popMatrix();
        }

        graphics.text(font, "Drag the tracker preview to move it", 12, 12, 0xFFFFFFFF, true);
        graphics.text(font, String.format(java.util.Locale.ROOT, "Scale: %.1fx", config.scale), 168, 38, 0xFFAAAAAA, false);
    }

    private int previewLine(GuiGraphicsExtractor graphics, int line, String value, String label) {
        int y = line * 10;
        graphics.text(font, label, 3, y, 0xFFAAAAAA, false);
        graphics.text(font, value, 105, y, 0xFFFFFFFF, true);
        return line + 1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;

        int px = (int) Math.round(config.x * width);
        int py = (int) Math.round(config.y * height);
        int previewWidth = ProfitTrackerHud.getPreviewWidth(config);
        int previewHeight = ProfitTrackerHud.getPreviewHeight(config);
        int padding = (int) Math.ceil(6.0 * config.scale);
        if (!inside(event.x(), event.y(), px - padding, py - padding, previewWidth + padding * 2, previewHeight + padding * 2)) return false;

        dragging = true;
        dragOffsetX = event.x() - px;
        dragOffsetY = event.y() - py;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (!dragging) return super.mouseDragged(event, deltaX, deltaY);
        config.x = clamp01((event.x() - dragOffsetX) / Math.max(1.0, width));
        config.y = clamp01((event.y() - dragOffsetY) / Math.max(1.0, height));
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!dragging) return super.mouseReleased(event);
        dragging = false;
        config.save();
        return true;
    }

    @Override
    public void onClose() {
        config.save();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private void saveAndRefresh() {
        config.save();
        hudButton.setMessage(hudLabel());
        profitButton.setMessage(toggleLabel("Profit", config.showProfit));
        rateButton.setMessage(toggleLabel("Profit / hour", config.showProfitPerHour));
        bestSessionButton.setMessage(toggleLabel("Best session", config.showHighestProfit));
        bestRateButton.setMessage(toggleLabel("Best rate", config.showHighestProfitPerHour));
        debugButton.setMessage(debugLabel());
    }

    private void cycleDebug() {
        ProfitTrackerDebug.Level[] levels = ProfitTrackerDebug.Level.values();
        config.debugLevel = levels[(config.debugLevel.ordinal() + 1) % levels.length];
    }

    private Component hudLabel() {
        return toggleLabel("HUD", config.showTracker);
    }

    private Component debugLabel() {
        return Component.literal("Debug: " + config.debugLevel.name());
    }

    private static Component toggleLabel(String name, boolean enabled) {
        return Component.literal(name + ": " + (enabled ? "ON" : "OFF"));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x <= left + width && y >= top && y <= top + height;
    }
}
