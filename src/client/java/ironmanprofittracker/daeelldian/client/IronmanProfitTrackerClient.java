package ironmanprofittracker.daeelldian.client;

import ironmanprofittracker.daeelldian.IronmanProfitTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/** Registers the small set of client callbacks used by IPT. */
public final class IronmanProfitTrackerClient implements ClientModInitializer {
    public static final ProfitTrackerConfig CONFIG = ProfitTrackerConfig.load();
    public static final ProfitTrackerState STATE = new ProfitTrackerState();

    private static final int MAINTENANCE_INTERVAL_TICKS = 5; // 4 Hz at normal 20 TPS.
    private int maintenanceTicks;

    @Override
    public void onInitializeClient() {
        HypixelLocationTracker.initialize();
        ProfitTrackerCommands.register();

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) SacksMessageParser.process(message, STATE);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            maintenanceTicks++;
            if (maintenanceTicks < MAINTENANCE_INTERVAL_TICKS) return;
            maintenanceTicks = 0;
            STATE.tick(ProfitTrackerClock.nowMs());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            HypixelLocationTracker.onConnectionBoundary();
            ProfitTrackerDebug.info("Server disconnect detected.");
            STATE.endSessionFromServerChange(ProfitTrackerClock.nowMs());
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            HypixelLocationTracker.onConnectionBoundary();
            STATE.onServerJoin(ProfitTrackerClock.nowMs());
            ProfitTrackerDebug.info("Server join detected; session boundary processed.");
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CHAT,
                IronmanProfitTracker.id("profit_tracker"),
                ProfitTrackerHud::render
        );

        IronmanProfitTracker.LOGGER.info("Ironman Profit Tracker client initialized.");
    }
}
