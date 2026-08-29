package ironmanprofittracker.daeelldian.client;

import ironmanprofittracker.daeelldian.ProfitTrackerMath;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Defines one MMM, its NPC-sellable reward forms, and optional normalized material display. */
public enum ProfitSource {
    DIAMOND_MINING("diamond_mining", "Diamond Mining", Items.DIAMOND, 0xFF55D9FF, true,
            tiers(
                    tier("Enchanted Diamond Block", Items.DIAMOND_BLOCK, true, 25_600L),
                    tier("Enchanted Diamond", Items.DIAMOND, true, 160L),
                    tier("Diamond", Items.DIAMOND, false, 1L)
            ),
            reward("diamond", 8L, "diamond", 1L),
            reward("enchanted diamond", 1_280L, "diamond", 160L),
            reward("enchanted diamond block", 204_800L, "diamond", 25_600L)),

    GOLD_MINING("gold_mining", "Gold Mining", Items.GOLD_INGOT, 0xFFFFC83D, true,
            tiers(
                    tier("Enchanted Gold Block", Items.GOLD_BLOCK, true, 25_600L),
                    tier("Enchanted Gold Ingot", Items.GOLD_INGOT, true, 160L),
                    tier("Gold Ingot", Items.GOLD_INGOT, false, 1L)
            ),
            reward("gold ingot", 3L, "gold", 1L),
            reward("enchanted gold ingot", 480L, "gold", 160L),
            reward("enchanted gold block", 76_800L, "gold", 25_600L)),

    GEMSTONE_MINING("gemstone_mining", "Gemstone Mining", Items.DIAMOND, 0xFFFF55FF, true,
            tiers(
                    tier("Fine Gemstone", Items.DIAMOND, true, 6_400L),
                    tier("Flawed Gemstone", Items.AMETHYST_SHARD, true, 80L),
                    tier("Rough Gemstone", Items.AMETHYST_SHARD, false, 1L)
            ),
            gemstoneRewards()),

    MYCELIUM_MINING("mycelium_mining", "Mycelium Mining", Items.MYCELIUM, 0xFFB784E6, true,
            tiers(
                    tier("Enchanted Mycelium Cube", Items.MYCELIUM, true, 10_240L),
                    tier("Enchanted Mycelium", Items.MYCELIUM, true, 160L),
                    tier("Mycelium", Items.MYCELIUM, false, 1L)
            ),
            reward("mycelium", 5L, "mycelium", 1L),
            reward("enchanted mycelium", 800L, "mycelium", 160L),
            reward("enchanted mycelium cube", 51_200L, "mycelium", 10_240L)),

    RED_SAND_MINING("red_sand_mining", "Red Sand Mining", Items.RED_SAND, 0xFFE66A3C, true,
            tiers(
                    tier("Enchanted Red Sand Cube", Items.RED_SANDSTONE, true, 10_240L),
                    tier("Enchanted Red Sand", Items.RED_SAND, true, 160L),
                    tier("Red Sand", Items.RED_SAND, false, 1L)
            ),
            reward("red sand", 5L, "red_sand", 1L),
            reward("enchanted red sand", 800L, "red_sand", 160L),
            reward("enchanted red sand cube", 51_200L, "red_sand", 10_240L)),

    ENDSTONE_MINING("endstone_mining", "Endstone Mining", Items.END_STONE, 0xFFE8DCA0, true,
            tiers(
                    tier("Enchanted End Stone", Items.END_STONE, true, 160L),
                    tier("End Stone", Items.END_STONE, false, 1L)
            ),
            reward("end stone", 2L, "end_stone", 1L),
            reward("enchanted end stone", 320L, "end_stone", 160L)),

    FIG_TREE("fig_tree", "Fig Tree", Items.OAK_LOG, 0xFFB85C8A, false, tiers(),
            reward("fig log", 7L), reward("enchanted fig log", 1_120L)),
    MANGROVE_TREE("mangrove_tree", "Mangrove Tree", Items.OAK_LOG, 0xFF6FAF64, false, tiers(),
            reward("mangrove log", 8L), reward("enchanted mangrove log", 1_280L), reward("mangcore", 204_800L)),
    HELIX_TREE("helix_tree", "Helix Tree", Items.OAK_LOG, 0xFF4FD7C8, false, tiers(),
            reward("helix log", 7L), reward("enchanted helix log", 1_120L));

    private final String id;
    private final String displayName;
    private final ItemStack iconStack;
    private final int hudAccentColor;
    private final boolean mining;
    private final List<MaterialTier> materialTiers;
    private final List<Reward> rewards;

    ProfitSource(
            String id,
            String displayName,
            Item icon,
            int hudAccentColor,
            boolean mining,
            MaterialTier[] materialTiers,
            Reward... rewards
    ) {
        this.id = id;
        this.displayName = displayName;
        this.iconStack = new ItemStack(icon);
        this.hudAccentColor = hudAccentColor;
        this.mining = mining;
        this.materialTiers = List.copyOf(List.of(materialTiers));
        this.rewards = new ArrayList<>(List.of(rewards));
        this.rewards.sort(Comparator.comparingInt((Reward reward) -> reward.name.length()).reversed());
    }

    private static Reward[] gemstoneRewards() {
        List<Reward> out = new ArrayList<>();
        String[] cheap = {"ruby", "jade", "sapphire", "amethyst", "amber", "topaz"};
        String[] expensive = {"jasper", "opal", "onyx", "citrine", "aquamarine", "peridot"};
        for (String color : cheap) {
            out.add(reward("fine " + color + " gemstone", 19_200L, color, 6_400L));
            out.add(reward("flawed " + color + " gemstone", 240L, color, 80L));
            out.add(reward("rough " + color + " gemstone", 3L, color, 1L));
        }
        for (String color : expensive) {
            out.add(reward("fine " + color + " gemstone", 25_600L, color, 6_400L));
            out.add(reward("flawed " + color + " gemstone", 320L, color, 80L));
            out.add(reward("rough " + color + " gemstone", 4L, color, 1L));
        }
        return out.toArray(Reward[]::new);
    }

    private static Reward reward(String name, long npcSellPrice) {
        return new Reward(name, npcSellPrice, "", 0L);
    }

    private static Reward reward(String name, long npcSellPrice, String materialFamily, long baseUnitsPerItem) {
        return new Reward(name, npcSellPrice, materialFamily, baseUnitsPerItem);
    }

    private static MaterialTier[] tiers(MaterialTier... tiers) {
        return tiers;
    }

    private static MaterialTier tier(String displayName, Item item, boolean glint, long baseUnitsPerItem) {
        ItemStack stack = new ItemStack(item);
        if (glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return new MaterialTier(displayName, stack, baseUnitsPerItem);
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public ItemStack getIconStack() { return iconStack; }
    public int getHudAccentColor() { return hudAccentColor; }
    public boolean isMining() { return mining; }
    public boolean hasMaterialBreakdown() { return mining && !materialTiers.isEmpty(); }

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
        return amount <= 0 ? ParsedReward.NONE : new ParsedReward(
                amount,
                reward.npcSellPrice,
                reward.name,
                reward.materialFamily,
                reward.baseUnitsPerItem
        );
    }

    private static long parseNumber(String value) {
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /** Adds material-equivalent units from an already-correlated reward into the session accumulator. */
    public void accumulateMaterials(Map<String, Long> target, ParsedSacksEvent.SourceReward sourceReward) {
        if (!hasMaterialBreakdown() || target == null || sourceReward == null) return;
        for (ParsedSacksEvent.RewardLine line : sourceReward.lines()) {
            if (line.baseUnitsPerItem() <= 0L || line.materialFamily() == null || line.materialFamily().isBlank()) continue;
            long units = ProfitTrackerMath.saturatingMultiply(line.amount(), line.baseUnitsPerItem());
            target.merge(line.materialFamily(), units, ProfitTrackerMath::saturatingAdd);
        }
    }

    /**
     * Converts tracked base units into the most compact equivalent display. Gemstone colors are
     * normalized independently and only then aggregated, so different colors are never illegally
     * compacted into one another.
     */
    public List<MaterialDisplayEntry> previewMaterials() {
        if (!hasMaterialBreakdown()) return List.of();
        long[] examples = {12L, 80L, 47L};
        List<MaterialDisplayEntry> out = new ArrayList<>(materialTiers.size());
        for (int i = 0; i < materialTiers.size(); i++) {
            MaterialTier tier = materialTiers.get(i);
            out.add(new MaterialDisplayEntry(tier.displayName(), tier.iconStack(), examples[Math.min(i, examples.length - 1)]));
        }
        return List.copyOf(out);
    }

    public List<MaterialDisplayEntry> normalizeMaterials(Map<String, Long> familyBaseUnits) {
        if (!hasMaterialBreakdown()) return List.of();

        long[] tierCounts = new long[materialTiers.size()];
        if (familyBaseUnits != null) {
            long[] tierSizes = new long[materialTiers.size()];
            for (int i = 0; i < materialTiers.size(); i++) {
                tierSizes[i] = materialTiers.get(i).baseUnitsPerItem();
            }
            for (long familyUnits : familyBaseUnits.values()) {
                long[] familyCounts = ProfitTrackerMath.compactTierCounts(familyUnits, tierSizes);
                for (int i = 0; i < tierCounts.length; i++) {
                    tierCounts[i] = ProfitTrackerMath.saturatingAdd(tierCounts[i], familyCounts[i]);
                }
            }
        }

        List<MaterialDisplayEntry> out = new ArrayList<>(materialTiers.size());
        for (int i = 0; i < materialTiers.size(); i++) {
            MaterialTier tier = materialTiers.get(i);
            out.add(new MaterialDisplayEntry(tier.displayName(), tier.iconStack(), tierCounts[i]));
        }
        return List.copyOf(out);
    }

    public record ParsedReward(
            long amount,
            long npcSellPrice,
            String itemName,
            String materialFamily,
            long baseUnitsPerItem
    ) {
        public static final ParsedReward NONE = new ParsedReward(0L, 0L, "", "", 0L);
        public long profit() { return ProfitTrackerMath.saturatingMultiply(amount, npcSellPrice); }
    }

    public record MaterialDisplayEntry(String displayName, ItemStack iconStack, long count) {}

    private record MaterialTier(String displayName, ItemStack iconStack, long baseUnitsPerItem) {}

    private static final class Reward {
        private final String name;
        private final long npcSellPrice;
        private final String materialFamily;
        private final long baseUnitsPerItem;
        private final Pattern namePattern;
        private final Pattern amountAfterPattern;
        private final Pattern amountBeforePattern;

        private Reward(String name, long npcSellPrice, String materialFamily, long baseUnitsPerItem) {
            this.name = name.toLowerCase(Locale.ROOT);
            this.npcSellPrice = npcSellPrice;
            this.materialFamily = materialFamily == null ? "" : materialFamily;
            this.baseUnitsPerItem = Math.max(0L, baseUnitsPerItem);
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
