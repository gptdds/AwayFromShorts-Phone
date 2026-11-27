package com.afs.guard.pluginqa;

import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.afs.guard.Guard;
import com.afs.guard.MasterSwitch;
import com.afs.guard.NetBlockService;
import com.afs.guard.TimeWindow;

import java.util.Calendar;

/** Verifies that every master-switch state change is gated by the adjustment window. */
public final class MasterSwitchDeviceTest extends Instrumentation {
    private int checks;

    private void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = 1;
        Context target = getTargetContext();
        SharedPreferences master = target.getSharedPreferences("afs_master_switch", Context.MODE_PRIVATE);
        SharedPreferences window = target.getSharedPreferences("afs_time_window", Context.MODE_PRIVATE);
        SharedPreferences guard = target.getSharedPreferences("afs_guard", Context.MODE_PRIVATE);
        boolean originalEnabled = MasterSwitch.enabled(target);
        boolean enabledExisted = master.contains("enabled");
        boolean startExisted = window.contains("start");
        boolean endExisted = window.contains("end");
        boolean clearedExisted = window.contains("cleared");
        boolean defaultExisted = window.contains("default_22_23");
        boolean armedExisted = guard.contains("armed");
        int originalStart = window.getInt("start", TimeWindow.DEFAULT_START);
        int originalEnd = window.getInt("end", TimeWindow.DEFAULT_END);
        boolean originalCleared = window.getBoolean("cleared", false);
        boolean originalDefault = window.getBoolean("default_22_23", false);
        boolean originalArmed = guard.getBoolean("armed", false);
        try {
            check(guard.edit().putBoolean("armed", true).commit(), "grace period disabled for QA");
            Calendar now = Calendar.getInstance();
            int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            check(writeWindow(window, (minute + 10) % 1440, (minute + 20) % 1440),
                    "outside-window fixture saved");
            check(writeMaster(master, true), "enabled fixture saved");
            check(!MasterSwitch.canEdit(target), "master switch is read-only outside the window");
            check(!MasterSwitch.setEnabled(target, false), "cannot switch off outside the window");
            check(MasterSwitch.enabled(target), "blocked off request preserves enabled state");
            check(writeMaster(master, false), "disabled fixture saved");
            check(MasterSwitch.canEdit(target), "disabled master can be opened outside the window");
            check(MasterSwitch.setEnabled(target, true), "can switch on outside the window");
            check(MasterSwitch.enabled(target), "on state persists");

            check(writeWindow(window, 0, 0), "always-open adjustment fixture saved");
            check(MasterSwitch.canEdit(target), "master switch is editable inside the window");
            check(MasterSwitch.setEnabled(target, false), "can switch off inside the window");
            check(!MasterSwitch.enabled(target), "off state persists");
            check(TimeWindow.start(target) == 0 && TimeWindow.end(target) == 0,
                    "master off clears the effective window");
            check(window.getInt("start", -1) == 0 && window.getInt("end", -1) == 0
                            && window.getBoolean("cleared", false),
                    "cleared window persists as 00:00-00:00");

            // 模拟旧版本遗留的 suspended 集合：总开关关闭后必须按设备策略释放，
            // 不能只依赖 RuleStore.applied。
            DevicePolicyManager dpm = (DevicePolicyManager) target.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = Guard.admin(target);
            String stale = getContext().getPackageName();
            check(dpm != null, "device policy service available");
            Thread.sleep(500);
            String[] refused = dpm.setPackagesSuspended(admin, new String[] { stale }, true);
            check(refused == null || refused.length == 0, "stale suspended fixture accepted");
            check(dpm.isPackageSuspended(admin, stale), "stale fixture is suspended by AFS");
            Guard.apply(target);
            check(!dpm.isPackageSuspended(admin, stale),
                    "master off releases packages left suspended by policy");

            check(MasterSwitch.setEnabled(target, true), "can switch on while cleared");
            check(MasterSwitch.enabled(target), "on state persists after clear");
            check(TimeWindow.start(target) == 0 && TimeWindow.end(target) == 60,
                    "master on restores the 00:00-01:00 default");
            result.putString("stream", "Master switch device: " + checks + " checks passed\n");
            code = 0;
        } catch (Throwable error) {
            result.putString("stream", "FAILED " + android.util.Log.getStackTraceString(error));
        } finally {
            restoreBoolean(master, "enabled", enabledExisted, originalEnabled);
            restoreInt(window, "start", startExisted, originalStart);
            restoreInt(window, "end", endExisted, originalEnd);
            restoreBoolean(window, "cleared", clearedExisted, originalCleared);
            restoreBoolean(window, "default_22_23", defaultExisted, originalDefault);
            restoreBoolean(guard, "armed", armedExisted, originalArmed);
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) target.getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (dpm != null) {
                    dpm.setPackagesSuspended(Guard.admin(target),
                            new String[] { getContext().getPackageName() }, false);
                }
            } catch (Throwable ignored) {
            }
            Guard.applyAsync(target);
            NetBlockService.reconcile(target);
        }
        finish(code, result);
    }

    private static boolean writeWindow(SharedPreferences prefs, int start, int end) {
        return prefs.edit().putInt("start", start).putInt("end", end)
                .putBoolean("cleared", false)
                .putBoolean("default_22_23", true).commit();
    }

    private static boolean writeMaster(SharedPreferences prefs, boolean enabled) {
        return prefs.edit().putBoolean("enabled", enabled).commit();
    }

    private static void restoreBoolean(SharedPreferences prefs, String key,
                                       boolean existed, boolean value) {
        SharedPreferences.Editor edit = prefs.edit();
        if (existed) edit.putBoolean(key, value); else edit.remove(key);
        edit.commit();
    }

    private static void restoreInt(SharedPreferences prefs, String key,
                                   boolean existed, int value) {
        SharedPreferences.Editor edit = prefs.edit();
        if (existed) edit.putInt(key, value); else edit.remove(key);
        edit.commit();
    }
}
