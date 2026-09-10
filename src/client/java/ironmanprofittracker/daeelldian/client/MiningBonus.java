package ironmanprofittracker.daeelldian.client;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mining-Fiesta bonus drops that add profit without identifying a normal MMM by themselves. */
public enum MiningBonus {
    REFINED_MINERAL("Refined Mineral", 100_000L, null, Items.PRISMARINE_CRYSTALS),
    GLOSSY_GEMSTONE("Glossy Gemstone", 200_000L, ProfitSource.GEMSTONE_MINING, Items.AMETHYST_SHARD);

    private final String displayName;
    private final String normalizedName;
    private final long npcSellPrice;
    private final ProfitSource specificSource;
    private final Item iconItem;
    private ItemStack iconStack;
    private final Pattern namePattern;
    private final Pattern amountAfterPattern;
    private final Pattern amountBeforePattern;

    MiningBonus(String displayName, long npcSellPrice, ProfitSource specificSource, Item iconItem) {
        this.displayName = displayName;
        this.normalizedName = displayName.toLowerCase(Locale.ROOT);
        this.npcSellPrice = npcSellPrice;
        this.specificSource = specificSource;
        this.iconItem = iconItem;
        String quoted = Pattern.quote(normalizedName);
        this.namePattern = Pattern.compile("(?i)(?<![a-z0-9])" + quoted + "(?![a-z0-9])");
        this.amountAfterPattern = Pattern.compile(
                "(?i)(?<![a-z0-9])" + quoted + "(?![a-z0-9])\\s*(?:x|×|:|-)?\\s*([0-9][0-9,]*)\\b"
        );
        this.amountBeforePattern = Pattern.compile(
                "(?i)\\b([0-9][0-9,]*)\\s*(?:x|×)?\\s*" + quoted + "(?![a-z0-9])"
        );
    }

    public String getDisplayName() { return displayName; }
    public long getNpcSellPrice() { return npcSellPrice; }
    public ProfitSource getSpecificSource() { return specificSource; }
    public ItemStack getIconStack() {
        if (iconStack == null) {
            iconStack = new ItemStack(iconItem);
            iconStack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return iconStack;
    }

    public boolean appliesTo(ProfitSource source) {
        return source != null && source.isMining() && (specificSource == null || specificSource == source);
    }

    public ParsedSacksEvent.RewardLine parseLine(String line) {
        if (line == null || line.isBlank() || !namePattern.matcher(line).find()) return null;

        Matcher after = amountAfterPattern.matcher(line);
        if (after.find()) return rewardLine(after.group(1));

        Matcher before = amountBeforePattern.matcher(line);
        if (before.find()) return rewardLine(before.group(1));
        return null;
    }

    private ParsedSacksEvent.RewardLine rewardLine(String number) {
        long amount = parseNumber(number);
        if (amount <= 0L) return null;
        return new ParsedSacksEvent.RewardLine(displayName, amount, npcSellPrice, "", 0L);
    }

    public static List<ParsedSacksEvent.RewardLine> parseRewards(String hoverText) {
        if (hoverText == null || hoverText.isBlank()) return List.of();
        List<ParsedSacksEvent.RewardLine> out = new ArrayList<>();
        String[] lines = hoverText.replace('\r', '\n').split("\\n+");
        for (String line : lines) {
            for (MiningBonus bonus : values()) {
                ParsedSacksEvent.RewardLine parsed = bonus.parseLine(line.trim());
                if (parsed != null && parsed.profit() > 0L) out.add(parsed);
            }
        }
        return List.copyOf(out);
    }

    public static MiningBonus fromItemName(String itemName) {
        if (itemName == null) return null;
        String normalized = itemName.trim().toLowerCase(Locale.ROOT);
        for (MiningBonus bonus : values()) {
            if (bonus.normalizedName.equals(normalized)) return bonus;
        }
        return null;
    }

    private static long parseNumber(String value) {
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
