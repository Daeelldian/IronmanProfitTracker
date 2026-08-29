package ironmanprofittracker.daeelldian.client;

import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;

import java.util.Locale;

/** Uses Hypixel's official location event to make island-specific tracking decisions. */
public final class HypixelLocationTracker {
    private static volatile boolean locationKnown;
    private static volatile boolean skyBlockHub;
    private static volatile String serverType = "";
    private static volatile String mode = "";
    private static volatile String map = "";

    private HypixelLocationTracker() {}

    public static void initialize() {
        HypixelModAPI api = HypixelModAPI.getInstance();
        api.createHandler(ClientboundLocationPacket.class, HypixelLocationTracker::onLocation);
        api.subscribeToEventPacket(ClientboundLocationPacket.class);
    }

    private static void onLocation(ClientboundLocationPacket packet) {
        serverType = optionalText(packet.getServerType().orElse(null));
        mode = optionalText(packet.getMode().orElse(null));
        map = optionalText(packet.getMap().orElse(null));
        locationKnown = true;

        boolean skyBlock = "skyblock".equals(serverType.toLowerCase(Locale.ROOT));
        boolean hub = "hub".equals(mode.toLowerCase(Locale.ROOT)) || "hub".equals(map.toLowerCase(Locale.ROOT));
        skyBlockHub = skyBlock && hub;

        ProfitTrackerDebug.info(
                "Hypixel location updated: serverType=" + serverType + " mode=" + mode + " map=" + map
                        + " skyBlockHub=" + skyBlockHub
        );
    }

    public static void onConnectionBoundary() {
        locationKnown = false;
        skyBlockHub = false;
        serverType = "";
        mode = "";
        map = "";
    }

    public static boolean isSkyBlockHub() { return locationKnown && skyBlockHub; }
    public static boolean isLocationKnown() { return locationKnown; }

    private static String optionalText(Object value) {
        return value == null ? "" : value.toString();
    }
}
