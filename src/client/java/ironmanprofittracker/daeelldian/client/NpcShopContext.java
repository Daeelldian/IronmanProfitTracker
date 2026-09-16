package ironmanprofittracker.daeelldian.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;

/** Detects Hypixel NPC merchant UIs by their Buyback control/title/lore. */
final class NpcShopContext {
    private NpcShopContext() {}

    static boolean isBuybackCapableShopOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)) return false;

        if (containsBuyback(screen.getTitle().getString())) return true;

        for (ItemStack stack : screen.getMenu().getItems()) {
            if (stack == null || stack.isEmpty()) continue;
            if (containsBuyback(stack.getHoverName().getString())) return true;

            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                if (containsBuyback(line.getString())) return true;
            }
        }
        return false;
    }

    private static boolean containsBuyback(String text) {
        if (text == null || text.isBlank()) return false;
        String normalized = text.toLowerCase(Locale.ROOT).replace(" ", "");
        return normalized.contains("buyback");
    }
}
