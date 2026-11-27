package com.afs.blocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import com.afs.core.rules.RuleBackupDocument;
import com.afs.core.rules.RuleJson;
import com.afs.core.rules.WeeklyRule;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashSet;
import java.util.Map;

final class RuleBackup {
    private RuleBackup() {}

    static RuleBackupDocument snapshot(Context context) {
        return new RuleBackupDocument("accessibility",Rules.all(context),java.util.Collections.emptySet(),
                Store.forceUntil(context),Store.startMin(context),Store.endMin(context));
    }

    static synchronized void restore(Context context,RuleBackupDocument document,boolean replace) throws Exception {
        if (!"accessibility".equals(document.variant)) throw new IllegalArgumentException("备份类型不符");
        Rules.requireEditable(context);
        RuleBackupDocument before = snapshot(context);
        File directory = new File(context.getFilesDir(),"rule-backups");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("无法创建恢复前备份");
        AtomicFile backup = new AtomicFile(new File(directory,"before-"+System.currentTimeMillis()+".json"));
        FileOutputStream output = backup.startWrite();
        try { output.write(before.encode().getBytes("UTF-8")); backup.finishWrite(output); }
        catch (Exception error) { backup.failWrite(output); throw error; }
        Rules.requireEditable(context);
        Map<String,WeeklyRule> merged = document.combined(before.rules,replace);
        SharedPreferences prefs = Store.sp(context);
        String original = prefs.getString(Rules.KEY,null);
        java.util.Set<String> targets = prefs.getStringSet("targets",null);
        boolean configured = Store.isConfigured(context);
        try {
            String encoded = RuleJson.encode(merged);
            Rules.requireEditable(context);
            if (!prefs.edit().putString(Rules.KEY,encoded).putStringSet("targets",new LinkedHashSet<>(merged.keySet()))
                    .putInt("start_min",document.start).putInt("end_min",document.end)
                    .putLong("force_until",Math.max(before.forceUntil,document.forceUntil)).putBoolean("configured",true).commit())
                throw new java.io.IOException("恢复写入失败");
        } catch (Exception error) {
            SharedPreferences.Editor rollback = prefs.edit().putInt("start_min",before.start).putInt("end_min",before.end)
                    .putLong("force_until",before.forceUntil).putBoolean("configured",configured);
            if (original == null) rollback.remove(Rules.KEY); else rollback.putString(Rules.KEY,original);
            if (targets == null) rollback.remove("targets"); else rollback.putStringSet("targets",targets);
            if (!rollback.commit()) throw new java.io.IOException("恢复及回滚均未写入成功；恢复前备份已保留",error);
            throw error;
        }
        BlockerService.recheck();
    }
}
