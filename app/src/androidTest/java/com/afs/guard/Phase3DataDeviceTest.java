package com.afs.guard;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.afs.guard.data.AfsDatabase;
import com.afs.guard.data.AfsRepositories;
import com.afs.guard.data.DataRecords;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class Phase3DataDeviceTest {
    @Test public void strictMigrationImportsVerifiesAndStaysIdempotent() throws Exception {
        Fixture fixture = fixture("success");
        fixture.guard.edit()
                .putString("rules", rulesJson())
                .putString("freeze_slots", slotsJson())
                .commit();
        fixture.timeWindow.edit().putInt("start", 60).putInt("end", 120).commit();
        fixture.dayBoundary.edit().putInt("reset_hour", 4).putInt("reset_minute", 30).commit();
        fixture.screen.edit().putBoolean("enabled", true).putInt("minutes", 300).commit();

        LegacyDatabaseMigration.run(fixture.context, fixture.repositories,
                fixture.guard, fixture.timeWindow, fixture.dayBoundary,
                fixture.screen, fixture.ui, fixture.language);

        assertEquals("SUCCESS", fixture.repositories.migrationResult());
        assertEquals("SUCCESS", fixture.status.getString("phase3_migration_result", ""));
        assertTrue(fixture.status.getBoolean("phase3_migration_complete", false));
        assertFalse(fixture.status.getString("phase3_migration_snapshot", "").isEmpty());

        List<DataRecords.RuleRecord> rules = fixture.repositories.ruleRepository().all();
        assertEquals(2, rules.size());
        Map<String, Rule> byPackage = new LinkedHashMap<>();
        Map<String, Long> added = new LinkedHashMap<>();
        for (DataRecords.RuleRecord record : rules) {
            byPackage.put(record.packageName, record.rule);
            added.put(record.packageName, record.addedAt);
        }
        Rule overnight = byPackage.get("com.example.overnight");
        assertNotNull(overnight);
        assertEquals(31, overnight.days);
        assertEquals(1320, overnight.startAt(0));
        assertEquals(120, overnight.endAt(0));
        assertTrue(overnight.freeze);
        assertFalse(overnight.gate);
        assertEquals(123456L, added.get("com.example.overnight").longValue());

        Rule multi = byPackage.get("com.example.multi");
        assertNotNull(multi);
        assertEquals(2, multi.windowCount());
        assertFalse(multi.freeze);
        assertTrue(multi.crossMidnightAt(1));
        assertEquals(60, fixture.repositories.configuration()
                .getInt("time_window.start", -1));
        assertEquals(4, fixture.repositories.configuration()
                .getInt("day_boundary.reset_hour", -1));
        LegacySnapshotParser.RulesSnapshot parsedRules =
                LegacySnapshotParser.parseRules(fixture.guard.getString("rules", ""));
        LegacySnapshotParser.SlotsSnapshot parsedSlots =
                LegacySnapshotParser.parseSlots(fixture.guard.getString("freeze_slots", "[]"));
        Rule slotOvernight = parsedSlots.slots.get("slot-overnight");
        Rule slotAllow = parsedSlots.slots.get("slot-allow");
        assertNotNull(slotOvernight);
        assertNotNull(slotAllow);
        assertFalse(FreezeSlotStore.unassigned(parsedRules.rules.get("com.example.overnight")));
        assertFalse(FreezeSlotStore.unassigned(parsedRules.rules.get("com.example.multi")));
        assertEquals(FreezeSlotStore.scheduleKey(slotOvernight),
                FreezeSlotStore.scheduleKey(parsedRules.rules.get("com.example.overnight")));
        assertEquals(FreezeSlotStore.scheduleKey(slotAllow),
                FreezeSlotStore.scheduleKey(parsedRules.rules.get("com.example.multi")));
        assertNotNull(fixture.repositories.applications()
                .find("com.example.overnight"));
        assertNotNull(fixture.repositories.applications()
                .find("com.example.multi"));

        try (SQLiteDatabase db = open(fixture)) {
            count(db, "rules", 2);
            count(db, "time_windows", 3);
            count(db, "freeze_slots", 2);
            count(db, "freeze_slot_members", 2);
        }

        LegacyDatabaseMigration.run(fixture.context, fixture.repositories,
                fixture.guard, fixture.timeWindow, fixture.dayBoundary,
                fixture.screen, fixture.ui, fixture.language);
        assertEquals(2, fixture.repositories.ruleRepository().all().size());
        try (SQLiteDatabase db = open(fixture)) {
            count(db, "rules", 2);
            count(db, "freeze_slots", 2);
            count(db, "schema_migrations", 1);
        }
    }

    @Test public void malformedLegacyJsonFailsWithoutTouchingPreferences() throws Exception {
        Fixture fixture = fixture("malformed");
        fixture.guard.edit()
                .putString("rules", "{broken")
                .putString("freeze_slots", "[]")
                .commit();
        LegacyDatabaseMigration.run(fixture.context, fixture.repositories,
                fixture.guard, fixture.timeWindow, fixture.dayBoundary,
                fixture.screen, fixture.ui, fixture.language);

        assertEquals("FAILED", fixture.repositories.migrationResult());
        assertEquals("{broken", fixture.guard.getString("rules", ""));
        assertTrue(fixture.repositories.ruleRepository().all().isEmpty());
        assertTrue(fixture.status.getString("phase3_migration_error", "")
                .contains("Invalid legacy snapshot"));

        fixture.guard.edit().putString("rules", rulesJson()).commit();
        LegacyDatabaseMigration.run(fixture.context, fixture.repositories,
                fixture.guard, fixture.timeWindow, fixture.dayBoundary,
                fixture.screen, fixture.ui, fixture.language);
        assertEquals("SUCCESS", fixture.repositories.migrationResult());
        assertEquals(2, fixture.repositories.ruleRepository().all().size());
    }

    @Test public void repositoryRevisionOutboxEventsFocusAndRollbackStayConsistent()
            throws Exception {
        Fixture fixture = fixture("repository");
        fixture.guard.edit()
                .putString("rules", rulesJson())
                .putString("freeze_slots", "[]")
                .commit();
        LegacyDatabaseMigration.run(fixture.context, fixture.repositories,
                fixture.guard, fixture.timeWindow, fixture.dayBoundary,
                fixture.screen, fixture.ui, fixture.language);
        assertEquals("SUCCESS", fixture.repositories.migrationResult());

        Rule changed = new Rule(127, new int[][]{{480, 720}}, true, true);
        fixture.repositories.ruleRepository().save("com.example.multi", changed,
                "test", "edit one rule");
        DataRecords.RuleRecord updated = fixture.repositories.ruleRepository()
                .find("com.example.multi");
        assertNotNull(updated);
        assertEquals(2, updated.revision);
        assertTrue(updated.rule.same(changed));
        assertFalse(fixture.repositories.outbox().getPendingOperations().isEmpty());

        int revisions;
        int outbox;
        try (SQLiteDatabase db = open(fixture)) {
            revisions = count(db, "rule_revisions", -1);
            outbox = count(db, "sync_outbox", -1);
        }
        Map<String, Rule> replacement = new LinkedHashMap<>();
        replacement.put("com.example.overnight",
                new Rule(31, new int[][]{{1320, 120}}, false, true));
        Map<String, Rule> duplicateSlots = new LinkedHashMap<>();
        duplicateSlots.put("slot-a", new Rule(127, new int[][]{{0, 60}}, true, true));
        duplicateSlots.put("slot-b", new Rule(127, new int[][]{{0, 60}}, true, true));
        try {
            fixture.repositories.importLegacySnapshot(replacement, duplicateSlots,
                    new LinkedHashMap<String, Long>(), new LinkedHashMap<String, String>(),
                    "rollback", "SUCCESS");
            fail("duplicate schedule keys must abort the import transaction");
        } catch (RuntimeException expected) {
            // The UNIQUE(schedule_key) constraint must roll the whole import back.
        }
        assertEquals(2, fixture.repositories.ruleRepository().all().size());
        assertEquals(2, fixture.repositories.ruleRepository()
                .find("com.example.multi").revision);
        try (SQLiteDatabase db = open(fixture)) {
            assertEquals(revisions, count(db, "rule_revisions", -1));
            assertEquals(outbox, count(db, "sync_outbox", -1));
        }

        DataRecords.EventRecord event = new DataRecords.EventRecord(
                Phase3Telemetry.APP_SUSPENDED, null, 1_800_000_000_000L,
                "2027-01-15", "Asia/Shanghai", "VERIFIED", "device_owner",
                "", "{\"test\":true}", "dedupe-fixture");
        fixture.repositories.eventRepository().record(event);
        fixture.repositories.eventRepository().record(new DataRecords.EventRecord(
                Phase3Telemetry.APP_SUSPENDED, null, 1_800_000_000_001L,
                "2027-01-15", "Asia/Shanghai", "VERIFIED", "device_owner",
                "", "{}", "dedupe-fixture"));
        assertEquals(1, fixture.repositories.eventRepository().recent(10).size());
        fixture.repositories.eventRepository().record(new DataRecords.EventRecord(
                Phase3Telemetry.APP_BLOCKED, null, 1_600_000_000_000L,
                "2020-09-13", "Asia/Shanghai", "BLOCKED", "device_owner",
                "", "{}", "old-fixture"));
        assertEquals(1, fixture.repositories.eventRepository()
                .deleteOlderThan(1_700_000_000_000L));
        assertEquals(1, fixture.repositories.eventRepository().recent(10).size());

        DataRecords.FocusSessionRecord session = fixture.repositories.focusRepository()
                .start("focus", 1_800_000_060_000L);
        assertNotNull(session);
        assertNotNull(fixture.repositories.focusRepository().active());
        fixture.repositories.focusRepository().finish(session.id,
                session.startedAt + 60_000L, "completed", "COMPLETED");
        assertNull(fixture.repositories.focusRepository().active());

        fixture.repositories.statistics().increment("2027-01-15", "Asia/Shanghai",
                60_000L, 30_000L, 2, 3, 1, 1_800_000_100_000L);
        DataRecords.DailyStatRecord stat = fixture.repositories.statistics()
                .forDay("2027-01-15", "Asia/Shanghai");
        assertNotNull(stat);
        assertEquals(60_000L, stat.focusDuration);
        assertEquals(30_000L, stat.enforcedDuration);
        assertEquals(2, stat.blockedAttempts);
        assertEquals(3, stat.successfulEnforcements);
        assertEquals(1, stat.failedEnforcements);
    }

    @Test public void localBackupExportsAndRestoresDatabaseSnapshot() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        LocalBackup.export(context, exported);
        Map<String, byte[]> data = LocalArchive.decode(
                new ByteArrayInputStream(exported.toByteArray()));
        byte[] databaseBytes = data.get("database/" + AfsDatabase.NAME);
        assertNotNull(databaseBytes);

        File source = File.createTempFile("afs-phase3-source-", ".db",
                context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(source)) {
                out.write(databaseBytes);
            }
            int sourceRules;
            int sourceMigrations;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(source.getAbsolutePath(),
                    null, SQLiteDatabase.OPEN_READONLY)) {
                sourceRules = count(db, "rules", -1);
                sourceMigrations = count(db, "schema_migrations", -1);
            }

            Fixture restored = fixture("backup-restore");
            LocalBackup.apply(restored.context, data);
            try (SQLiteDatabase db = open(restored)) {
                assertEquals(sourceRules, count(db, "rules", -1));
                assertEquals(sourceMigrations, count(db, "schema_migrations", -1));
            }
        } finally {
            if (!source.delete()) source.deleteOnExit();
        }
    }

    @Test public void repositoryPerformanceStaysWithinBudget() throws Exception {
        Fixture fixture = fixture("performance");
        Map<String, Rule> rules = new LinkedHashMap<>();
        Map<String, Long> added = new LinkedHashMap<>();
        for (int i = 0; i < 500; i++) {
            String pkg = String.format("com.example.performance.%03d", i);
            rules.put(pkg, new Rule(127, new int[][]{{480 + (i % 60), 720 + (i % 60)}},
                    true, true));
            added.put(pkg, 1_700_000_000_000L + i);
        }
        long writeStart = System.nanoTime();
        fixture.repositories.importLegacySnapshot(rules,
                new LinkedHashMap<String, Rule>(), added,
                new LinkedHashMap<String, String>(), "perf", "SUCCESS");
        long writeMs = (System.nanoTime() - writeStart) / 1_000_000L;

        long readStart = System.nanoTime();
        assertEquals(500, fixture.repositories.ruleRepository().all().size());
        long readMs = (System.nanoTime() - readStart) / 1_000_000L;

        long eventStart = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            fixture.repositories.eventRepository().record(new DataRecords.EventRecord(
                    Phase3Telemetry.APP_BLOCKED, null, 1_800_000_000_000L + i,
                    "2027-01-15", "Asia/Shanghai", "BLOCKED", "device_owner",
                    "", "{}", "perf-" + i));
        }
        assertEquals(200, fixture.repositories.eventRepository().recent(200).size());
        long eventMs = (System.nanoTime() - eventStart) / 1_000_000L;

        String report = "rules=500 write_ms=" + writeMs + " read_ms=" + readMs
                + " events=200 recent_ms=" + eventMs;
        Log.i("AFSPhase3Perf", report);
        File output = new File(fixture.context.getCacheDir(), "phase3-performance.txt");
        try (FileOutputStream out = new FileOutputStream(output)) {
            out.write(report.getBytes("UTF-8"));
        }
        assertTrue("write budget: " + report, writeMs < 30_000L);
        assertTrue("read budget: " + report, readMs < 5_000L);
        assertTrue("event budget: " + report, eventMs < 15_000L);
    }

    private static Fixture fixture(String label) throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String token = label + "_" + System.nanoTime() + "_";
        File root = new File(base.getCacheDir(), "phase3-" + token);
        assertTrue(root.mkdirs());
        File files = new File(root, "files");
        File cache = new File(root, "cache");
        File databases = new File(root, "databases");
        assertTrue(files.mkdirs());
        assertTrue(cache.mkdirs());
        assertTrue(databases.mkdirs());
        Context isolated = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
            @Override public File getCacheDir() { return cache; }
            @Override public File getDatabasePath(String name) {
                return new File(databases, name);
            }
            @Override public SharedPreferences getSharedPreferences(String name,
                                                                     int mode) {
                return base.getSharedPreferences(token + name, mode);
            }
            @Override public String getPackageName() { return "com.afs.guard.fixture"; }
        };
        return new Fixture(isolated, AfsRepositories.isolated(isolated));
    }

    private static final class Fixture {
        final Context context;
        final AfsRepositories repositories;
        final SharedPreferences guard;
        final SharedPreferences timeWindow;
        final SharedPreferences dayBoundary;
        final SharedPreferences screen;
        final SharedPreferences ui;
        final SharedPreferences language;
        final SharedPreferences status;

        Fixture(Context context, AfsRepositories repositories) {
            this.context = context;
            this.repositories = repositories;
            guard = context.getSharedPreferences("afs_guard", Context.MODE_PRIVATE);
            timeWindow = context.getSharedPreferences("afs_time_window",
                    Context.MODE_PRIVATE);
            dayBoundary = context.getSharedPreferences("afs_day_boundary",
                    Context.MODE_PRIVATE);
            screen = context.getSharedPreferences("afs_screen_on_limit",
                    Context.MODE_PRIVATE);
            ui = context.getSharedPreferences("afs_ui", Context.MODE_PRIVATE);
            language = context.getSharedPreferences("afs_lang", Context.MODE_PRIVATE);
            status = context.getSharedPreferences("afs_data_status",
                    Context.MODE_PRIVATE);
        }
    }

    private static String rulesJson() throws Exception {
        JSONObject rules = new JSONObject();
        rules.put("com.example.overnight", new JSONObject()
                .put("d", 31)
                .put("freeze", true)
                .put("g", false)
                .put("at", 123456L)
                .put("w", new JSONArray().put(new JSONArray().put(1320).put(120))));
        rules.put("com.example.multi", new JSONObject()
                .put("d", 127)
                .put("freeze", false)
                .put("g", true)
                .put("at", 654321L)
                .put("w", new JSONArray()
                        .put(new JSONArray().put(480).put(720))
                        .put(new JSONArray().put(1320).put(60))));
        return rules.toString();
    }

    private static String slotsJson() throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("id", "slot-overnight")
                        .put("days", 31).put("freeze", true)
                        .put("windows", new JSONArray()
                                .put(new JSONArray().put(1320).put(120))))
                .put(new JSONObject().put("id", "slot-allow")
                        .put("days", 127).put("freeze", false)
                        .put("windows", new JSONArray()
                                .put(new JSONArray().put(480).put(720))
                                .put(new JSONArray().put(1320).put(60))))
                .toString();
    }

    private static SQLiteDatabase open(Fixture fixture) {
        File file = fixture.context.getDatabasePath(AfsDatabase.NAME);
        assertTrue(file.isFile());
        return SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY);
    }

    private static int count(SQLiteDatabase db, String table, int expected) {
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null);
        try {
            assertTrue(cursor.moveToFirst());
            int actual = cursor.getInt(0);
            if (expected >= 0) assertEquals(expected, actual);
            return actual;
        } finally {
            cursor.close();
        }
    }
}
