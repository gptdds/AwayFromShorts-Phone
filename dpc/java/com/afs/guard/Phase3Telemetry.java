package com.afs.guard;

import android.content.Context;
import android.util.Log;

import com.afs.guard.data.AfsDatabase;
import com.afs.guard.data.AfsRepositories;
import com.afs.guard.data.DataRecords;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Bridges verified platform actions into the phase-three repository.
 *
 * All writes run on the database worker.  Failure here is diagnostic only and
 * must never change the result of an enforcement call.
 */
public final class Phase3Telemetry {
    private static final String TAG = "AFSPhase3";

    public static final String APP_SUSPENDED = "APP_SUSPENDED";
    public static final String APP_UNSUSPENDED = "APP_UNSUSPENDED";
    public static final String APP_BLOCKED = "APP_BLOCKED";
    public static final String ENFORCEMENT_FAILED = "ENFORCEMENT_FAILED";
    public static final String FOCUS_STARTED = "FOCUS_STARTED";
    public static final String FOCUS_ENDED = "FOCUS_ENDED";
    public static final String SERVICE_RECOVERED = "SERVICE_RECOVERED";

    private Phase3Telemetry() {
    }

    public static void enforcementTransition(Context context, String pkg,
                                             boolean wasBlocked, boolean nowBlocked,
                                             String method, String trigger) {
        if (wasBlocked == nowBlocked) return;
        long eventTime = System.currentTimeMillis();
        String eventType = nowBlocked ? APP_SUSPENDED : APP_UNSUSPENDED;
        run(context, repositories -> {
            DataRecords.AppRecord app = ensureApplication(repositories, pkg);
            repositories.applications().setEnforcementState(pkg, nowBlocked, eventTime);
            record(repositories, context, eventType, app.id, eventTime, "VERIFIED",
                    method, "", metadata("desired", nowBlocked, "previous", wasBlocked,
                            "trigger", trigger), eventType + "|" + pkg + "|" + eventTime);
            increment(context, repositories, eventTime, 0L, 0, 1, 0);
        });
    }

    public static void enforcementFailure(Context context, String pkg,
                                          boolean desiredBlocked, String method,
                                          String errorCode) {
        long eventTime = System.currentTimeMillis();
        run(context, repositories -> {
            DataRecords.AppRecord app = ensureApplication(repositories, pkg);
            record(repositories, context, ENFORCEMENT_FAILED, app.id, eventTime, "FAILED",
                    method, errorCode == null ? "" : errorCode,
                    metadata("desired", desiredBlocked, "trigger", "apply"),
                    ENFORCEMENT_FAILED + "|" + pkg + "|" + desiredBlocked + "|"
                            + (eventTime / 600_000L));
            increment(context, repositories, eventTime, 0L, 0, 0, 1);
        });
    }

    public static void blockedAttempt(Context context, String pkg, int reason,
                                      String method) {
        long eventTime = System.currentTimeMillis();
        run(context, repositories -> {
            DataRecords.AppRecord app = ensureApplication(repositories, pkg);
            record(repositories, context, APP_BLOCKED, app.id, eventTime, "BLOCKED",
                    method, "", metadata("reason", reason),
                    APP_BLOCKED + "|" + pkg + "|" + reason + "|" + (eventTime / 60_000L));
            increment(context, repositories, eventTime, 0L, 1, 0, 0);
        });
    }

    public static void startFocus(Context context, long plannedEndAt) {
        long eventTime = System.currentTimeMillis();
        run(context, repositories -> {
            if (repositories.focusRepository().active() != null) return;
            repositories.focusRepository().start("focus", plannedEndAt);
            record(repositories, context, FOCUS_STARTED, null, eventTime, "STARTED",
                    "session", "", metadata("planned_end_at", plannedEndAt),
                    FOCUS_STARTED + "|" + eventTime);
        });
    }

    public static void finishFocus(Context context, long endedAt, String reason,
                                   String status) {
        finishFocus(context, endedAt, reason, status, true);
    }

    public static void finishFocus(Context context, long endedAt, String reason,
                                   String status, boolean countDuration) {
        run(context, repositories -> {
            DataRecords.FocusSessionRecord session = repositories.focusRepository().active();
            if (session == null) return;
            long safeEnd = Math.max(session.startedAt, endedAt);
            long duration = countDuration
                    ? Math.min(24L * 3600_000L, safeEnd - session.startedAt) : 0L;
            repositories.focusRepository().finish(session.id, safeEnd, reason, status);
            record(repositories, context, FOCUS_ENDED, null, safeEnd, "COMPLETED",
                    "session", "",
                    metadata("duration_ms", duration, "reason", reason, "status", status),
                    FOCUS_ENDED + "|" + session.id);
            increment(context, repositories, safeEnd, duration, 0, 0, 0);
        });
    }

    public static void serviceRecovered(Context context) {
        long eventTime = System.currentTimeMillis();
        run(context, repositories -> record(repositories, context, SERVICE_RECOVERED,
                null, eventTime, "VERIFIED", "device_owner", "", new JSONObject(),
                SERVICE_RECOVERED + "|" + (eventTime / 86_400_000L)));
    }

    private static DataRecords.AppRecord ensureApplication(AfsRepositories repositories,
                                                            String pkg) {
        DataRecords.AppRecord app = repositories.applications().find(pkg);
        return app != null ? app : repositories.applications().upsert(pkg, pkg, true);
    }

    private static void record(AfsRepositories repositories, Context context,
                               String eventType, String applicationId, long eventTime,
                               String result, String method, String errorCode,
                               JSONObject metadata, String dedupeKey) throws Exception {
        TimeZone zone = GuardClock.pin(context);
        SimpleDateFormat localFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        localFormat.setTimeZone(zone);
        String localDate = localFormat.format(new Date(eventTime));
        metadata.put("quota_date", DayBoundary.key(context, eventTime));
        repositoryRecord(repositories, eventType, applicationId, eventTime, localDate,
                zone.getID(), result, method, errorCode, metadata, dedupeKey);
    }

    private static void repositoryRecord(AfsRepositories repositories, String eventType,
                                         String applicationId, long eventTime,
                                         String localDate, String timezone,
                                         String result, String method, String errorCode,
                                         JSONObject metadata, String dedupeKey) {
        repositories.eventRepository().record(new DataRecords.EventRecord(
                eventType, applicationId, eventTime, localDate, timezone, result,
                method, errorCode, metadata.toString(), dedupeKey));
    }

    private static void increment(Context context, AfsRepositories repositories,
                                  long eventTime, long focusDuration, int blocked,
                                  int success, int failed) {
        TimeZone zone = GuardClock.pin(context);
        SimpleDateFormat localFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        localFormat.setTimeZone(zone);
        String localDate = localFormat.format(new Date(eventTime));
        repositories.statistics().increment(localDate, zone.getID(), focusDuration, 0L,
                blocked, success, failed, System.currentTimeMillis());
    }

    private static JSONObject metadata(Object... pairs) {
        JSONObject out = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                out.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        } catch (Exception error) {
            throw new IllegalStateException("Event metadata could not be serialized", error);
        }
        return out;
    }

    private static void run(Context context, Work work) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) return;
        AfsDatabase.runAsync(() -> {
            try {
                work.run(AfsRepositories.get(app));
            } catch (Throwable error) {
                Log.w(TAG, "telemetry write failed", error);
            }
        });
    }

    private interface Work {
        void run(AfsRepositories repositories) throws Exception;
    }
}
