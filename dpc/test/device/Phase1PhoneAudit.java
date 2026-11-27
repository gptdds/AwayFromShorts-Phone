package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Phase1PhoneAudit extends Instrumentation {
    private String stage;
    private int checks;

    public void onCreate(Bundle arguments) { stage = arguments.getString("stage","before"); start(); }

    private void check(boolean value,String label) { if (!value) throw new AssertionError(label); checks++; }

    private Object call(Context context,String type,String name,Class<?>[] parameters,Object... arguments) throws Exception {
        Method method = Class.forName(type,true,context.getClassLoader()).getDeclaredMethod(name,parameters);
        method.setAccessible(true);
        try { return method.invoke(null,arguments); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception)error.getCause();
            throw error;
        }
    }

    private String hash(String value) throws Exception {
        StringBuilder output = new StringBuilder();
        for (byte part : MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8")))
            output.append(String.format(java.util.Locale.ROOT,"%02x",part & 255));
        return output.toString();
    }

    private String canonical(Object value) throws Exception {
        if (value instanceof Map) {
            List<String> keys = new ArrayList<>(((Map<String,?>)value).keySet()); Collections.sort(keys);
            StringBuilder output = new StringBuilder("{");
            for (String key : keys) output.append(JSONObject.quote(key)).append(':').append(canonical(((Map<?,?>)value).get(key))).append(',');
            return output.append('}').toString();
        }
        if (value instanceof Set) {
            List<String> parts = new ArrayList<>();
            for (Object part : (Set<?>)value) parts.add(String.valueOf(part));
            Collections.sort(parts); return new JSONArray(parts).toString();
        }
        return String.valueOf(value);
    }

    private void write(File target,String text) throws Exception {
        try (OutputStream output = new FileOutputStream(target)) { output.write(text.getBytes("UTF-8")); }
    }

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
        Bundle result = new Bundle(); boolean passed = false; Activity activity = null;
        try {
            Context context = getTargetContext();
            check("before".equals(stage) || "after".equals(stage),"valid audit stage");
            File folder = new File(context.getExternalFilesDir(null),"phase1-" + stage);
            check(folder.isDirectory() || folder.mkdirs(),"audit output available");
            File backup = new File(folder,"local-backup.zip");
            try (OutputStream output = new FileOutputStream(backup)) {
                call(context,"com.afs.guard.LocalBackup","export",new Class<?>[]{Context.class,OutputStream.class},context,output);
            }
            check(backup.length() > 100L,"full local backup exported");
            SharedPreferences prefs = context.getSharedPreferences("afs_guard",Context.MODE_PRIVATE);
            String raw = prefs.getString("rules","{}"); JSONObject rules = new JSONObject(raw);
            JSONObject fingerprints = new JSONObject();
            String[] fields = {"rules","freeze_slots","schedule_timezone","first_seen","armed","last_rule"};
            for (String key : fields) fingerprints.put("afs_guard/" + key,hash(canonical(prefs.getAll().get(key))));
            fingerprints.put("afs_daily_quota/config",hash(context.getSharedPreferences("afs_daily_quota",0).getString("config","")));
            for (String name : new String[]{"afs_ui","afs_tool_shelf","afs_learning_link","afs_time_window","afs_net_window","afs_day_boundary"})
                fingerprints.put(name,hash(canonical(context.getSharedPreferences(name,0).getAll())));
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),0);
            DevicePolicyManager policy = (DevicePolicyManager)context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            check(policy != null && policy.isDeviceOwnerApp(context.getPackageName()),"device owner retained");
            ComponentName admin = new ComponentName(context,"com.afs.guard.AfsAdmin");
            JSONObject audit = new JSONObject().put("stage",stage).put("versionName",info.versionName)
                    .put("versionCode",info.versionCode).put("firstInstallTime",info.firstInstallTime)
                    .put("deviceOwner",true).put("scheduledApps",rules.length()).put("configHashes",fingerprints)
                    .put("backupBytes",backup.length());
            Map<String,?> enforced = (Map<String,?>)call(context,"com.afs.guard.Guard","enforcedRules",new Class<?>[]{Context.class},context);
            JSONArray mismatches = new JSONArray(); int installed = 0, actualBlocked = 0;
            for (String pkg : enforced.keySet()) {
                PackageInfo app;
                try { app = context.getPackageManager().getPackageInfo(pkg,android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES); }
                catch (android.content.pm.PackageManager.NameNotFoundException missing) { continue; }
                if ((app.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_INSTALLED) == 0) continue;
                installed++;
                boolean actual = policy.isPackageSuspended(admin,pkg) || policy.isApplicationHidden(admin,pkg);
                if (actual) actualBlocked++;
                int reason = (Integer)call(context,"com.afs.guard.Guard","blockReasonNow",new Class<?>[]{Context.class,String.class},context,pkg);
                if (actual != (reason != 0)) mismatches.put(new JSONObject().put("package",pkg).put("reason",reason).put("actualBlocked",actual));
            }
            audit.put("enforcedApps",enforced.size()).put("installedManagedApps",installed).put("actualBlocked",actualBlocked)
                    .put("mismatches",mismatches);
            if ("after".equals(stage)) {
                Object document = call(context,"com.afs.guard.RuleBackup","snapshot",new Class<?>[]{Context.class},context);
                String json = (String)document.getClass().getMethod("encode").invoke(document);
                JSONObject config = new JSONObject(json);
                check(config.getJSONObject("rules").getJSONObject("rules").length() == rules.length(),"new JSON export retains every scheduled app");
                write(new File(folder,"rules-backup.json"),json);
                boolean mayRestore = (Boolean)call(context,"com.afs.guard.LocalBackup","mayRestore",new Class<?>[]{Context.class},context);
                if (!mayRestore) {
                    boolean refused = false;
                    try { call(context,"com.afs.guard.RuleBackup","restore",new Class<?>[]{Context.class,document.getClass(),boolean.class},context,document,true); }
                    catch (IllegalStateException expected) { refused = true; }
                    check(refused,"locked phone rejects rule restore without modifying data");
                    check(raw.equals(prefs.getString("rules","")),"rejected import preserves exact rules");
                }
                long next = (Long)call(context,"com.afs.guard.Guard","nextScheduledChange",new Class<?>[]{Context.class},context);
                check(next == 0L || next > System.currentTimeMillis(),"countdown points to a future rule transition");
                audit.put("nextScheduledChange",next);
                Class<?> diagnostics = Class.forName("com.afs.guard.DiagnosticsActivity",true,context.getClassLoader());
                activity = startActivitySync(new Intent(context,diagnostics).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Activity opened = activity; String[] content = {""};
                for (int attempt = 0; attempt < 100; attempt++) {
                    SystemClock.sleep(100L);
                    runOnMainSync(() -> content[0] = texts(opened.findViewById(android.R.id.content)));
                    if (content[0].contains("最后一次完整成功执行") || content[0].contains("诊断未完成")) break;
                }
                write(new File(folder,"diagnostics.txt"),content[0]);
                check(content[0].contains("最后一次完整成功执行"),"native diagnosis finishes on the connected phone");
                check(content[0].contains("系统状态与规则一致"),"diagnosis confirms actual system policy matches the rules");
            }
            audit.put("checks",checks); write(new File(folder,"audit.json"),audit.toString(2));
            result.putString("stream","PHASE1 PHONE " + stage + ": " + checks + " checks passed; scheduled="
                    + rules.length() + "; managed=" + enforced.size() + "; actualBlocked=" + actualBlocked
                    + "; mismatches=" + mismatches.length() + "\n");
            passed = true;
        } catch (Throwable error) {
            java.io.StringWriter trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream",trace.toString());
        } finally {
            if (activity != null) { Activity opened = activity; runOnMainSync(opened::finish); }
        }
        finish(passed ? -1 : 0,result);
    }
}
