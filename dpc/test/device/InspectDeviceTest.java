package com.afs.guard.pluginqa;

import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.afs.guard.AppUsageQuota;
import com.afs.guard.Guard;
import com.afs.guard.MasterSwitch;
import com.afs.guard.Rule;
import com.afs.guard.RuleStore;
import com.afs.guard.TimeWindow;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/** Read-only inspection of the current rule, quota and suspension state. */
public final class InspectDeviceTest extends Instrumentation {
    private static final String GOOGLE_SEARCH = "com.google.android.googlequicksearchbox";
    private Bundle args;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        args = state;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = 1;
        Context c = getTargetContext();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("args=").append(args == null ? "null" : args.toString()).append('\n');
            sb.append("master=").append(MasterSwitch.enabled(c)).append('\n');
            sb.append("window=").append(TimeWindow.start(c)).append('-').append(TimeWindow.end(c)).append('\n');
            sb.append("owner=").append(Guard.isOwner(c)).append('\n');

            Map<String, Rule> rules = RuleStore.all(c);
            Set<String> applied = RuleStore.applied(c);
            Set<String> keys = RuleStore.keyApps(c);
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            Class<?> quotaStore = Class.forName("com.afs.guard.DailyQuotaStore");
            Method snapshot = quotaStore.getDeclaredMethod("snapshot", Context.class);
            snapshot.setAccessible(true);
            Object quota = snapshot.invoke(null, c);
            Method groupOf = quota.getClass().getDeclaredMethod("group", String.class);
            groupOf.setAccessible(true);
            Method usedOf = quota.getClass().getDeclaredMethod("used", org.json.JSONObject.class);
            usedOf.setAccessible(true);
            Method statusOf = quotaStore.getDeclaredMethod("status", Context.class, String.class);
            statusOf.setAccessible(true);
            String[] packages = {
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.apps.bard",
                    "com.google.android.gm",
                    "com.android.chrome",
                    "tv.danmaku.bili"
            };
            for (String pkg : packages) {
                Rule rule = rules.get(pkg);
                sb.append(pkg).append('\n');
                sb.append("  inRules=").append(rule != null)
                        .append(" applied=").append(applied.contains(pkg))
                        .append(" key=").append(keys.contains(pkg)).append('\n');
                if (rule != null) {
                    sb.append("  schedule=").append(rule.freezeSummary())
                            .append(" days=").append(rule.days)
                            .append(" gate=").append(rule.gate).append('\n');
                }
                sb.append("  usageQuota=").append(AppUsageQuota.minutes(c, pkg)).append('\n');
                Object group = groupOf.invoke(quota, pkg);
                if (group instanceof org.json.JSONObject) {
                    org.json.JSONObject json = (org.json.JSONObject) group;
                    sb.append("  quotaGroup=").append(json.toString()).append('\n');
                    sb.append("  quotaUsed=").append(usedOf.invoke(quota, json)).append('\n');
                }
                sb.append("  quotaStatus=").append(statusOf.invoke(null, c, pkg)).append('\n');
                int reason = Guard.blockReasonNow(c, pkg);
                sb.append("  reason=").append(reason).append(" (")
                        .append(Guard.blockReasonText(reason)).append(")\n");
                boolean suspended = false;
                boolean hidden = false;
                try {
                    suspended = dpm != null && dpm.isPackageSuspended(Guard.admin(c), pkg);
                    hidden = dpm != null && dpm.isApplicationHidden(Guard.admin(c), pkg);
                } catch (Throwable ignored) {
                }
                sb.append("  suspended=").append(suspended)
                        .append(" hidden=").append(hidden).append('\n');
            }

            boolean fix = args != null
                    && ("true".equals(args.getString("fix_gemini"))
                    || args.getBoolean("fix_gemini", false));
            if (fix) {
                sb.append(fixGemini(c, snapshot, quotaStore, groupOf, statusOf));
            }
            android.util.Log.i("AFSInspect", sb.toString());
            result.putString("stream", sb.toString());
            code = 0;
        } catch (Throwable error) {
            result.putString("stream", "FAILED " + android.util.Log.getStackTraceString(error));
        }
        finish(code, result);
    }

    /**
     * One-off repair: Google Search is only being frozen because it was added to a
     * shared daily-quota cell. Gemini delegates its entry point to Google Search, so
     * the cell makes Gemini unusable. Remove only that package and keep a backup.
     */
    private static String fixGemini(Context c, Method snapshot, Class<?> quotaStore, Method groupOf,
                                    Method statusOf) throws Exception {
        SharedPreferences p = c.getSharedPreferences("afs_daily_quota", Context.MODE_PRIVATE);
        String before = p.getString("config", "{}");
        org.json.JSONObject config = new org.json.JSONObject(before);
        org.json.JSONArray groups = config.optJSONArray("groups");
        boolean removed = false;
        String from = "";
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                org.json.JSONObject group = groups.optJSONObject(i);
                if (group == null) continue;
                org.json.JSONArray apps = group.optJSONArray("apps");
                if (apps == null) continue;
                boolean found = contains(apps, GOOGLE_SEARCH);
                if (!found) continue;
                from = group.optString("name");
                remove(apps, GOOGLE_SEARCH);
                org.json.JSONArray background = group.optJSONArray("background");
                if (background != null) remove(background, GOOGLE_SEARCH);
                removed = true;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("fixGemini.removed=").append(removed)
                .append(" from=").append(from).append('\n');
        if (removed) {
            Class<?> data = Class.forName("com.afs.guard.DailyQuotaData");
            Method validate = data.getDeclaredMethod("validate", org.json.JSONObject.class);
            validate.setAccessible(true);
            validate.invoke(null, config);
            if (!p.edit()
                    .putString("gemini_fix_backup_config", before)
                    .putLong("gemini_fix_backup_at", System.currentTimeMillis())
                    .putString("config", config.toString())
                    .commit()) {
                throw new IllegalStateException("config backup/write failed");
            }
            java.lang.reflect.Field refreshed = quotaStore.getDeclaredField("refreshed");
            refreshed.setAccessible(true);
            refreshed.setLong(null, 0L);
            java.lang.reflect.Field signature = quotaStore.getDeclaredField("signature");
            signature.setAccessible(true);
            signature.set(null, "");
            android.util.Log.i("AFSInspect", "gemini fix: config written");
        } else {
            sb.append("fixGemini.configAlreadyFixed=true\n");
        }
        Object group = groupOf.invoke(snapshot.invoke(null, c), GOOGLE_SEARCH);
        sb.append("fixGemini.afterGroup=").append(group == null ? "none" : group.toString()).append('\n');
        sb.append("fixGemini.status=").append(statusOf.invoke(null, c, GOOGLE_SEARCH)).append('\n');
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        String[] failed = dpm.setPackagesSuspended(Guard.admin(c), new String[] { GOOGLE_SEARCH }, false);
        sb.append("fixGemini.directRelease=")
                .append(failed == null ? "ok" : java.util.Arrays.toString(failed)).append('\n');
        android.util.Log.i("AFSInspect", "gemini fix: direct release done");
        Guard.applyAsync(c);
        android.util.Log.i("AFSInspect", "gemini fix: async apply queued");
        return sb.toString();
    }

    private static boolean contains(org.json.JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i))) return true;
        }
        return false;
    }

    private static void remove(org.json.JSONArray array, String value) {
        for (int i = array.length() - 1; i >= 0; i--) {
            if (value.equals(array.optString(i))) array.remove(i);
        }
    }
}
