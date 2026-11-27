package com.afs.blocker;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import com.afs.core.rules.RuleBackupDocument;
import com.afs.core.rules.RuleJson;
import com.afs.core.rules.WeeklyRule;
import com.afs.test.FailingContext;

import java.util.Collections;

public final class Phase1DeviceTest extends Instrumentation {
    private int checks;
    private boolean requireShizuku;
    private static final String FIXTURE = "com.afs.phase1.fixture";
    private void check(boolean value,String label) { if (!value) throw new AssertionError(label); checks++; }
    public void onCreate(Bundle state) { requireShizuku = "true".equals(state.getString("require_shizuku")); start(); }

    private String shell(String command) throws Exception {
        android.os.ParcelFileDescriptor file = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command);
        try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(file)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer,0,count);
            return output.toString("UTF-8");
        }
    }

    public void onStart() {
        Context context = getTargetContext(); Bundle result = new Bundle(); SharedPreferences prefs = Store.sp(context);
        boolean success = false;
        try {
            prefs.edit().remove(Rules.KEY).putStringSet("targets",Collections.singleton(FIXTURE))
                    .putInt("start_min",1320).putInt("end_min",60).putBoolean("configured",false).putLong("force_until",0).commit();
            WeeklyRule legacy = Rules.all(context).get(FIXTURE);
            check(legacy != null && legacy.startAt(0) == 1320 && !legacy.freezeMode(),"legacy global window becomes app rule");
            String raw = prefs.getString(Rules.KEY,""); Rules.all(context);
            check(raw.equals(prefs.getString(Rules.KEY,"")),"blocker migration is idempotent");
            check(prefs.contains("targets") && prefs.contains("rules_before_migration"),"old keys and migration snapshot retained");
            prefs.edit().putString(Rules.KEY,"corrupt").commit();
            try { Rules.all(context); throw new AssertionError("corrupt rules accepted"); }
            catch (IllegalStateException expected) { checks++; }
            check(prefs.getString(Rules.KEY,"").equals("corrupt"),"corruption is not overwritten");
            prefs.edit().putString(Rules.KEY,raw).commit();
            WeeklyRule always = WeeklyRule.allow(127,new int[][]{{0,0}});
            Rules.put(context,FIXTURE,always);
            RuleBackupDocument backup = RuleBackup.snapshot(context);
            RuleBackup.restore(context,backup,true);
            check(Rules.all(context).size() == 1,"blocker backup roundtrip");
            String original = prefs.getString(Rules.KEY,"");
            try { RuleBackup.restore(new FailingContext(context,"afs_blocker"),backup,true); throw new AssertionError("failed restore accepted"); }
            catch (java.io.IOException expected) { checks++; }
            check(prefs.getString(Rules.KEY,"").equals(original),"blocker failed restore rolled back");
            Store.startForce(context,1500L);
            try { Store.setForceUntil(context,0); throw new AssertionError("force shortened"); }
            catch (IllegalStateException expected) { checks++; }
            try { RuleBackup.restore(context,backup,true); throw new AssertionError("locked restore accepted"); }
            catch (IllegalStateException expected) { checks++; }
            SystemClock.sleep(1600L);
            check(!Rules.blocked(context,FIXTURE,System.currentTimeMillis()),"force expiry returns to app schedule");
            shell("settings put secure accessibility_enabled 0");
            shell("settings delete secure enabled_accessibility_services");
            SystemClock.sleep(300L);
            shell("settings put secure enabled_accessibility_services com.afs.blocker/.BlockerService");
            shell("settings put secure accessibility_enabled 1");
            for (int attempt = 0; attempt < 40 && !RuntimeStatus.healthy(); attempt++) SystemClock.sleep(200L);
            check(RuntimeStatus.healthy(),"actual accessibility connection and heartbeat");
            if (requireShizuku) {
                shell("am start -n com.afs.blocker/.MainActivity");
                boolean binder = false;
                for (int attempt = 0; attempt < 40; attempt++) {
                    ShizukuBridge.ensureBinder(context);
                    binder = (Boolean)Class.forName("rikka.shizuku.Shizuku").getMethod("pingBinder").invoke(null);
                    if (binder) break;
                    SystemClock.sleep(200L);
                }
                check(binder,"Shizuku binder delivered to active client");
            }
            Rules.put(context,FIXTURE,WeeklyRule.freeze(127,new int[][]{{0,0}}));
            shell("am start -n com.afs.phase1.fixture/android.app.Activity");
            boolean exited = false;
            for (int attempt = 0; attempt < 30; attempt++) {
                SystemClock.sleep(200L);
                String activities = shell("dumpsys activity activities");
                java.util.regex.Matcher resumed = java.util.regex.Pattern.compile("topResumedActivity[^\\n]*").matcher(activities);
                if (resumed.find() && !resumed.group().contains(FIXTURE)) { exited = true; break; }
            }
            check(exited,"blocked fixture actually exits to desktop");
            check(context.getSharedPreferences("afs_runtime",Context.MODE_PRIVATE).getLong("last_success",0) > 0,"real HOME success recorded");
            if (requireShizuku) {
                boolean stopped = false;
                for (int attempt = 0; attempt < 30; attempt++) {
                    SystemClock.sleep(200L);
                    String detail = context.getSharedPreferences("afs_runtime",Context.MODE_PRIVATE).getString("last_action","");
                    if (detail.contains("强化停止") && detail.contains("exit=0")) { stopped = true; break; }
                }
                check(stopped,"actual Shizuku force-stop returned success");
                check(shell("dumpsys package " + FIXTURE).contains("stopped=true"),"system package flag confirms force-stop");
            }
            try { Rules.put(context,FIXTURE,always); throw new AssertionError("locked editor accepted"); }
            catch (IllegalStateException expected) { checks++; }
            result.putString("stream","PHASE1 BLOCKER DEVICE: " + checks + " checks passed\n"); success = true;
        } catch (Throwable error) {
            java.io.StringWriter trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream",trace.toString());
        } finally {
            try { prefs.edit().putString(Rules.KEY,RuleJson.encode(Collections.singletonMap(FIXTURE,WeeklyRule.allow(127,new int[][]{{0,0}})))).putLong("force_until",0).commit(); }
            catch (Exception ignored) {}
        }
        finish(success ? -1 : 0,result);
    }
}
