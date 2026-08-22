package ironmanprofittracker.daeelldian.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ironmanprofittracker.daeelldian.IronmanProfitTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProfitTrackerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ProfitTrackerFiles.configFile("ironman-profit-tracker.json");

    public boolean showTracker = true;
    public boolean showProfit = true;
    public boolean showProfitPerHour = true;
    public boolean showHighestProfit = true;
    public boolean showHighestProfitPerHour = true;
    public ProfitTrackerDebug.Level debugLevel = ProfitTrackerDebug.Level.OFF;
    public double x = 0.02;
    public double y = 0.02;
    public double scale = 1.0;

    public static ProfitTrackerConfig load() {
        if (!Files.exists(FILE)) return new ProfitTrackerConfig();
        try {
            ProfitTrackerConfig config = GSON.fromJson(ProfitTrackerFiles.read(FILE), ProfitTrackerConfig.class);
            if (config == null) return new ProfitTrackerConfig();
            config.clamp();
            return config;
        } catch (Exception exception) {
            IronmanProfitTracker.LOGGER.warn("Failed to read IPT config {}; defaults will be used.", FILE, exception);
            return new ProfitTrackerConfig();
        }
    }

    public void save() {
        clamp();
        try {
            ProfitTrackerFiles.writeAtomically(FILE, GSON.toJson(this));
        } catch (IOException exception) {
            IronmanProfitTracker.LOGGER.error("Failed to save IPT config {}.", FILE, exception);
        }
    }

    public void clamp() {
        if (debugLevel == null) debugLevel = ProfitTrackerDebug.Level.OFF;
        x = finiteClamp(x, 0.0, 1.0, 0.02);
        y = finiteClamp(y, 0.0, 1.0, 0.02);
        scale = finiteClamp(scale, 0.5, 2.0, 1.0);
    }

    public void resetPosition() {
        x = 0.02;
        y = 0.02;
    }

    private static double finiteClamp(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) return fallback;
        return Math.max(min, Math.min(max, value));
    }
}
