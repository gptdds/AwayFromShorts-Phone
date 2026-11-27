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

import com.afs.core.rules.RuleBackupDocument;
import com.afs.test.FailingContext;

import java.util.Collections;

public final class Phase1DeviceTest extends Instrumentation {
    private int checks;
    private static final String FIXTURE = "com.afs.phase1.fixture";
    private void check(boolean value,String label) { if (!value) throw new AssertionError(label); checks++; }
    public void onCreate(Bundle state) { start(); }

    private String texts(View view) {
        StringBuilder output = new StringBuilder();
        if (view instanceof TextView) output.append(((TextView)view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int index = 0; index < group.getChildCount(); index++) output.append(texts(group.getChildAt(index)));
        }
        return output.toString();
    }

    public void onStart() {
        Context context = getTargetContext(); Bundle result = new Bundle(); Activity activity = null;
        boolean success = false;
        SharedPreferences prefs = context.getSharedPreferences("afs_guard",Context.MODE_PRIVATE);
        try {
            check(Guard.isOwner(context),"device owner available");
            prefs.edit().remove("rules").putStringSet("targets",Collections.singleton(FIXTURE))
                    .putInt("days",31).putInt("start_min",0).putInt("end_min",0)
                    .putLong("first_seen",System.currentTimeMillis()).putBoolean("armed",false).commit();
            Rule migrated = RuleStore.all(context).get(FIXTURE);
            check(migrated != null && migrated.days == 31 && !migrated.freeze,"legacy DO mode and weekdays retained");
            check(prefs.contains("targets") && prefs.contains("rules_before_migration"),"migration keeps old data and snapshot");
            String raw = prefs.getString("rules",""); RuleStore.all(context);
            check(raw.equals(prefs.getString("rules","")),"DO repeated migration is idempotent");
            RuleStore.put(context,FIXTURE,new Rule(127,new int[][]{{0,0}},false,false));
            RuleBackupDocument allow = RuleBackup.snapshot(context);
            RuleBackup.restore(context,allow,true);
            check(RuleStore.count(context) == 1,"restore roundtrip retains app");
            String original = prefs.getString("rules","");
            try {
                RuleBackup.restore(new FailingContext(context,"afs_guard"),allow,true);
                throw new AssertionError("failed write accepted");
            } catch (java.io.IOException expected) { checks++; }
            check(original.equals(prefs.getString("rules","")),"DO failed restore rolled back exact raw rules");
            check(new java.io.File(context.getFilesDir(),"rule-backups").list().length > 0,"pre-restore backup exists");
            prefs.edit().putBoolean("armed",true).commit();
            try { RuleBackup.restore(context,allow,true); throw new AssertionError("locked import accepted"); }
            catch (IllegalStateException expected) { checks++; }
            RuleStore.put(context,FIXTURE,new Rule(127,new int[][]{{0,0}},false,true)); Guard.apply(context);
            check(Guard.dpm(context).isPackageSuspended(Guard.admin(context),FIXTURE),"actual system suspension applied");
            check(Guard.suspendedCount(context) == 1,"actual frozen count reads the system");
            RuleStore.put(context,FIXTURE,new Rule(127,new int[][]{{0,0}},false,false)); Guard.apply(context);
            check(!Guard.dpm(context).isPackageSuspended(Guard.admin(context),FIXTURE),"schedule edit immediately releases fixture");
            check(Guard.nextScheduledChange(context) == 0,"all-day release shows no invented countdown");
            check(context.getSharedPreferences("afs_status",Context.MODE_PRIVATE).getLong("last_success_at",0L) > 0,"actual execution timestamp recorded");
            activity = startActivitySync(new Intent(context,DiagnosticsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity opened = activity;
            String[] content = {""};
            for (int attempt = 0; attempt < 30; attempt++) {
                SystemClock.sleep(100L);
                runOnMainSync(() -> content[0] = texts(opened.findViewById(android.R.id.content)));
                if (content[0].contains("最后一次完整成功执行")) break;
            }
            check(content[0].contains("系统健康诊断") && content[0].contains("系统状态与规则一致"),"diagnosis uses actual system comparison");
            result.putString("stream","PHASE1 DPC DEVICE: " + checks + " checks passed\n"); success = true;
        } catch (Throwable error) {
            java.io.StringWriter trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream",trace.toString());
        } finally {
            if (activity != null) { Activity opened = activity; runOnMainSync(opened::finish); }
        }
        finish(success ? -1 : 0,result);
    }
}
