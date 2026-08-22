package ironmanprofittracker.daeelldian;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared mod identity/constants. All runtime behavior is client-only. */
public final class IronmanProfitTracker {
    public static final String MOD_ID = "ironman-profit-tracker";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private IronmanProfitTracker() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
