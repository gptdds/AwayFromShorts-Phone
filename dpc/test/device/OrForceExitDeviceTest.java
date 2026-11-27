package com.afs.guard;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 互斥格首次到点的弹窗提醒：真机行为只在这里用一次性模拟器验证。
 *
 * 冻结只判断“该不该冻”，提醒只判断“到点要不要弹一次，且不把前台应用请出去”；
 * 两条路径分开断言，避免一个开关同时改变冻结语义。
 */
public final class OrForceExitDeviceTest extends Instrumentation {
    private int checks;

    private void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }

    private void shell(String command) throws Exception {
        try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[1024];
            while (in.read(buffer) >= 0) { }
        }
    }

    private String topPackage() {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        try { return root.getPackageName() == null ? null : root.getPackageName().toString(); }
        finally { root.recycle(); }
    }

    private boolean hasText(AccessibilityNodeInfo node, String needle) {
        if (node == null) return false;
        CharSequence text = node.getText();
        if (text != null && text.toString().contains(needle)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (hasText(child, needle)) return true; } finally { child.recycle(); }
        }
        return false;
    }

    private boolean waitForText(String needle, long ms) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + ms;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            try {
                if (hasText(root, needle)) return true;
            } finally {
                if (root != null) root.recycle();
            }
            Thread.sleep(120);
        }
        return false;
    }

    private void restorePrefs(SharedPreferences prefs, Map<String, ?> backup) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : backup.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
        }
        editor.commit();
    }

    private String waitTop(String expected, String fallback, long ms) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + ms;
        String last = topPackage();
        while (android.os.SystemClock.elapsedRealtime() < until) {
            last = topPackage();
            if (last != null && (last.equals(expected) || last.equals(fallback))) return last;
            Thread.sleep(120);
        }
        return last;
    }

    private void fixture(SharedPreferences prefs, JSONObject config, JSONObject usage) {
        check(prefs.edit().putBoolean("recount_full_day_v1", true)
                .putString("config", config.toString())
                .putString("usage", usage.toString()).commit(), "fixture saved");
    }

    private void refresh(Context c) throws Exception {
        Method method = DailyQuotaStore.class.getDeclaredMethod("refresh", Context.class, boolean.class);
        method.setAccessible(true);
        method.invoke(null, c, true);
    }

    private JSONObject group(String id, String name, int minutes, String pkg, String or) throws Exception {
        JSONObject group = new JSONObject().put("id", id).put("name", name)
                .put("minutes", minutes).put("apps", new JSONArray(Arrays.asList(pkg)));
        if (or != null) group.put("orGroup", or);
        return group;
    }

    private JSONObject config(JSONObject... groups) throws Exception {
        return new JSONObject().put("enabled", true).put("groups", new JSONArray(Arrays.asList(groups)));
    }

    private JSONObject ledger(long now, boolean exceeded) throws Exception {
        JSONObject usage = DailyQuotaData.fresh(now);
        usage.optJSONObject("targets").put("com.afs.quota.one", exceeded ? 600000L : 0L)
                .put("com.afs.quota.two", 0L);
        if (exceeded) {
            usage.put("or_choices", new JSONObject().put("orx", "ora"));
            usage.put("or_choices_meta", new JSONObject().put("orx",
                    new JSONObject().put("first_use", now - DailyQuotaData.OR_FORGIVENESS_MS - 60000L)));
        }
        usage.put("lastAt", now);
        return usage;
    }

    /** 把测试夹具拉到前台：只在应用没有被 suspend 的情况下调用。 */
    private void foreground(Context c, String pkg) throws Exception {
        c.startActivity(new android.content.Intent().setClassName(pkg, "com.afs.quota.fixture.QuotaFixture")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        String top = waitTop(pkg, c.getPackageName(), 8000);
        check(pkg.equals(top), "quota fixture enters foreground " + pkg + " (top=" + top + ")");
    }

    /** 等 5 秒采样缓存里出现这只应用；锁机节拍读的就是这份缓存。 */
    private void warmForegroundCache(Context c, String pkg) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 10000;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (pkg.equals(AppUsageQuota.foregroundCached(c, GuardClock.now(c).getTimeInMillis()))) return;
            Thread.sleep(300);
        }
        throw new AssertionError("foreground cache never observed " + pkg);
    }

    @Override
    public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override
    public void onStart() {
        Context c = getTargetContext();
        String a = "com.afs.quota.one", b = "com.afs.quota.two";
        SharedPreferences prefs = c.getSharedPreferences(DailyQuotaStore.SP, 0);
        Map<String, ?> backup = new HashMap<>(prefs.getAll());
        SharedPreferences alertPrefs = c.getSharedPreferences("afs_alert", 0);
        Map<String, ?> alertBackup = new HashMap<>(alertPrefs.getAll());
        try {
            check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"), "emulator required");
            check(Guard.isOwner(c), "actual device owner available");
            alertPrefs.edit().clear().commit();
            shell("svc power stayon true");
            shell("input keyevent KEYCODE_WAKEUP");
            shell("wm dismiss-keyguard");
            Thread.sleep(600);

            long now = GuardClock.now(c).getTimeInMillis();
            JSONObject plain = config(group("ora", "格A", 120, a, null), group("orb", "格B", 120, b, null));
            fixture(prefs, plain, ledger(now, false));
            refresh(c);
            Guard.apply(c);
            check(!DailyQuotaStore.exceeded(c, a) && !DailyQuotaStore.exceeded(c, b), "clean fixture is not frozen");

            // 1) 仅冻结：同组其他成员已过 5 分钟，冻结但必须留在前台，也不能弹提醒。
            JSONObject freezeOnly = config(group("ora", "格A", 120, a, "orx"), group("orb", "格B", 120, b, "orx"));
            DailyQuotaData.setOrForceExit(freezeOnly, "orx", false);
            foreground(c, b);
            fixture(prefs, freezeOnly, ledger(now, true));
            refresh(c);
            check(DailyQuotaStore.exceeded(c, b), "freeze-only still freezes the other member");
            check(!DailyQuotaStore.orReminder(c, b, now), "freeze-only option disables the reminder");
            warmForegroundCache(c, b);
            Guard.remindOrFirstConflict(c);
            Thread.sleep(1500);
            check(b.equals(topPackage()), "freeze-only leaves the foreground app in place");

            // 2) 默认开启：同一条件下弹一次提醒，不把前台应用请到阻断页。
            fixture(prefs, plain, ledger(now, false));
            refresh(c);
            Guard.apply(c);
            check(!DailyQuotaStore.exceeded(c, b), "release before the second pass");
            foreground(c, b);
            JSONObject forceOn = config(group("ora", "格A", 120, a, "orx"), group("orb", "格B", 120, b, "orx"));
            fixture(prefs, forceOn, ledger(now, true));
            refresh(c);
            check(DailyQuotaStore.orReminder(c, b, now), "default option has a due reminder");
            warmForegroundCache(c, b);
            Guard.remindOrFirstConflict(c);
            check(waitForText("互斥格子首次提醒", 8000), "reminder popup is shown");
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            try {
                check(hasText(root, "本次不会强制退出"), "reminder states that the app is not forced out");
                check(!hasText(root, "今天的额度用完了"), "mutual exclusion does not open the quota blocker");
            } finally {
                if (root != null) root.recycle();
            }
            shell("input keyevent 4");
            String top = waitTop(b, c.getPackageName(), 6000);
            check(b.equals(top), "dismissing the reminder returns to the foreground app (top=" + top + ")");
            check(DailyQuotaStore.snapshot(c).group(b).optString("id").equals("orb")
                    && DailyQuotaStore.snapshot(c).orChoice("orx").equals("ora"),
                    "reminder does not rewrite today's choice");
            check(DailyQuotaStore.snapshot(c).orRemindedAt("orx") > 0L,
                    "reminder records the once-per-day marker");
            check(DailyQuotaStore.snapshot(c).orForceExitedAt("orx") > 0L,
                    "old ledger field remains compatible");
            check(!DailyQuotaStore.claimOrReminder(c, b, GuardClock.now(c).getTimeInMillis()),
                    "the once-per-day claim rejects a second reminder");
            Guard.remindOrFirstConflict(c);
            Thread.sleep(1200);
            check(b.equals(topPackage()), "a second tick leaves the foreground app untouched");
            DailyQuotaData reloaded = new DailyQuotaData(prefs.getString("config", "{}"),
                    prefs.getString("usage", "{}"), GuardClock.now(c).getTimeInMillis());
            check(reloaded.orRemindedAt("orx") > 0L, "reminder marker survives a reload");

            // 3) 组内选项必须一致：把一只成员改成仅冻结后整组都应是仅冻结。
            JSONObject mixed = config(group("ora", "格A", 120, a, "orx"), group("orb", "格B", 120, b, "orx"));
            DailyQuotaData.setOrForceExit(mixed, "orx", false);
            DailyQuotaData.setOrForceExitMinutes(mixed, "orx", 2);
            JSONArray groups = mixed.optJSONArray("groups");
            check(!DailyQuotaData.orForceExit(groups.optJSONObject(0))
                    && !DailyQuotaData.orForceExit(groups.optJSONObject(1))
                    && DailyQuotaData.orForceExitMinutes(groups.optJSONObject(0)) == 2
                    && DailyQuotaData.orForceExitMinutes(groups.optJSONObject(1)) == 2,
                    "exit option and delay stay in sync across the group");
        } catch (AssertionError error) {
            throw error;
        } catch (Exception error) {
            throw new AssertionError("or first reminder device test failed: " + error);
        } finally {
            try {
                restorePrefs(prefs, backup);
                restorePrefs(alertPrefs, alertBackup);
                refresh(c);
                Guard.apply(c);
            } catch (Exception ignored) { }
            Bundle result = new Bundle();
            result.putString("stream", "Or first reminder device: " + checks + " checks passed\n");
            finish(android.app.Activity.RESULT_OK, result);
        }
    }
}
