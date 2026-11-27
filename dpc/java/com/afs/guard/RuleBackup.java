package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import com.afs.core.rules.RuleBackupDocument;
import com.afs.core.rules.RuleJson;
import com.afs.core.rules.WeeklyRule;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

final class RuleBackup {
    private RuleBackup() {}

    static RuleBackupDocument snapshot(Context context) {
        Map<String,WeeklyRule> rules = new LinkedHashMap<>();
        for (Map.Entry<String,Rule> entry : RuleStore.all(context).entrySet()) {
            Rule rule = entry.getValue();
            rules.put(entry.getKey(),rule.freeze ? WeeklyRule.freeze(rule.days,rule.windows()) : WeeklyRule.allow(rule.days,rule.windows()));
        }
        return new RuleBackupDocument("device-owner",rules,RuleStore.keyApps(context),0,RuleStore.FALLBACK_START,RuleStore.FALLBACK_END);
    }

    static synchronized void restore(Context context, RuleBackupDocument document, boolean replace) throws Exception {
        if (!"device-owner".equals(document.variant)) throw new IllegalArgumentException("备份类型不符");
        if (!LocalBackup.mayRestore(context)) throw new IllegalStateException("当前锁定策略不允许覆盖规则；请在首次配置期恢复");
        RuleBackupDocument before = snapshot(context);
        String externalBefore = ExternalAdjustment.snapshot(context);
        File directory = new File(context.getFilesDir(),"rule-backups");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("无法创建恢复前备份");
        AtomicFile backup = new AtomicFile(new File(directory,"before-"+System.currentTimeMillis()+".json"));
        FileOutputStream output = backup.startWrite();
        try { output.write(before.encode().getBytes("UTF-8")); backup.finishWrite(output); }
        catch (Exception error) { backup.failWrite(output); throw error; }
        if (!LocalBackup.mayRestore(context)) throw new IllegalStateException("锁定状态已变化，恢复未开始");
        Map<String,WeeklyRule> combined = document.combined(before.rules,replace);
        SharedPreferences prefs = context.getSharedPreferences("afs_guard",Context.MODE_PRIVATE);
        String original = prefs.getString("rules","{}");
        JSONObject old = new JSONObject(original), next = new JSONObject();
        for (Map.Entry<String,WeeklyRule> entry : combined.entrySet()) {
            JSONObject rule = RuleJson.write(entry.getValue());
            boolean gate = document.rules.containsKey(entry.getKey()) ? !document.keyApps.contains(entry.getKey())
                    : !before.keyApps.contains(entry.getKey());
            rule.put("g",gate);
            JSONObject previous = old.optJSONObject(entry.getKey());
            if (previous != null && previous.has("at")) rule.put("at",previous.getLong("at"));
            next.put(entry.getKey(),rule);
        }
        DailyQuotaStore.beforeScheduleChange(context);
        if (!LocalBackup.mayRestore(context)) throw new IllegalStateException("锁定状态已变化，恢复未开始");
        try {
            if (!prefs.edit().putString("rules",next.toString()).commit()) throw new java.io.IOException("规则写入失败");
            if (Guard.isOwner(context)) {
                Guard.apply(context);
                if (!context.getSharedPreferences("afs_status",Context.MODE_PRIVATE).getBoolean("last_apply_ok",false))
                    throw new java.io.IOException("系统未完整接受恢复后的规则");
            }
            Hub.markConfigDirty(context);
            if (ExternalAdjustment.recordIfChanged(context, externalBefore, T.s("规则备份"))) {
                Guard.applyAsync(context);
            }
        } catch (Exception error) {
            if (!prefs.edit().putString("rules",original).commit()) throw new java.io.IOException("恢复及回滚均未写入成功；恢复前备份已保留",error);
            Guard.applyAsync(context);
            throw error;
        }
    }
}
