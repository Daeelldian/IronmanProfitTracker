package ironmanprofittracker.daeelldian.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import ironmanprofittracker.daeelldian.IronmanProfitTracker;
import ironmanprofittracker.daeelldian.ProfitTrackerMath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Persistent lifetime records. Schema remains compatible with v11+ and includes recovered stash profit. */
public final class ProfitTrackerRecords {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ProfitTrackerFiles.configFile("ironman-profit-tracker-records.json");

    private final Map<String, Record> records;

    private ProfitTrackerRecords(Map<String, Record> records) {
        this.records = records == null ? new HashMap<>() : new HashMap<>(records);
        this.records.values().removeIf(record -> record == null);
        this.records.values().forEach(ProfitTrackerRecords::sanitize);
    }

    public static ProfitTrackerRecords load() {
        if (!Files.exists(FILE)) return new ProfitTrackerRecords(new HashMap<>());
        try {
            Map<String, Record> loaded = GSON.fromJson(
                    ProfitTrackerFiles.read(FILE),
                    new TypeToken<Map<String, Record>>() {}.getType()
            );
            return new ProfitTrackerRecords(loaded);
        } catch (Exception exception) {
            IronmanProfitTracker.LOGGER.warn("Failed to read IPT records {}; starting with empty records.", FILE, exception);
            return new ProfitTrackerRecords(new HashMap<>());
        }
    }

    public long getTotalProfit(ProfitSource source) {
        return get(source).totalProfit;
    }

    public long getHighestProfit(ProfitSource source) {
        return get(source).highestProfit;
    }

    public double getHighestProfitPerHour(ProfitSource source) {
        return get(source).highestProfitPerHour;
    }

    public long getRecoveredProfit(ProfitSource source) {
        return get(source).recoveredProfit;
    }

    public void updateSession(ProfitSource source, long profit, double perHour) {
        if (source == null) return;
        Record record = get(source);
        long safeProfit = Math.max(0L, profit);
        double safeRate = Double.isFinite(perHour) && perHour > 0 ? perHour : 0.0;
        record.totalProfit = ProfitTrackerMath.saturatingAdd(record.totalProfit, safeProfit);
        record.highestProfit = Math.max(record.highestProfit, safeProfit);
        record.highestProfitPerHour = Math.max(record.highestProfitPerHour, safeRate);
        save();
    }

    public void clear(ProfitSource source) {
        if (source == null) return;
        records.remove(source.getId());
        save();
    }

    public void addRecoveredProfits(Map<ProfitSource, Long> recoveredBySource) {
        if (recoveredBySource == null || recoveredBySource.isEmpty()) return;
        boolean changed = false;
        for (Map.Entry<ProfitSource, Long> entry : recoveredBySource.entrySet()) {
            ProfitSource source = entry.getKey();
            long safeProfit = entry.getValue() == null ? 0L : Math.max(0L, entry.getValue());
            if (source == null || safeProfit == 0L) continue;
            Record record = get(source);
            record.totalProfit = ProfitTrackerMath.saturatingAdd(record.totalProfit, safeProfit);
            record.recoveredProfit = ProfitTrackerMath.saturatingAdd(record.recoveredProfit, safeProfit);
            changed = true;
        }
        if (changed) save();
    }

    private Record get(ProfitSource source) {
        return records.computeIfAbsent(source.getId(), ignored -> new Record());
    }

    private static void sanitize(Record record) {
        record.totalProfit = Math.max(0L, record.totalProfit);
        record.highestProfit = Math.max(0L, record.highestProfit);
        if (!Double.isFinite(record.highestProfitPerHour) || record.highestProfitPerHour < 0.0) {
            record.highestProfitPerHour = 0.0;
        }
        record.recoveredProfit = Math.max(0L, record.recoveredProfit);
    }

    private void save() {
        try {
            ProfitTrackerFiles.writeAtomically(FILE, GSON.toJson(records));
        } catch (IOException exception) {
            IronmanProfitTracker.LOGGER.error("Failed to save IPT records {}.", FILE, exception);
        }
    }

    private static final class Record {
        long totalProfit;
        long highestProfit;
        double highestProfitPerHour;
        long recoveredProfit;
    }
}
