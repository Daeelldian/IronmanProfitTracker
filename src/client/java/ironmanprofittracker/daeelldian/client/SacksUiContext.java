package ironmanprofittracker.daeelldian.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.Locale;

/** Conservative detector for Hypixel Sacks management/container screens. */
final class SacksUiContext {
    private SacksUiContext() {}

    static boolean isSacksScreenOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof AbstractContainerScreen<?> screen)) return false;
        String title = screen.getTitle().getString();
        if (title == null || title.isBlank()) return false;
        return title.toLowerCase(Locale.ROOT).contains("sack");
    }
}
