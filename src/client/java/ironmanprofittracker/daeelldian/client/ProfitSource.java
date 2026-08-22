package ironmanprofittracker.daeelldian.client;

import ironmanprofittracker.daeelldian.ProfitTrackerMath;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Defines one MMM and every NPC-sellable reward form that belongs to it. */
public enum ProfitSource {
    DIAMOND_MINING("diamond_mining", "Diamond Mining", Items.DIAMOND, 0xFF55D9FF,
            reward("diamond", 8L), reward("enchanted diamond", 1_280L), reward("enchanted diamond block", 204_800L)),
    GOLD_MINING("gold_mining", "Gold Mining", Items.GOLD_INGOT, 0xFFFFC83D,
            reward("gold ingot", 3L), reward("enchanted gold ingot", 480L), reward("enchanted gold block", 76_800L)),
    GEMSTONE_MINING("gemstone_mining", "Gemstone Mining", Items.DIAMOND, 0xFFFF55FF, gemstoneRewards()),
    MYCELIUM_MINING("mycelium_mining", "Mycelium Mining", Items.MYCELIUM, 0xFFB784E6,
            reward("mycelium", 5L), reward("enchanted mycelium", 800L), reward("enchanted mycelium cube", 51_200L)),
    RED_SAND_MINING("red_sand_mining", "Red Sand Mining", Items.RED_SAND, 0xFFE66A3C,
            reward("red sand", 5L), reward("enchanted red sand", 800L), reward("enchanted red sand cube", 51_200L)),
    ENDSTONE_MINING("endstone_mining", "Endstone Mining", Items.END_STONE, 0xFFE8DCA0,
            reward("end stone", 2L), reward("enchanted end stone", 320L)),
    FIG_TREE("fig_tree", "Fig Tree", Items.OAK_LOG, 0xFFB85C8A,
            reward("fig log", 7L), reward("enchanted fig log", 1_120L)),
    MANGROVE_TREE("mangrove_tree", "Mangrove Tree", Items.OAK_LOG, 0xFF6FAF64,
            reward("mangrove log", 8L), reward("enchanted mangrove log", 1_280L), reward("mangcore", 204_800L)),
    HELIX_TREE("helix_tree", "Helix Tree", Items.OAK_LOG, 0xFF4FD7C8,
            reward("helix log", 7L), reward("enchanted helix log", 1_120L));

    private final String id;
    private final String displayName;
    private final ItemStack iconStack;
    private final int hudAccentColor;
    private final List<Reward> rewards;

    ProfitSource(String id, String displayName, Item icon, int hudAccentColor, Reward... rewards) {
        this.id = id;
        this.displayName = displayName;
        this.iconStack = new ItemStack(icon);
        this.hudAccentColor = hudAccentColor;
        this.rewards = new ArrayList<>(List.of(rewards));
        this.rewards.sort(Comparator.comparingInt((Reward reward) -> reward.name.length()).reversed());
    }

    private static Reward[] gemstoneRewards() {
        List<Reward> out = new ArrayList<>();
        String[] cheap = {"ruby", "jade", "sapphire", "amethyst", "amber", "topaz"};
        String[] expensive = {"jasper", "opal", "onyx", "citrine", "aquamarine", "peridot"};
        for (String color : cheap) {
            out.add(reward("fine " + color + " gemstone", 19_200L));
            out.add(reward("flawed " + color + " gemstone", 240L));
            out.add(reward("rough " + color + " gemstone", 3L));
        }
        for (String color : expensive) {
            out.add(reward("fine " + color + " gemstone", 25_600L));
            out.add(reward("flawed " + color + " gemstone", 320L));
            out.add(reward("rough " + color + " gemstone", 4L));
        }
        return out.toArray(Reward[]::new);
    }

    private static Reward reward(String name, long npcSellPrice) {
        return new Reward(name, npcSellPrice);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Shared immutable-in-practice icon stack used only for GUI extraction. */
    public ItemStack getIconStack() {
        return iconStack;
    }

    /** Opaque ARGB accent used by the HUD border and tracked MMM name. */
    public int getHudAccentColor() {
        return hudAccentColor;
    }

    /** Parses every configured reward line present in one Hypixel hover payload. */
    public List<ParsedReward> parseRewards(String hoverText) {
        if (hoverText == null || hoverText.isBlank()) return List.of();
        List<ParsedReward> parsed = new ArrayList<>();
        String[] lines = hoverText.replace('\r', '\n').split("\\n+");
        for (String line : lines) {
            ParsedReward reward = parseRewardLine(line.trim());
            if (reward.amount() > 0 && reward.profit() > 0) parsed.add(reward);
        }
        return List.copyOf(parsed);
    }

    private ParsedReward parseRewardLine(String line) {
        if (line.isBlank()) return ParsedReward.NONE;
        Reward best = null;
        for (Reward reward : rewards) {
            if (reward.namePattern.matcher(line).find()) {
                best = reward;
                break;
            }
        }
        if (best == null) return ParsedReward.NONE;

        Matcher after = best.amountAfterPattern.matcher(line);
        if (after.find()) return parsed(after.group(1), best);

        Matcher before = best.amountBeforePattern.matcher(line);
        if (before.find()) return parsed(before.group(1), best);

        return ParsedReward.NONE;
    }

    private static ParsedReward parsed(String number, Reward reward) {
        long amount = parseNumber(number);
        return amount <= 0 ? ParsedReward.NONE : new ParsedReward(amount, reward.npcSellPrice, reward.name);
    }

    private static long parseNumber(String value) {
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public record ParsedReward(long amount, long npcSellPrice, String itemName) {
        public static final ParsedReward NONE = new ParsedReward(0L, 0L, "");

        public long profit() {
            return ProfitTrackerMath.saturatingMultiply(amount, npcSellPrice);
        }
    }

    private static final class Reward {
        private final String name;
        private final long npcSellPrice;
        private final Pattern namePattern;
        private final Pattern amountAfterPattern;
        private final Pattern amountBeforePattern;

        private Reward(String name, long npcSellPrice) {
            this.name = name.toLowerCase(Locale.ROOT);
            this.npcSellPrice = npcSellPrice;
            String quoted = Pattern.quote(this.name);
            this.namePattern = Pattern.compile("(?i)(?<![a-z0-9])" + quoted + "(?![a-z0-9])");
            this.amountAfterPattern = Pattern.compile(
                    "(?i)(?<![a-z0-9])" + quoted + "(?![a-z0-9])\\s*(?:x|×|:|-)?\\s*([0-9][0-9,]*)\\b"
            );
            this.amountBeforePattern = Pattern.compile(
                    "(?i)\\b([0-9][0-9,]*)\\s*(?:x|×)?\\s*" + quoted + "(?![a-z0-9])"
            );
        }
    }
}
