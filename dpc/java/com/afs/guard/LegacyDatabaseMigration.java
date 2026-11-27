package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Log;

import com.afs.guard.data.AfsRepositories;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One-way, verified mirror of the SharedPreferences data into the phase-three database. */
public final class LegacyDatabaseMigration {
    private static final String TAG = "AFSDataMigration";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String STATUS = "afs_data_status";
    private static final String SNAPSHOT_PREFIX = "legacy-sharedpreferences-v2-";
    private static final int SNAPSHOTS_TO_KEEP = 5;

    private LegacyDatabaseMigration() {
    }

    public static void run(Context context) {
        run(context, AfsRepositories.get(context),
                context.getSharedPreferences("afs_guard", Context.MODE_PRIVATE),
                context.getSharedPreferences("afs_time_window", Context.MODE_PRIVATE),
                context.getSharedPreferences("afs_day_boundary", Context.MODE_PRIVATE),
                context.getSharedPreferences("afs_screen_on_limit", Context.MODE_PRIVATE),
                context.getSharedPreferences("afs_ui", Context.MODE_PRIVATE),
                context.getSharedPreferences("afs_lang", Context.MODE_PRIVATE));
    }

    static void run(Context context, AfsRepositories repositories,
                    SharedPreferences guard, SharedPreferences timeWindow,
                    SharedPreferences dayBoundary, SharedPreferences screenLimit,
                    SharedPreferences ui, SharedPreferences language) {
        SharedPreferences status = context.getSharedPreferences(STATUS, Context.MODE_PRIVATE);
        String previous = repositories.migrationResult();
        if ("SUCCESS".equals(previous) || "SKIPPED".equals(previous)) {
            status.edit()
                    .putString("phase3_migration_result", previous)
                    .putBoolean("phase3_migration_complete", true)
                    .commit();
            return;
        }

        String checksum = "";
        String snapshotPath = "";
        try {
            String rulesRaw = string(guard, "rules", "");
            String slotsRaw = string(guard, "freeze_slots", "[]");
            Map<String, String> configuration = configuration(guard, timeWindow, dayBoundary,
                    screenLimit, ui, language);
            JSONObject snapshot = snapshot(guard, timeWindow, dayBoundary, screenLimit, ui,
                    language, rulesRaw, slotsRaw, configuration);
            String snapshotText = snapshot.toString();
            checksum = digest(snapshotText);
            File file = writeSnapshot(context, snapshotText, checksum);
            snapshotPath = file.getAbsolutePath();

            LegacySnapshotParser.RulesSnapshot parsedRules = rules(guard, rulesRaw);
            LegacySnapshotParser.SlotsSnapshot parsedSlots =
                    LegacySnapshotParser.parseSlots(slotsRaw);
            String result = parsedRules.rules.isEmpty() && parsedSlots.slots.isEmpty()
                    ? "SKIPPED" : "SUCCESS";
            repositories.importLegacySnapshot(parsedRules.rules, parsedSlots.slots,
                    parsedRules.addedAt, configuration, checksum, result);

            status.edit()
                    .putBoolean("phase3_migration_complete", true)
                    .putString("phase3_migration_result", result)
                    .putLong("phase3_migration_at", System.currentTimeMillis())
                    .putString("phase3_migration_checksum", checksum)
                    .putString("phase3_migration_snapshot", snapshotPath)
                    .remove("phase3_migration_error")
                    .commit();
            Log.i(TAG, "Legacy data migration " + result + " (" + checksum + ")");
        } catch (Throwable error) {
            String message = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "" : error.getMessage());
            try {
                repositories.recordMigrationFailure(checksum);
            } catch (Throwable databaseError) {
                message += "; failure record unavailable: "
                        + databaseError.getClass().getSimpleName();
            }
            status.edit()
                    .putBoolean("phase3_migration_complete", false)
                    .putString("phase3_migration_result", "FAILED")
                    .putLong("phase3_migration_at", System.currentTimeMillis())
                    .putString("phase3_migration_checksum", checksum)
                    .putString("phase3_migration_snapshot", snapshotPath)
                    .putString("phase3_migration_error", truncate(message, 500))
                    .commit();
            Log.e(TAG, "Legacy data migration failed; SharedPreferences remain active", error);
        }
    }

    private static LegacySnapshotParser.RulesSnapshot rules(SharedPreferences guard,
                                                             String rawRules) {
        boolean global = !guard.contains("rules")
                && (guard.contains("days") || guard.contains("start_min")
                || guard.contains("end_min") || guard.contains("targets"));
        if (!global) return LegacySnapshotParser.parseRules(rawRules);

        int days = guard.getInt("days", Rule.ALL_DAYS);
        int start = guard.getInt("start_min", RuleStore.FALLBACK_START);
        int end = guard.getInt("end_min", RuleStore.FALLBACK_END);
        Set<String> targets = guard.getStringSet("targets", null);
        if (targets == null || targets.isEmpty()) {
            targets = new LinkedHashSet<>(Arrays.asList(Guard.DEFAULT_TARGETS));
        }
        return LegacySnapshotParser.legacyGlobal(days, start, end, targets);
    }

    private static Map<String, String> configuration(SharedPreferences guard,
                                                     SharedPreferences timeWindow,
                                                     SharedPreferences dayBoundary,
                                                     SharedPreferences screenLimit,
                                                     SharedPreferences ui,
                                                     SharedPreferences language) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("time_window.start", String.valueOf(boundedInt(timeWindow, "start",
                TimeWindow.DEFAULT_START, 0, 1439)));
        values.put("time_window.end", String.valueOf(boundedInt(timeWindow, "end",
                TimeWindow.DEFAULT_END, 0, 1439)));

        long now = System.currentTimeMillis();
        int hour = boundedInt(dayBoundary, "reset_hour", DayBoundary.DEFAULT_HOUR, 0, 23);
        int minute = boundedInt(dayBoundary, "reset_minute", 0, 0, 59);
        int pendingHour = dayBoundary.getInt("pending_hour", -1);
        int pendingMinute = boundedInt(dayBoundary, "pending_minute", 0, 0, 59);
        if (pendingHour >= 0 && pendingHour <= 23
                && now >= dayBoundary.getLong("pending_at", Long.MAX_VALUE)) {
            hour = pendingHour;
            minute = pendingMinute;
        }
        values.put("day_boundary.reset_hour", String.valueOf(hour));
        values.put("day_boundary.reset_minute", String.valueOf(minute));

        values.put("screen_on_limit.enabled",
                String.valueOf(screenLimit.getBoolean("enabled", false)));
        int minutes = screenLimit.getInt("minutes", 180);
        if (minutes < 5 || minutes > 24 * 60) minutes = 180;
        values.put("screen_on_limit.minutes", String.valueOf(minutes));

        String timezone = string(guard, "schedule_timezone", "");
        if (!timezone.isEmpty()) values.put("schedule.timezone", timezone);

        copyString(ui, "style_id", "ui.style_id", values);
        copyString(ui, "effect_quality", "ui.effect_quality", values);
        copyString(ui, "theme", "ui.theme", values);
        if (ui.contains("reduce_motion")) {
            values.put("ui.reduce_motion", String.valueOf(ui.getBoolean("reduce_motion", false)));
        }
        copyString(language, "mode", "language.mode", values);
        return values;
    }

    private static JSONObject snapshot(SharedPreferences guard,
                                       SharedPreferences timeWindow,
                                       SharedPreferences dayBoundary,
                                       SharedPreferences screenLimit,
                                       SharedPreferences ui,
                                       SharedPreferences language,
                                       String rulesRaw,
                                       String slotsRaw,
                                       Map<String, String> configuration) throws Exception {
        JSONObject root = new JSONObject();
        root.put("schema", 1);
        root.put("created_at", System.currentTimeMillis());
        root.put("rules_raw", rulesRaw);
        root.put("freeze_slots_raw", slotsRaw);
        root.put("rules_key_absent", !guard.contains("rules"));
        root.put("legacy_globals", rawPreferences(guard,
                "days", "start_min", "end_min", "targets", "schedule_timezone"));
        root.put("preferences", new JSONObject()
                .put("afs_time_window", rawPreferences(timeWindow, "start", "end"))
                .put("afs_day_boundary", rawPreferences(dayBoundary,
                        "reset_hour", "reset_minute", "pending_hour", "pending_minute",
                        "pending_at"))
                .put("afs_screen_on_limit", rawPreferences(screenLimit, "enabled", "minutes"))
                .put("afs_ui", rawPreferences(ui, "style_id", "effect_quality", "theme",
                        "reduce_motion"))
                .put("afs_lang", rawPreferences(language, "mode")));
        JSONObject config = new JSONObject();
        for (Map.Entry<String, String> entry : configuration.entrySet()) {
            config.put(entry.getKey(), entry.getValue());
        }
        root.put("configuration", config);
        return root;
    }

    private static JSONObject rawPreferences(SharedPreferences preferences, String... keys) {
        JSONObject out = new JSONObject();
        for (String key : keys) {
            try {
                if (!preferences.contains(key)) continue;
                Object value = preferences.getAll().get(key);
                if (value instanceof Set) {
                    JSONArray array = new JSONArray();
                    List<String> sorted = new ArrayList<>();
                    for (Object item : (Set<?>) value) sorted.add(String.valueOf(item));
                    sorted.sort(Comparator.naturalOrder());
                    for (String item : sorted) array.put(item);
                    out.put(key, array);
                } else {
                    out.put(key, value);
                }
            } catch (Exception error) {
                throw new IllegalStateException("Cannot snapshot preference " + key, error);
            }
        }
        return out;
    }

    private static File writeSnapshot(Context context, String text, String checksum)
            throws Exception {
        File directory = new File(context.getFilesDir(), "phase3");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create phase3 snapshot directory");
        }
        File file = new File(directory, SNAPSHOT_PREFIX + System.currentTimeMillis() + ".json");
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(text.getBytes(UTF8));
            out.flush();
            out.getFD().sync();
            atomic.finishWrite(out);
        } catch (Exception error) {
            if (out != null) atomic.failWrite(out);
            throw error;
        }
        pruneSnapshots(directory);
        return file;
    }

    private static void pruneSnapshots(File directory) {
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith(SNAPSHOT_PREFIX) && name.endsWith(".json"));
        if (files == null || files.length <= SNAPSHOTS_TO_KEEP) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - SNAPSHOTS_TO_KEEP; i++) {
            if (!files[i].delete()) Log.w(TAG, "Could not prune " + files[i].getName());
        }
    }

    private static String string(SharedPreferences preferences, String key, String fallback) {
        try {
            return preferences.getString(key, fallback);
        } catch (ClassCastException error) {
            throw new IllegalStateException("Preference " + key + " is not a string", error);
        }
    }

    private static int boundedInt(SharedPreferences preferences, String key, int fallback,
                                  int minimum, int maximum) {
        int value;
        try {
            value = preferences.getInt(key, fallback);
        } catch (ClassCastException error) {
            throw new IllegalStateException("Preference " + key + " is not an integer", error);
        }
        return value < minimum || value > maximum ? fallback : value;
    }

    private static void copyString(SharedPreferences preferences, String key, String target,
                                   Map<String, String> output) {
        String value = string(preferences, key, "");
        if (!value.isEmpty()) output.put(target, value);
    }

    private static String digest(String value) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] bytes = hash.digest(value.getBytes(UTF8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b));
        return out.toString();
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
