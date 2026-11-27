package com.afs.guard.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;

import com.afs.guard.FreezeSlotStore;
import com.afs.guard.Rule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Repository facade for the phase-three database.
 *
 * Public repository methods are blocking.  Callers on the UI thread must use
 * {@link AfsDatabase#runAsync(Runnable)} or another explicit worker.
 */
public final class AfsRepositories {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String LEGACY_MIGRATION = "legacy-sharedpreferences-v2";
    private static final int LEGACY_MIGRATION_VERSION = 101;

    private static volatile AfsRepositories instance;

    private final AfsDatabase database;
    private final Apps apps;
    private final Rules rules;
    private final Config config;
    private final Events events;
    private final Focus focus;
    private final Stats stats;
    private final Outbox outbox;

    public static AfsRepositories get(Context context) {
        if (instance == null) {
            synchronized (AfsRepositories.class) {
                if (instance == null) {
                    instance = new AfsRepositories(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** Isolated repository graph for tests; production code uses {@link #get(Context)}. */
    public static AfsRepositories isolated(Context context) {
        return new AfsRepositories(context.getApplicationContext(), AfsDatabase.isolated(context));
    }

    /** Release the singleton before a local restore replaces the database file. */
    public static synchronized void closeForRestore() {
        if (instance != null) {
            instance.database.close();
            instance = null;
        }
        AfsDatabase.closeSingleton();
    }

    private AfsRepositories(Context context) {
        this(context, AfsDatabase.get(context));
    }

    private AfsRepositories(Context context, AfsDatabase database) {
        this.database = database;
        apps = new Apps(database);
        rules = new Rules(database, apps);
        config = new Config(database);
        events = new Events(database);
        focus = new Focus(database);
        stats = new Stats(database);
        outbox = new Outbox(database);
        ensureDevice(context);
    }

    public ApplicationRepository applications() { return apps; }
    public RuleRepository ruleRepository() { return rules; }
    public ConfigurationRepository configuration() { return config; }
    public EventRepository eventRepository() { return events; }
    public FocusRepository focusRepository() { return focus; }
    public StatisticsRepository statistics() { return stats; }
    public SyncOutbox outbox() { return outbox; }

    public boolean isLegacyMigrationComplete() {
        String result = migrationResult();
        return "SUCCESS".equals(result) || "SKIPPED".equals(result);
    }

    public String migrationResult() {
        Cursor cursor = database.getReadableDatabase().query("schema_migrations",
                new String[]{"result"}, "migration_name=?", new String[]{LEGACY_MIGRATION},
                null, null, null);
        try {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        } finally {
            cursor.close();
        }
    }

    public void recordMigrationFailure(String checksum) {
        recordMigration(LEGACY_MIGRATION_VERSION, LEGACY_MIGRATION, "FAILED", checksum);
    }

    /**
     * Imports a verified SharedPreferences snapshot in one transaction.
     *
     * The old preferences are only read by the caller.  Nothing here touches the
     * Android enforcement APIs, so a failed import cannot loosen a freeze rule.
     */
    public void importLegacySnapshot(Map<String, Rule> ruleMap,
                                     Map<String, Rule> slotMap,
                                     Map<String, Long> addedAtMap,
                                     Map<String, String> configuration,
                                     String checksum,
                                     String result) {
        if (!"SUCCESS".equals(result) && !"SKIPPED".equals(result)) {
            throw new IllegalArgumentException("Legacy import result must be SUCCESS or SKIPPED");
        }
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Rule> entry : ruleMap.entrySet()) {
                String pkg = entry.getKey();
                long addedAt = addedAtMap.containsKey(pkg) ? addedAtMap.get(pkg) : 0L;
                rules.saveInternal(db, pkg, entry.getValue(), "migration",
                        "SharedPreferences rules snapshot", addedAt, now);
            }
            Set<String> importedPackages = new LinkedHashSet<>(ruleMap.keySet());
            for (String pkg : rules.enabledPackages(db)) {
                if (!importedPackages.contains(pkg)) {
                    rules.removeInternal(db, pkg, "migration",
                            "Not present in SharedPreferences snapshot", now);
                }
            }

            db.delete("freeze_slot_members", null, null);
            db.delete("freeze_slots", null, null);
            for (Map.Entry<String, Rule> entry : slotMap.entrySet()) {
                String slotId = entry.getKey();
                Rule slotRule = entry.getValue();
                String key = FreezeSlotStore.scheduleKey(slotRule);
                ContentValues slot = new ContentValues();
                slot.put("id", slotId);
                slot.put("label", "");
                slot.put("days", slotRule.days);
                slot.put("freeze_mode", slotRule.freeze ? 1 : 0);
                slot.put("schedule_key", key);
                slot.put("created_at", now);
                slot.put("updated_at", now);
                db.insertWithOnConflict("freeze_slots", null, slot,
                        SQLiteDatabase.CONFLICT_REPLACE);

                for (Map.Entry<String, Rule> appRule : ruleMap.entrySet()) {
                    if (FreezeSlotStore.unassigned(appRule.getValue())) continue;
                    if (key.equals(FreezeSlotStore.scheduleKey(appRule.getValue()))) {
                        String appId = apps.findId(db, appRule.getKey());
                        if (appId != null) {
                            ContentValues member = new ContentValues();
                            member.put("slot_id", slotId);
                            member.put("application_id", appId);
                            db.insertWithOnConflict("freeze_slot_members", null, member,
                                    SQLiteDatabase.CONFLICT_IGNORE);
                        }
                    }
                }
            }

            for (Map.Entry<String, String> entry : configuration.entrySet()) {
                config.putInternal(db, entry.getKey(), entry.getValue(), "string", now);
            }

            verifyLegacyImport(db, ruleMap, slotMap, addedAtMap, configuration);
            recordMigrationInternal(db, LEGACY_MIGRATION_VERSION, LEGACY_MIGRATION,
                    result, checksum, now);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void verifyLegacyImport(SQLiteDatabase db, Map<String, Rule> expected,
                                    Map<String, Rule> expectedSlots,
                                    Map<String, Long> expectedAddedAt,
                                    Map<String, String> expectedConfiguration) {
        Map<String, Rule> actual = rules.readAllInternal(db);
        if (!actual.keySet().equals(expected.keySet())) {
            throw new IllegalStateException("Migration verification failed: package set changed");
        }
        for (Map.Entry<String, Rule> entry : expected.entrySet()) {
            Rule written = actual.get(entry.getKey());
            if (written == null || !written.same(entry.getValue())) {
                throw new IllegalStateException("Migration verification failed: " + entry.getKey());
            }
            long expectedAt = expectedAddedAt.containsKey(entry.getKey())
                    ? expectedAddedAt.get(entry.getKey()) : 0L;
            DataRecords.RuleRecord record = rules.findInternal(db, entry.getKey());
            if (record == null || record.addedAt != expectedAt) {
                throw new IllegalStateException(
                        "Migration verification failed: added_at changed for " + entry.getKey());
            }
        }

        Cursor slots = db.query("freeze_slots",
                new String[]{"id", "schedule_key"}, null, null, null, null, "id ASC");
        Set<String> actualSlotIds = new LinkedHashSet<>();
        try {
            while (slots.moveToNext()) {
                String id = slots.getString(0);
                actualSlotIds.add(id);
                Rule expectedRule = expectedSlots.get(id);
                if (expectedRule == null
                        || !slots.getString(1).equals(FreezeSlotStore.scheduleKey(expectedRule))) {
                    throw new IllegalStateException(
                            "Migration verification failed: freeze slot changed: " + id);
                }
            }
        } finally {
            slots.close();
        }
        if (!actualSlotIds.equals(expectedSlots.keySet())) {
            throw new IllegalStateException("Migration verification failed: freeze slot set changed");
        }

        for (Map.Entry<String, Rule> slot : expectedSlots.entrySet()) {
            Cursor members = db.rawQuery(
                    "SELECT a.package_name FROM freeze_slot_members m " +
                            "JOIN applications a ON a.id=m.application_id " +
                            "WHERE m.slot_id=? ORDER BY a.package_name",
                    new String[]{slot.getKey()});
            Set<String> actualMembers = new LinkedHashSet<>();
            try {
                while (members.moveToNext()) actualMembers.add(members.getString(0));
            } finally {
                members.close();
            }
            Set<String> expectedMembers = new LinkedHashSet<>();
            String key = FreezeSlotStore.scheduleKey(slot.getValue());
            for (Map.Entry<String, Rule> rule : expected.entrySet()) {
                if (FreezeSlotStore.unassigned(rule.getValue())) continue;
                if (key.equals(FreezeSlotStore.scheduleKey(rule.getValue()))) {
                    expectedMembers.add(rule.getKey());
                }
            }
            if (!actualMembers.equals(expectedMembers)) {
                throw new IllegalStateException(
                        "Migration verification failed: slot membership changed: " + slot.getKey());
            }
        }

        for (Map.Entry<String, String> entry : expectedConfiguration.entrySet()) {
            if (!entry.getValue().equals(config.get(entry.getKey(), null))) {
                throw new IllegalStateException(
                        "Migration verification failed: configuration changed: " + entry.getKey());
            }
        }
    }

    private void recordMigrationFailureInternal(SQLiteDatabase db, String checksum, long now) {
        recordMigrationInternal(db, LEGACY_MIGRATION_VERSION, LEGACY_MIGRATION,
                "FAILED", checksum, now);
    }

    private void recordMigration(int version, String name, String result, String checksum) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            recordMigrationInternal(db, version, name, result, checksum, System.currentTimeMillis());
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void recordMigrationInternal(SQLiteDatabase db, int version, String name,
                                         String result, String checksum, long now) {
        ContentValues values = new ContentValues();
        values.put("version", version);
        values.put("migration_name", name);
        values.put("applied_at", now);
        values.put("result", result);
        values.put("checksum", checksum == null ? "" : checksum);
        db.insertWithOnConflict("schema_migrations", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void ensureDevice(Context context) {
        SQLiteDatabase db = database.getWritableDatabase();
        Cursor cursor = db.query("devices", new String[]{"id"}, null, null, null, null, null);
        boolean exists;
        try {
            exists = cursor.moveToFirst();
        } finally {
            cursor.close();
        }
        if (exists) {
            return;
        }
        long now = System.currentTimeMillis();
        String appVersion;
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            appVersion = info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException error) {
            appVersion = "";
        }
        ContentValues values = new ContentValues();
        values.put("device_uuid", UUID.randomUUID().toString());
        values.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
        values.put("android_version", Build.VERSION.RELEASE);
        values.put("app_version", appVersion);
        values.put("enforcement_mode", "device_owner");
        values.put("created_at", now);
        values.put("updated_at", now);
        db.insertWithOnConflict("devices", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String digest(String value) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] bytes = hash.digest((value == null ? "" : value).getBytes(UTF8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String ruleJson(Rule rule) {
        try {
            JSONObject out = new JSONObject();
            out.put("days", rule.days);
            out.put("freeze", rule.freeze);
            out.put("gate", rule.gate);
            JSONArray windows = new JSONArray();
            for (int i = 0; i < rule.windowCount(); i++) {
                windows.put(new JSONArray().put(rule.startAt(i)).put(rule.endAt(i)));
            }
            out.put("windows", windows);
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Rule serialization failed", error);
        }
    }

    private static final class Apps implements ApplicationRepository {
        private final AfsDatabase database;

        Apps(AfsDatabase database) {
            this.database = database;
        }

        @Override public List<DataRecords.AppRecord> all() {
            Cursor cursor = database.getReadableDatabase().query("applications",
                    null, null, null, null, null, "package_name ASC");
            List<DataRecords.AppRecord> out = new ArrayList<>();
            try {
                while (cursor.moveToNext()) out.add(read(cursor));
            } finally {
                cursor.close();
            }
            return out;
        }

        @Override public DataRecords.AppRecord find(String packageName) {
            Cursor cursor = database.getReadableDatabase().query("applications",
                    null, "package_name=?", new String[]{packageName},
                    null, null, null);
            try {
                return cursor.moveToFirst() ? read(cursor) : null;
            } finally {
                cursor.close();
            }
        }

        @Override public DataRecords.AppRecord upsert(String packageName, String displayName,
                                                      boolean installed) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                upsertInternal(db, packageName, displayName, installed, now());
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return find(packageName);
        }

        @Override public void setEnabled(String packageName, boolean enabled) {
            ContentValues values = new ContentValues();
            values.put("enabled", enabled ? 1 : 0);
            values.put("updated_at", now());
            database.getWritableDatabase().update("applications", values,
                    "package_name=?", new String[]{packageName});
        }

        @Override public void setEnforcementState(String packageName, boolean suspended,
                                                  long changedAt) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                String appId = findId(db, packageName);
                if (appId == null) return;
                Cursor cursor = db.query("applications",
                        new String[]{"last_enforcement_state"}, "id=?", new String[]{appId},
                        null, null, null);
                boolean changed;
                try {
                    changed = !cursor.moveToFirst() || cursor.getInt(0) != (suspended ? 1 : 0);
                } finally {
                    cursor.close();
                }
                if (!changed) return;
                ContentValues values = new ContentValues();
                values.put("last_enforcement_state", suspended ? 1 : 0);
                values.put("last_enforced_at", changedAt);
                values.put("updated_at", now());
                db.update("applications", values, "id=?", new String[]{appId});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        String upsertInternal(SQLiteDatabase db, String packageName, String displayName,
                              boolean installed, long now) {
            String existing = findId(db, packageName);
            ContentValues values = new ContentValues();
            values.put("package_name", packageName);
            values.put("display_name", displayName == null || displayName.isEmpty()
                    ? packageName : displayName);
            values.put("installed", installed ? 1 : 0);
            values.put("updated_at", now);
            if (existing == null) {
                existing = id();
                values.put("id", existing);
                values.put("category", "");
                values.put("enabled", 1);
                values.put("last_enforcement_state", 0);
                values.put("last_enforced_at", 0);
                values.put("created_at", now);
                db.insertOrThrow("applications", null, values);
            } else {
                db.update("applications", values, "id=?", new String[]{existing});
            }
            return existing;
        }

        String findId(SQLiteDatabase db, String packageName) {
            Cursor cursor = db.query("applications", new String[]{"id"},
                    "package_name=?", new String[]{packageName}, null, null, null);
            try {
                return cursor.moveToFirst() ? cursor.getString(0) : null;
            } finally {
                cursor.close();
            }
        }

        private static DataRecords.AppRecord read(Cursor cursor) {
            return new DataRecords.AppRecord(
                    cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                    cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                    cursor.getString(cursor.getColumnIndexOrThrow("category")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) != 0,
                    cursor.getInt(cursor.getColumnIndexOrThrow("installed")) != 0,
                    cursor.getInt(cursor.getColumnIndexOrThrow("last_enforcement_state")) != 0,
                    cursor.getLong(cursor.getColumnIndexOrThrow("last_enforced_at")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
        }
    }

    private static final class Rules implements RuleRepository {
        private final AfsDatabase database;
        private final Apps apps;

        Rules(AfsDatabase database, Apps apps) {
            this.database = database;
            this.apps = apps;
        }

        @Override public List<DataRecords.RuleRecord> all() {
            SQLiteDatabase db = database.getReadableDatabase();
            Cursor cursor = db.rawQuery(
                    "SELECT r.*, a.package_name FROM rules r " +
                            "JOIN applications a ON a.id=r.application_id " +
                            "WHERE r.enabled=1 ORDER BY a.package_name",
                    null);
            List<DataRecords.RuleRecord> out = new ArrayList<>();
            try {
                while (cursor.moveToNext()) out.add(read(cursor, db));
            } finally {
                cursor.close();
            }
            return out;
        }

        @Override public DataRecords.RuleRecord find(String packageName) {
            Cursor cursor = database.getReadableDatabase().rawQuery(
                    "SELECT r.*, a.package_name FROM rules r " +
                            "JOIN applications a ON a.id=r.application_id " +
                            "WHERE a.package_name=? AND r.enabled=1",
                    new String[]{packageName});
            try {
                return cursor.moveToFirst() ? read(cursor, database.getReadableDatabase()) : null;
            } finally {
                cursor.close();
            }
        }

        @Override public DataRecords.RuleRecord save(String packageName, Rule rule,
                                                     String source, String reason) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                DataRecords.RuleRecord result = saveInternal(db, packageName, rule,
                        source, reason, 0L, now());
                db.setTransactionSuccessful();
                return result;
            } finally {
                db.endTransaction();
            }
        }

        @Override public void remove(String packageName, String source, String reason) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                removeInternal(db, packageName, source, reason, now());
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        @Override public void replaceAll(Map<String, Rule> rules, String source, String reason) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                long now = now();
                for (Map.Entry<String, Rule> entry : rules.entrySet()) {
                    saveInternal(db, entry.getKey(), entry.getValue(), source, reason, 0L, now);
                }
                Set<String> wanted = new LinkedHashSet<>(rules.keySet());
                for (String pkg : enabledPackages(db)) {
                    if (!wanted.contains(pkg)) removeInternal(db, pkg, source, reason, now);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        DataRecords.RuleRecord saveInternal(SQLiteDatabase db, String packageName, Rule rule,
                                            String source, String reason, long addedAt, long now) {
            String appId = apps.upsertInternal(db, packageName, packageName, true, now);
            Cursor cursor = db.query("rules", null, "application_id=?",
                    new String[]{appId}, null, null, null);
            String ruleId = null;
            String ruleUuid = null;
            String oldDigest = "";
            Rule oldRule = null;
            int oldRevision = 0;
            long createdAt = now;
            long existingAddedAt = 0L;
            try {
                if (cursor.moveToFirst()) {
                    ruleId = cursor.getString(cursor.getColumnIndexOrThrow("id"));
                    ruleUuid = cursor.getString(cursor.getColumnIndexOrThrow("rule_uuid"));
                    oldRevision = cursor.getInt(cursor.getColumnIndexOrThrow("revision"));
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
                    existingAddedAt = cursor.getLong(cursor.getColumnIndexOrThrow("added_at"));
                    oldRule = loadRule(db, ruleId);
                    oldDigest = digest(oldRule.groupKey());
                }
            } finally {
                cursor.close();
            }

            String newDigest = digest(rule.groupKey());
            if (oldRule != null && oldRule.same(rule)) {
                ContentValues unchanged = new ContentValues();
                unchanged.put("enabled", 1);
                unchanged.put("updated_at", now);
                if (addedAt > 0L) unchanged.put("added_at", addedAt);
                db.update("rules", unchanged, "id=?", new String[]{ruleId});
                return findInternal(db, packageName);
            }

            int revision = oldRevision + 1;
            ContentValues values = new ContentValues();
            values.put("application_id", appId);
            values.put("rule_type", "schedule");
            values.put("enabled", 1);
            values.put("freeze_mode", rule.freeze ? 1 : 0);
            values.put("gate", rule.gate ? 1 : 0);
            values.put("weekday_mask", rule.days);
            values.put("priority", 0);
            values.put("revision", revision);
            values.put("added_at", addedAt > 0L ? addedAt : existingAddedAt);
            values.put("updated_at", now);
            if (ruleId == null) {
                ruleId = id();
                ruleUuid = id();
                values.put("id", ruleId);
                values.put("rule_uuid", ruleUuid);
                values.put("created_at", now);
                db.insertOrThrow("rules", null, values);
            } else {
                db.update("rules", values, "id=?", new String[]{ruleId});
            }

            db.delete("time_windows", "rule_id=?", new String[]{ruleId});
            for (int i = 0; i < rule.windowCount(); i++) {
                ContentValues window = new ContentValues();
                window.put("id", id());
                window.put("rule_id", ruleId);
                window.put("start_minute", rule.startAt(i));
                window.put("end_minute", rule.endAt(i));
                window.put("sort_order", i);
                window.put("created_at", now);
                window.put("updated_at", now);
                db.insertOrThrow("time_windows", null, window);
            }

            revision(db, ruleId, revision, oldDigest, newDigest, source, reason,
                    ruleJson(rule), now);
            enqueueInternal(db, "rule", ruleId, oldRule == null ? "CREATE" : "UPDATE",
                    revision, ruleJson(rule), now);
            return findInternal(db, packageName);
        }

        void removeInternal(SQLiteDatabase db, String packageName, String source,
                            String reason, long now) {
            Cursor cursor = db.rawQuery(
                    "SELECT r.id, r.revision, r.rule_uuid FROM rules r " +
                            "JOIN applications a ON a.id=r.application_id " +
                            "WHERE a.package_name=? AND r.enabled=1",
                    new String[]{packageName});
            String ruleId = null;
            int revision = 0;
            try {
                if (cursor.moveToFirst()) {
                    ruleId = cursor.getString(0);
                    revision = cursor.getInt(1) + 1;
                }
            } finally {
                cursor.close();
            }
            if (ruleId == null) return;
            Rule old = loadRule(db, ruleId);
            ContentValues values = new ContentValues();
            values.put("enabled", 0);
            values.put("revision", revision);
            values.put("updated_at", now);
            db.update("rules", values, "id=?", new String[]{ruleId});
            revision(db, ruleId, revision, digest(old.groupKey()), "",
                    source, reason, ruleJson(old), now);
            enqueueInternal(db, "rule", ruleId, "DELETE", revision,
                    new JSONObject().toString(), now);
        }

        private Map<String, Rule> readAllInternal(SQLiteDatabase db) {
            Cursor cursor = db.rawQuery(
                    "SELECT r.*, a.package_name FROM rules r " +
                            "JOIN applications a ON a.id=r.application_id " +
                            "WHERE r.enabled=1 ORDER BY a.package_name", null);
            Map<String, Rule> out = new LinkedHashMap<>();
            try {
                while (cursor.moveToNext()) {
                    DataRecords.RuleRecord record = read(cursor, db);
                    out.put(record.packageName, record.rule);
                }
            } finally {
                cursor.close();
            }
            return out;
        }

        private DataRecords.RuleRecord findInternal(SQLiteDatabase db, String packageName) {
            Cursor cursor = db.rawQuery(
                    "SELECT r.*, a.package_name FROM rules r " +
                            "JOIN applications a ON a.id=r.application_id " +
                            "WHERE a.package_name=? AND r.enabled=1",
                    new String[]{packageName});
            try {
                return cursor.moveToFirst() ? read(cursor, db) : null;
            } finally {
                cursor.close();
            }
        }

        private DataRecords.RuleRecord read(Cursor cursor, SQLiteDatabase db) {
            String ruleId = cursor.getString(cursor.getColumnIndexOrThrow("id"));
            Rule rule = loadRule(db, ruleId);
            return new DataRecords.RuleRecord(
                    ruleId,
                    cursor.getString(cursor.getColumnIndexOrThrow("application_id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                    rule,
                    cursor.getInt(cursor.getColumnIndexOrThrow("revision")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) != 0,
                    cursor.getInt(cursor.getColumnIndexOrThrow("priority")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("added_at")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
        }

        private Rule loadRule(SQLiteDatabase db, String ruleId) {
            Cursor ruleCursor = db.query("rules", new String[]{"weekday_mask", "gate", "freeze_mode"},
                    "id=?", new String[]{ruleId}, null, null, null);
            int days;
            boolean gate;
            boolean freeze;
            try {
                if (!ruleCursor.moveToFirst()) {
                    throw new IllegalStateException("Rule row disappeared: " + ruleId);
                }
                days = ruleCursor.getInt(0);
                gate = ruleCursor.getInt(1) != 0;
                freeze = ruleCursor.getInt(2) != 0;
            } finally {
                ruleCursor.close();
            }
            Cursor windows = db.query("time_windows", new String[]{"start_minute", "end_minute"},
                    "rule_id=?", new String[]{ruleId}, null, null, "sort_order ASC");
            int[][] values = new int[windows.getCount()][2];
            int index = 0;
            try {
                while (windows.moveToNext()) {
                    values[index][0] = windows.getInt(0);
                    values[index][1] = windows.getInt(1);
                    index++;
                }
            } finally {
                windows.close();
            }
            return new Rule(days, values, gate, freeze);
        }

        private void revision(SQLiteDatabase db, String ruleId, int revision,
                              String oldDigest, String newDigest, String source,
                              String reason, String snapshot, long now) {
            ContentValues values = new ContentValues();
            values.put("id", id());
            values.put("rule_id", ruleId);
            values.put("revision", revision);
            values.put("changed_at", now);
            values.put("old_digest", oldDigest);
            values.put("new_digest", newDigest);
            values.put("source", source);
            values.put("reason", reason == null ? "" : reason);
            values.put("snapshot", snapshot);
            db.insertOrThrow("rule_revisions", null, values);
        }

        private Set<String> enabledPackages(SQLiteDatabase db) {
            Set<String> out = new LinkedHashSet<>();
            Cursor cursor = db.rawQuery(
                    "SELECT a.package_name FROM rules r " +
                            "JOIN applications a ON a.id=r.application_id WHERE r.enabled=1",
                    null);
            try {
                while (cursor.moveToNext()) out.add(cursor.getString(0));
            } finally {
                cursor.close();
            }
            return out;
        }
    }

    private static final class Config implements ConfigurationRepository {
        private final AfsDatabase database;

        Config(AfsDatabase database) {
            this.database = database;
        }

        @Override public String get(String key, String fallback) {
            Cursor cursor = database.getReadableDatabase().query("configuration",
                    new String[]{"value"}, "key=?", new String[]{key}, null, null, null);
            try {
                return cursor.moveToFirst() ? cursor.getString(0) : fallback;
            } finally {
                cursor.close();
            }
        }

        @Override public int getInt(String key, int fallback) {
            try {
                return Integer.parseInt(get(key, String.valueOf(fallback)));
            } catch (RuntimeException error) {
                return fallback;
            }
        }

        @Override public boolean getBoolean(String key, boolean fallback) {
            return Boolean.parseBoolean(get(key, String.valueOf(fallback)));
        }

        @Override public void put(String key, String value, String type) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                putInternal(db, key, value, type, now());
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        @Override public void putInt(String key, int value) {
            put(key, String.valueOf(value), "int");
        }

        @Override public void putBoolean(String key, boolean value) {
            put(key, String.valueOf(value), "boolean");
        }

        void putInternal(SQLiteDatabase db, String key, String value, String type, long now) {
            ContentValues values = new ContentValues();
            values.put("key", key);
            values.put("value", value == null ? "" : value);
            values.put("value_type", type == null ? "string" : type);
            values.put("updated_at", now);
            db.insertWithOnConflict("configuration", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private static final class Events implements EventRepository {
        private final AfsDatabase database;

        Events(AfsDatabase database) {
            this.database = database;
        }

        @Override public void record(DataRecords.EventRecord event) {
            ContentValues values = new ContentValues();
            values.put("id", event.id);
            values.put("event_type", event.eventType);
            values.put("application_id", event.applicationId);
            values.put("event_time", event.eventTime);
            values.put("local_date", event.localDate);
            values.put("timezone", event.timezone);
            values.put("execution_result", event.executionResult);
            values.put("enforcement_method", event.enforcementMethod);
            values.put("error_code", event.errorCode);
            values.put("metadata", event.metadata);
            values.put("dedupe_key", event.dedupeKey);
            values.put("created_at", now());
            database.getWritableDatabase().insertWithOnConflict("enforcement_events",
                    null, values, SQLiteDatabase.CONFLICT_IGNORE);
        }

        @Override public List<DataRecords.EventRecord> recent(int limit) {
            Cursor cursor = database.getReadableDatabase().query("enforcement_events",
                    null, null, null, null, null, "event_time DESC", String.valueOf(limit));
            List<DataRecords.EventRecord> out = new ArrayList<>();
            try {
                while (cursor.moveToNext()) {
                    out.add(new DataRecords.EventRecord(
                            cursor.getString(cursor.getColumnIndexOrThrow("event_type")),
                            cursor.getString(cursor.getColumnIndexOrThrow("application_id")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("event_time")),
                            cursor.getString(cursor.getColumnIndexOrThrow("local_date")),
                            cursor.getString(cursor.getColumnIndexOrThrow("timezone")),
                            cursor.getString(cursor.getColumnIndexOrThrow("execution_result")),
                            cursor.getString(cursor.getColumnIndexOrThrow("enforcement_method")),
                            cursor.getString(cursor.getColumnIndexOrThrow("error_code")),
                            cursor.getString(cursor.getColumnIndexOrThrow("metadata")),
                            cursor.getString(cursor.getColumnIndexOrThrow("dedupe_key"))));
                }
            } finally {
                cursor.close();
            }
            return out;
        }

        @Override public int deleteOlderThan(long cutoff) {
            return database.getWritableDatabase().delete("enforcement_events",
                    "event_time<?", new String[]{String.valueOf(cutoff)});
        }
    }

    private static final class Focus implements FocusRepository {
        private final AfsDatabase database;

        Focus(AfsDatabase database) {
            this.database = database;
        }

        @Override public DataRecords.FocusSessionRecord start(String sessionType,
                                                              long plannedEndAt) {
            String id = id();
            long now = now();
            ContentValues values = new ContentValues();
            values.put("id", id);
            values.put("session_type", sessionType);
            values.put("started_at", now);
            values.put("planned_end_at", plannedEndAt);
            values.put("ended_at", 0L);
            values.put("end_reason", "");
            values.put("status", "ACTIVE");
            values.put("created_at", now);
            database.getWritableDatabase().insertOrThrow("focus_sessions", null, values);
            return new DataRecords.FocusSessionRecord(id, sessionType, now, plannedEndAt,
                    0L, "", "ACTIVE", now);
        }

        @Override public DataRecords.FocusSessionRecord active() {
            Cursor cursor = database.getReadableDatabase().query("focus_sessions",
                    null, "status=?", new String[]{"ACTIVE"}, null, null,
                    "started_at DESC", "1");
            try {
                return cursor.moveToFirst() ? read(cursor) : null;
            } finally {
                cursor.close();
            }
        }

        @Override public void finish(String id, long endedAt, String reason, String status) {
            ContentValues values = new ContentValues();
            values.put("ended_at", endedAt);
            values.put("end_reason", reason == null ? "" : reason);
            values.put("status", status);
            database.getWritableDatabase().update("focus_sessions", values,
                    "id=?", new String[]{id});
        }

        @Override public List<DataRecords.FocusSessionRecord> between(long from, long to) {
            Cursor cursor = database.getReadableDatabase().query("focus_sessions",
                    null, "started_at>=? AND started_at<?",
                    new String[]{String.valueOf(from), String.valueOf(to)},
                    null, null, "started_at ASC");
            List<DataRecords.FocusSessionRecord> out = new ArrayList<>();
            try {
                while (cursor.moveToNext()) out.add(read(cursor));
            } finally {
                cursor.close();
            }
            return out;
        }

        private static DataRecords.FocusSessionRecord read(Cursor cursor) {
            return new DataRecords.FocusSessionRecord(
                    cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("session_type")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("planned_end_at")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("ended_at")),
                    cursor.getString(cursor.getColumnIndexOrThrow("end_reason")),
                    cursor.getString(cursor.getColumnIndexOrThrow("status")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at")));
        }
    }

    private static final class Stats implements StatisticsRepository {
        private final AfsDatabase database;

        Stats(AfsDatabase database) {
            this.database = database;
        }

        @Override public DataRecords.DailyStatRecord forDay(String localDate, String timezone) {
            Cursor cursor = database.getReadableDatabase().query("daily_statistics",
                    null, "local_date=? AND timezone=?",
                    new String[]{localDate, timezone}, null, null, null);
            try {
                return cursor.moveToFirst() ? read(cursor) : null;
            } finally {
                cursor.close();
            }
        }

        @Override public void upsert(DataRecords.DailyStatRecord record) {
            ContentValues values = new ContentValues();
            values.put("id", UUID.nameUUIDFromBytes(
                    (record.localDate + "|" + record.timezone).getBytes(UTF8)).toString());
            values.put("local_date", record.localDate);
            values.put("timezone", record.timezone);
            values.put("focus_duration", record.focusDuration);
            values.put("enforced_duration", record.enforcedDuration);
            values.put("blocked_attempts", record.blockedAttempts);
            values.put("successful_enforcements", record.successfulEnforcements);
            values.put("failed_enforcements", record.failedEnforcements);
            values.put("updated_at", record.updatedAt);
            database.getWritableDatabase().insertWithOnConflict("daily_statistics",
                    null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }

        @Override public List<DataRecords.DailyStatRecord> range(String fromDate, String toDate,
                                                                 String timezone) {
            Cursor cursor = database.getReadableDatabase().query("daily_statistics",
                    null, "local_date>=? AND local_date<=? AND timezone=?",
                    new String[]{fromDate, toDate, timezone}, null, null, "local_date ASC");
            List<DataRecords.DailyStatRecord> out = new ArrayList<>();
            try {
                while (cursor.moveToNext()) out.add(read(cursor));
            } finally {
                cursor.close();
            }
            return out;
        }

        @Override public void increment(String localDate, String timezone, long focusDuration,
                                        long enforcedDuration, int blockedAttempts,
                                        int successfulEnforcements, int failedEnforcements,
                                        long updatedAt) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                DataRecords.DailyStatRecord current = forDayInternal(db, localDate, timezone);
                long focus = (current == null ? 0L : current.focusDuration)
                        + Math.max(0L, focusDuration);
                long enforced = (current == null ? 0L : current.enforcedDuration)
                        + Math.max(0L, enforcedDuration);
                int blocked = (current == null ? 0 : current.blockedAttempts)
                        + Math.max(0, blockedAttempts);
                int success = (current == null ? 0 : current.successfulEnforcements)
                        + Math.max(0, successfulEnforcements);
                int failed = (current == null ? 0 : current.failedEnforcements)
                        + Math.max(0, failedEnforcements);
                ContentValues values = new ContentValues();
                values.put("id", UUID.nameUUIDFromBytes(
                        (localDate + "|" + timezone).getBytes(UTF8)).toString());
                values.put("local_date", localDate);
                values.put("timezone", timezone);
                values.put("focus_duration", focus);
                values.put("enforced_duration", enforced);
                values.put("blocked_attempts", blocked);
                values.put("successful_enforcements", success);
                values.put("failed_enforcements", failed);
                values.put("updated_at", updatedAt);
                db.insertWithOnConflict("daily_statistics", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        private DataRecords.DailyStatRecord forDayInternal(SQLiteDatabase db,
                                                            String localDate,
                                                            String timezone) {
            Cursor cursor = db.query("daily_statistics", null,
                    "local_date=? AND timezone=?",
                    new String[]{localDate, timezone}, null, null, null);
            try {
                return cursor.moveToFirst() ? read(cursor) : null;
            } finally {
                cursor.close();
            }
        }

        private static DataRecords.DailyStatRecord read(Cursor cursor) {
            return new DataRecords.DailyStatRecord(
                    cursor.getString(cursor.getColumnIndexOrThrow("local_date")),
                    cursor.getString(cursor.getColumnIndexOrThrow("timezone")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("focus_duration")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("enforced_duration")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("blocked_attempts")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("successful_enforcements")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("failed_enforcements")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
        }
    }

    private static final class Outbox implements SyncOutbox {
        private final AfsDatabase database;

        Outbox(AfsDatabase database) {
            this.database = database;
        }

        @Override public String enqueue(String entityType, String entityId, String operation,
                                        int entityRevision, String payload) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                String id = enqueueInternal(db, entityType, entityId, operation,
                        entityRevision, payload, now());
                db.setTransactionSuccessful();
                return id;
            } finally {
                db.endTransaction();
            }
        }

        @Override public List<DataRecords.OutboxRecord> getPendingOperations() {
            Cursor cursor = database.getReadableDatabase().query("sync_outbox",
                    null, "sync_status=? AND next_attempt_at<=?",
                    new String[]{"PENDING", String.valueOf(now())},
                    null, null, "created_at ASC");
            List<DataRecords.OutboxRecord> out = new ArrayList<>();
            try {
                while (cursor.moveToNext()) {
                    out.add(new DataRecords.OutboxRecord(
                            cursor.getString(cursor.getColumnIndexOrThrow("id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("entity_type")),
                            cursor.getString(cursor.getColumnIndexOrThrow("entity_id")),
                            cursor.getString(cursor.getColumnIndexOrThrow("operation")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("entity_revision")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("payload_version")),
                            cursor.getString(cursor.getColumnIndexOrThrow("payload")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                            cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
                            cursor.getString(cursor.getColumnIndexOrThrow("sync_status")),
                            cursor.getString(cursor.getColumnIndexOrThrow("last_error")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("next_attempt_at"))));
                }
            } finally {
                cursor.close();
            }
            return out;
        }

        @Override public void markCompleted(String operationId) {
            ContentValues values = new ContentValues();
            values.put("sync_status", "COMPLETED");
            values.put("last_error", "");
            database.getWritableDatabase().update("sync_outbox", values,
                    "id=?", new String[]{operationId});
        }

        @Override public void markFailed(String operationId, String reason) {
            SQLiteDatabase db = database.getWritableDatabase();
            Cursor cursor = db.query("sync_outbox", new String[]{"retry_count"},
                    "id=?", new String[]{operationId}, null, null, null);
            int retry = 0;
            try {
                if (cursor.moveToFirst()) retry = cursor.getInt(0);
            } finally {
                cursor.close();
            }
            retry++;
            ContentValues values = new ContentValues();
            values.put("sync_status", "PENDING");
            values.put("retry_count", retry);
            values.put("last_error", reason == null ? "" : reason);
            values.put("next_attempt_at", now() + Math.min(3600_000L, retry * 60_000L));
            db.update("sync_outbox", values, "id=?", new String[]{operationId});
        }

        private String enqueueInternal(SQLiteDatabase db, String entityType, String entityId,
                                       String operation, int entityRevision, String payload,
                                       long now) {
            String id = id();
            ContentValues values = new ContentValues();
            values.put("id", id);
            values.put("entity_type", entityType);
            values.put("entity_id", entityId);
            values.put("operation", operation);
            values.put("entity_revision", entityRevision);
            values.put("payload_version", 1);
            values.put("payload", payload);
            values.put("created_at", now);
            values.put("retry_count", 0);
            values.put("sync_status", "PENDING");
            values.put("last_error", "");
            values.put("next_attempt_at", now);
            db.insertOrThrow("sync_outbox", null, values);
            return id;
        }
    }

    private static void enqueueInternal(SQLiteDatabase db, String entityType, String entityId,
                                        String operation, int revision, String payload, long now) {
        ContentValues values = new ContentValues();
        values.put("id", id());
        values.put("entity_type", entityType);
        values.put("entity_id", entityId);
        values.put("operation", operation);
        values.put("entity_revision", revision);
        values.put("payload_version", 1);
        values.put("payload", payload);
        values.put("created_at", now);
        values.put("retry_count", 0);
        values.put("sync_status", "PENDING");
        values.put("last_error", "");
        values.put("next_attempt_at", now);
        db.insertOrThrow("sync_outbox", null, values);
    }
}

