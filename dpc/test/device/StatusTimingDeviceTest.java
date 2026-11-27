package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Verifies that StatusActivity crosses the penalty boundary without showing stale phase text. */
public final class StatusTimingDeviceTest extends Instrumentation {
    private int checks;
    private Activity current;

    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private String texts(View root, StringBuilder out) {
        if (root instanceof TextView) out.append(((TextView)root).getText()).append('\n');
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)root;
            for (int i = 0; i < group.getChildCount(); i++) texts(group.getChildAt(i), out);
        }
        return out.toString();
    }

    private void waitForTick(long millis) {
        SystemClock.sleep(millis);
        runOnMainSync(() -> {});
    }

    @Override public void onCreate(Bundle args) {
        start();
    }

    @Override public void onStart() {
        Context context = getTargetContext();
        SharedPreferences rulesPrefs = context.getSharedPreferences("afs_guard", Context.MODE_PRIVATE);
        SharedPreferences penaltyPrefs = context.getSharedPreferences(StudyGate.TABOO_SP, Context.MODE_PRIVATE);
        String originalRules = rulesPrefs.getString("rules", "{}");
        String originalPenalties = penaltyPrefs.getString("schedule_penalties", "{}");
        long originalHoldUntil = penaltyPrefs.getLong("hold_until", 0L);
        Bundle result = new Bundle();
        Throwable failure = null;
        String pkg = "com.google.android.calendar";
        try {
            long now = System.currentTimeMillis();
            long from = now + 60_000L;
            long until = from + 30 * 60_000L;
            JSONObject rules = new JSONObject(originalRules);
            JSONArray windows = new JSONArray().put(new JSONArray().put(0).put(840));
            rules.put(pkg, new JSONObject().put("d", 127).put("g", false)
                    .put("freeze", true).put("w", windows));
            check(rulesPrefs.edit().putString("rules", rules.toString()).commit(), "rules fixture committed");

            JSONObject penalties = new JSONObject();
            penalties.put(pkg, new JSONObject().put("from", from)
                    .put("until", until).put("src", PenaltyStore.SRC_CHECKIN));
            check(penaltyPrefs.edit().putString("schedule_penalties", penalties.toString())
                    .putLong("hold_until", until)
                    .putBoolean("penalty_schedule_v1", true).commit(),
                    "penalty fixture committed");

            current = startActivitySync(new Intent(context, StatusActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            waitForTick(1_300L);
            TextView state = (TextView)field(current, "linkageState");
            TextView detail = (TextView)field(current, "linkageDetail");
            check("待接续".contentEquals(state.getText()),
                    "before boundary state is waiting, was " + state.getText());
            check(detail.getText().toString().contains("已安排"), "before boundary detail is scheduled");
            check(detail.getText().toString().matches("(?s).*\\d{2}:\\d{2}:\\d{2}.*"),
                    "before boundary detail includes seconds");

            long crossingFrom = System.currentTimeMillis() + 2_500L;
            long crossingUntil = crossingFrom + 30 * 60_000L;
            penalties.put(pkg, new JSONObject().put("from", crossingFrom)
                    .put("until", crossingUntil).put("src", PenaltyStore.SRC_CHECKIN));
            check(penaltyPrefs.edit().putString("schedule_penalties", penalties.toString())
                    .putLong("hold_until", crossingUntil).commit(),
                    "moving boundary committed without recreating the page");
            waitForTick(1_200L);
            state = (TextView)field(current, "linkageState");
            check("待接续".contentEquals(state.getText()),
                    "new boundary is still pending while observed");
            waitForTick(4_000L);
            state = (TextView)field(current, "linkageState");
            detail = (TextView)field(current, "linkageDetail");
            check("生效中".contentEquals(state.getText()),
                    "after boundary state becomes active, was " + state.getText());
            check(detail.getText().toString().contains("已生效"), "after boundary detail is active");
            check(!detail.getText().toString().contains("已安排"), "after boundary stale scheduled text is gone");
            String expectedEnd = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date(crossingUntil));
            check(detail.getText().toString().contains(expectedEnd), "active detail includes delayed end time");

            View cells = (View)field(current, "appCells");
            String appText = texts(cells, new StringBuilder());
            check(appText.contains("Calendar") || appText.contains("日历"), "fixture app has its own status cell");
            check(appText.contains("打卡追加"), "app status explains the delayed freeze");
            check(appText.contains("生效中"), "app status advances to active state");
        } catch (Throwable error) {
            failure = error;
        } finally {
            rulesPrefs.edit().putString("rules", originalRules).commit();
            penaltyPrefs.edit().putString("schedule_penalties", originalPenalties)
                    .putLong("hold_until", originalHoldUntil).commit();
            if (current != null) {
                final Activity activity = current;
                runOnMainSync(activity::finish);
            }
        }
        if (failure == null) {
            result.putString("stream", "STATUS TIMING DEVICE: " + checks
                    + " assertions passed; original rules and penalties restored\n");
            finish(-1, result);
        } else {
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream", trace.toString());
            finish(0, result);
        }
    }
}
