package com.afs.guard.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Single local database for phase-three data.
 *
 * The database is intentionally separate from the enforcement path.  Existing
 * SharedPreferences remain authoritative until the repository migration has
 * been verified, so a database failure can never silently unfreeze an app.
 */
public final class AfsDatabase extends SQLiteOpenHelper {
    public static final String NAME = "afs_data.db";
    public static final int VERSION = 1;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable work) {
            Thread thread = new Thread(work, "afs-data-io");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    });

    private static volatile AfsDatabase instance;

    public static AfsDatabase get(Context context) {
        if (instance == null) {
            synchronized (AfsDatabase.class) {
                if (instance == null) {
                    instance = new AfsDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** Isolated helper for migration tests; production code uses {@link #get(Context)}. */
    public static AfsDatabase isolated(Context context) {
        return new AfsDatabase(context.getApplicationContext());
    }

    public static ExecutorService io() {
        return IO;
    }

    public static void runAsync(Runnable work) {
        IO.execute(work);
    }

    public static synchronized void closeSingleton() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    /**
     * Consistent database file for local backup.
     *
     * VACUUM INTO is a single SQLite snapshot.  Older SQLite builds fall back
     * to a full WAL checkpoint followed by a byte copy.
     */
    public byte[] snapshotBytes(Context context, int limit) throws Exception {
        File cache = context.getCacheDir();
        File temp = new File(cache, "afs-data-snapshot-" + System.nanoTime() + ".db");
        if (temp.exists() && !temp.delete()) {
            throw new IllegalStateException("Cannot prepare database snapshot");
        }
        try {
            SQLiteDatabase db = getWritableDatabase();
            try {
                db.execSQL("VACUUM INTO '" + temp.getAbsolutePath().replace("'", "''") + "'");
            } catch (RuntimeException unsupported) {
                Cursor checkpoint = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null);
                try {
                    checkpoint.moveToFirst();
                } finally {
                    checkpoint.close();
                }
                File source = context.getDatabasePath(NAME);
                copyBounded(source, temp, limit);
            }
            return readBounded(temp, limit);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static void copyBounded(File source, File target, int limit) throws Exception {
        try (FileInputStream in = new FileInputStream(source);
             java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IllegalStateException("Database snapshot is too large");
                out.write(buffer, 0, count);
            }
        }
    }

    private static byte[] readBounded(File file, int limit) throws Exception {
        if (file.length() > limit) throw new IllegalStateException("Database snapshot is too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IllegalStateException("Database snapshot is too large");
                out.write(buffer, 0, count);
            }
        }
        return out.toByteArray();
    }

    private AfsDatabase(Context context) {
        super(context, NAME, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.rawQuery("PRAGMA busy_timeout=5000", null).close();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.beginTransaction();
        try {
            db.execSQL("CREATE TABLE devices (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "device_uuid TEXT NOT NULL UNIQUE," +
                    "device_name TEXT NOT NULL," +
                    "android_version TEXT NOT NULL," +
                    "app_version TEXT NOT NULL," +
                    "enforcement_mode TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL)");

            db.execSQL("CREATE TABLE applications (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "package_name TEXT NOT NULL UNIQUE," +
                    "display_name TEXT NOT NULL," +
                    "category TEXT NOT NULL DEFAULT ''," +
                    "enabled INTEGER NOT NULL DEFAULT 1," +
                    "installed INTEGER NOT NULL DEFAULT 1," +
                    "last_enforcement_state INTEGER NOT NULL DEFAULT 0," +
                    "last_enforced_at INTEGER NOT NULL DEFAULT 0," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL)");

            db.execSQL("CREATE TABLE rules (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "application_id TEXT NOT NULL," +
                    "rule_uuid TEXT NOT NULL UNIQUE," +
                    "rule_type TEXT NOT NULL DEFAULT 'schedule'," +
                    "enabled INTEGER NOT NULL DEFAULT 1," +
                    "freeze_mode INTEGER NOT NULL DEFAULT 0," +
                    "gate INTEGER NOT NULL DEFAULT 1," +
                    "weekday_mask INTEGER NOT NULL DEFAULT 127," +
                    "priority INTEGER NOT NULL DEFAULT 0," +
                    "revision INTEGER NOT NULL DEFAULT 1," +
                    "added_at INTEGER NOT NULL DEFAULT 0," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "FOREIGN KEY(application_id) REFERENCES applications(id) ON DELETE CASCADE)");

            db.execSQL("CREATE TABLE time_windows (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "rule_id TEXT NOT NULL," +
                    "start_minute INTEGER NOT NULL," +
                    "end_minute INTEGER NOT NULL," +
                    "sort_order INTEGER NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "CHECK(start_minute >= 0 AND start_minute < 1440)," +
                    "CHECK(end_minute >= 0 AND end_minute < 1440)," +
                    "FOREIGN KEY(rule_id) REFERENCES rules(id) ON DELETE CASCADE)");

            db.execSQL("CREATE TABLE freeze_slots (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "label TEXT NOT NULL DEFAULT ''," +
                    "days INTEGER NOT NULL," +
                    "freeze_mode INTEGER NOT NULL," +
                    "schedule_key TEXT NOT NULL UNIQUE," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL)");

            db.execSQL("CREATE TABLE freeze_slot_members (" +
                    "slot_id TEXT NOT NULL," +
                    "application_id TEXT NOT NULL," +
                    "PRIMARY KEY(slot_id, application_id)," +
                    "FOREIGN KEY(slot_id) REFERENCES freeze_slots(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(application_id) REFERENCES applications(id) ON DELETE CASCADE)");

            db.execSQL("CREATE TABLE rule_revisions (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "rule_id TEXT NOT NULL," +
                    "revision INTEGER NOT NULL," +
                    "changed_at INTEGER NOT NULL," +
                    "old_digest TEXT NOT NULL," +
                    "new_digest TEXT NOT NULL," +
                    "source TEXT NOT NULL," +
                    "reason TEXT NOT NULL DEFAULT ''," +
                    "snapshot TEXT NOT NULL," +
                    "FOREIGN KEY(rule_id) REFERENCES rules(id) ON DELETE CASCADE)");

            db.execSQL("CREATE TABLE focus_sessions (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "session_type TEXT NOT NULL," +
                    "started_at INTEGER NOT NULL," +
                    "planned_end_at INTEGER NOT NULL," +
                    "ended_at INTEGER NOT NULL DEFAULT 0," +
                    "end_reason TEXT NOT NULL DEFAULT ''," +
                    "status TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL)");

            db.execSQL("CREATE TABLE enforcement_events (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "event_type TEXT NOT NULL," +
                    "application_id TEXT," +
                    "event_time INTEGER NOT NULL," +
                    "local_date TEXT NOT NULL," +
                    "timezone TEXT NOT NULL," +
                    "execution_result TEXT NOT NULL," +
                    "enforcement_method TEXT NOT NULL," +
                    "error_code TEXT NOT NULL DEFAULT ''," +
                    "metadata TEXT NOT NULL DEFAULT '{}'," +
                    "dedupe_key TEXT UNIQUE," +
                    "created_at INTEGER NOT NULL," +
                    "FOREIGN KEY(application_id) REFERENCES applications(id) ON DELETE SET NULL)");

            db.execSQL("CREATE TABLE daily_statistics (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "local_date TEXT NOT NULL," +
                    "timezone TEXT NOT NULL," +
                    "focus_duration INTEGER NOT NULL DEFAULT 0," +
                    "enforced_duration INTEGER NOT NULL DEFAULT 0," +
                    "blocked_attempts INTEGER NOT NULL DEFAULT 0," +
                    "successful_enforcements INTEGER NOT NULL DEFAULT 0," +
                    "failed_enforcements INTEGER NOT NULL DEFAULT 0," +
                    "updated_at INTEGER NOT NULL," +
                    "UNIQUE(local_date, timezone))");

            db.execSQL("CREATE TABLE schema_migrations (" +
                    "version INTEGER PRIMARY KEY NOT NULL," +
                    "migration_name TEXT NOT NULL," +
                    "applied_at INTEGER NOT NULL," +
                    "result TEXT NOT NULL," +
                    "checksum TEXT NOT NULL DEFAULT '')");

            db.execSQL("CREATE TABLE sync_outbox (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "entity_type TEXT NOT NULL," +
                    "entity_id TEXT NOT NULL," +
                    "operation TEXT NOT NULL," +
                    "entity_revision INTEGER NOT NULL," +
                    "payload_version INTEGER NOT NULL," +
                    "payload TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "retry_count INTEGER NOT NULL DEFAULT 0," +
                    "sync_status TEXT NOT NULL," +
                    "last_error TEXT NOT NULL DEFAULT ''," +
                    "next_attempt_at INTEGER NOT NULL DEFAULT 0)");

            db.execSQL("CREATE TABLE configuration (" +
                    "key TEXT PRIMARY KEY NOT NULL," +
                    "value TEXT NOT NULL," +
                    "value_type TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL)");

            db.execSQL("CREATE INDEX idx_applications_package ON applications(package_name)");
            db.execSQL("CREATE INDEX idx_rules_application ON rules(application_id)");
            db.execSQL("CREATE INDEX idx_windows_rule ON time_windows(rule_id, sort_order)");
            db.execSQL("CREATE INDEX idx_slot_members_slot ON freeze_slot_members(slot_id)");
            db.execSQL("CREATE INDEX idx_revisions_rule ON rule_revisions(rule_id, revision)");
            db.execSQL("CREATE INDEX idx_events_time ON enforcement_events(event_time)");
            db.execSQL("CREATE INDEX idx_events_date ON enforcement_events(local_date)");
            db.execSQL("CREATE INDEX idx_events_app_time ON enforcement_events(application_id, event_time)");
            db.execSQL("CREATE INDEX idx_outbox_status ON sync_outbox(sync_status, next_attempt_at)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first phase-three schema.  Future versions must add
        // explicit, tested migrations here; never drop user tables implicitly.
    }
}

