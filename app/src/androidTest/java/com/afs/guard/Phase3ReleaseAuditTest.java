package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.afs.guard.data.AfsDatabase;
import com.afs.guard.data.AfsRepositories;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class Phase3ReleaseAuditTest {
    @Test public void productionDatabaseMigrationAndOutboxAreHealthy() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AfsRepositories repositories = AfsRepositories.get(context);
        String migration = repositories.migrationResult();
        assertTrue("migration result: " + migration,
                "SUCCESS".equals(migration) || "SKIPPED".equals(migration));

        File databaseFile = context.getDatabasePath(AfsDatabase.NAME);
        assertTrue("database file missing", databaseFile.isFile());
        StringBuilder report = new StringBuilder();
        report.append("migration=").append(migration).append('\n');
        report.append("database_bytes=").append(databaseFile.length()).append('\n');

        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(
                databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
            try (Cursor integrity = database.rawQuery("PRAGMA integrity_check", null)) {
                assertTrue(integrity.moveToFirst());
                assertEquals("ok", integrity.getString(0));
                report.append("integrity=").append(integrity.getString(0)).append('\n');
            }
            try (Cursor foreignKeys = database.rawQuery("PRAGMA foreign_key_check", null)) {
                assertTrue("foreign key violations", !foreignKeys.moveToFirst());
                report.append("foreign_key_check=ok\n");
            }
            for (String table : new String[]{
                    "applications", "rules", "time_windows", "freeze_slots",
                    "freeze_slot_members", "rule_revisions", "focus_sessions",
                    "enforcement_events", "daily_statistics", "sync_outbox",
                    "schema_migrations"
            }) {
                report.append(table).append('=').append(count(database, table)).append('\n');
            }
            report.append("migration_rows=");
            try (Cursor rows = database.rawQuery(
                    "SELECT version || ':' || migration_name || ':' || result "
                            + "FROM schema_migrations ORDER BY version", null)) {
                while (rows.moveToNext()) {
                    report.append(rows.getString(0)).append(';');
                }
            }
            report.append('\n');
        }

        SharedPreferences status = context.getSharedPreferences(
                "afs_data_status", Context.MODE_PRIVATE);
        String snapshot = status.getString("phase3_migration_snapshot", "");
        assertTrue("migration snapshot missing", !snapshot.isEmpty());
        assertTrue("migration snapshot file missing", new File(snapshot).isFile());
        assertTrue("migration not marked complete",
                status.getBoolean("phase3_migration_complete", false));
        assertNotNull(repositories.ruleRepository().all());
        report.append("snapshot=").append(snapshot).append('\n');
        report.append("migration_complete=true\n");

        File output = new File(context.getExternalFilesDir(null),
                "phase3-release-audit.txt");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(report.toString().getBytes(StandardCharsets.UTF_8));
        }
        assertTrue("audit output missing", output.isFile() && output.length() > 0L);
    }

    private static int count(SQLiteDatabase database, String table) {
        try (Cursor cursor = database.rawQuery(
                "SELECT COUNT(*) FROM " + table, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }
}
