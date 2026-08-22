package ironmanprofittracker.daeelldian.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/** Client-only IPT commands. */
public final class ProfitTrackerCommands {
    private static final long CLEAR_CONFIRM_WINDOW_MS = 30_000L;

    private static ProfitSource pendingClearSource;
    private static long pendingClearUntilMs;

    private ProfitTrackerCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommands.literal("ipt")
                        .then(ClientCommands.literal("stats")
                                .then(ClientCommands.argument("tracked_mmm", StringArgumentType.word())
                                        .executes(command -> {
                                            showStats(StringArgumentType.getString(command, "tracked_mmm"));
                                            return 1;
                                        }))
                                .executes(command -> {
                                    error("Usage: /ipt stats <tracked mmm>");
                                    return 0;
                                }))
                        .then(ClientCommands.literal("clearstats")
                                .then(ClientCommands.argument("tracked_mmm", StringArgumentType.word())
                                        .executes(command -> {
                                            requestClear(StringArgumentType.getString(command, "tracked_mmm"));
                                            return 1;
                                        }))
                                .executes(command -> {
                                    error("Usage: /ipt clearstats <tracked mmm>");
                                    return 0;
                                }))
                        .then(ClientCommands.literal("confirm")
                                .executes(command -> {
                                    confirmClear();
                                    return 1;
                                }))
        ));
    }

    private static void requestClear(String requested) {
        ProfitSource source = findSource(requested);
        if (source == null) {
            error("Unknown MMM: " + requested);
            return;
        }

        pendingClearSource = source;
        pendingClearUntilMs = ProfitTrackerClock.nowMs() + CLEAR_CONFIRM_WINDOW_MS;
        send(Component.literal("[IPT] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Reset statistics? ").withStyle(ChatFormatting.RED))
                .append(Component.literal(source.getDisplayName()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" will have all stored IPT records deleted.\n").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Type ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/ipt confirm").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" within 30 seconds to confirm.").withStyle(ChatFormatting.GRAY)));
    }

    private static void confirmClear() {
        ProfitSource source = pendingClearSource;
        long nowMs = ProfitTrackerClock.nowMs();
        if (source == null || nowMs > pendingClearUntilMs) {
            pendingClearSource = null;
            pendingClearUntilMs = 0L;
            error("There is no active statistics reset to confirm.");
            return;
        }

        pendingClearSource = null;
        pendingClearUntilMs = 0L;
        IronmanProfitTrackerClient.STATE.clearStatistics(source);
        send(Component.literal("[IPT] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Statistics reset").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(source.getDisplayName()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" records have been cleared.").withStyle(ChatFormatting.GRAY)));
    }

    private static void showStats(String requested) {
        ProfitSource source = findSource(requested);
        if (source == null) {
            error("Unknown MMM: " + requested);
            return;
        }

        ProfitTrackerRecords records = IronmanProfitTrackerClient.STATE.getRecords();
        long total = records.getTotalProfit(source);
        long high = records.getHighestProfit(source);
        double highRate = records.getHighestProfitPerHour(source);
        long recovered = records.getRecoveredProfit(source);

        MutableComponent line = Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━").withStyle(ChatFormatting.DARK_GRAY);
        MutableComponent message = Component.literal("").append(line.copy()).append("\n")
                .append(Component.literal("[IPT] ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(source.getDisplayName()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" Stats").withStyle(ChatFormatting.WHITE)).append("\n")
                .append(label("Total Profit", ProfitTrackerState.formatCoins(total))).append("\n")
                .append(label("Highest Session", ProfitTrackerState.formatCoins(high))).append("\n")
                .append(label("Highest Profit/h", ProfitTrackerState.formatCoins(highRate) + "/h")).append("\n")
                .append(label("Recovered From Stash", ProfitTrackerState.formatCoins(recovered))).append("\n")
                .append(line.copy());
        send(message);
    }

    private static MutableComponent label(String name, String value) {
        return Component.literal("  " + name + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.GREEN));
    }

    private static void error(String text) {
        send(Component.literal("[IPT] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("Error").withStyle(ChatFormatting.RED))
                .append(Component.literal("  " + text).withStyle(ChatFormatting.GRAY)));
    }

    private static void send(Component component) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null && minecraft.player != null) {
            minecraft.gui.getChat().addClientSystemMessage(component);
        }
    }

    private static ProfitSource findSource(String requested) {
        String normalized = requested.toLowerCase(Locale.ROOT).replace('-', '_');
        for (ProfitSource source : ProfitSource.values()) {
            if (source.getId().equals(normalized)
                    || source.getDisplayName().toLowerCase(Locale.ROOT).replace(' ', '_').equals(normalized)
                    || shortName(source).equals(normalized)) {
                return source;
            }
        }
        return null;
    }

    private static String shortName(ProfitSource source) {
        return switch (source) {
            case DIAMOND_MINING -> "diamond";
            case GOLD_MINING -> "gold";
            case GEMSTONE_MINING -> "gemstone";
            case MYCELIUM_MINING -> "mycelium";
            case RED_SAND_MINING -> "red_sand";
            case ENDSTONE_MINING -> "endstone";
            case FIG_TREE -> "fig";
            case MANGROVE_TREE -> "mangrove";
            case HELIX_TREE -> "helix";
        };
    }
}
