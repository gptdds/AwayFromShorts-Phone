package com.afs.guard;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.afs.guard.data.AfsRepositories;

import java.util.ArrayList;
import java.util.List;

public final class DiagnosticsActivity extends ThemedActivity {
    private LinearLayout body;
    private Ui ui;
    private int generation;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();

    protected void onCreate(Bundle state) {
        super.onCreate(state); ui = new Ui(this);
        ScrollView scroll = new ScrollView(this); ui.page(scroll);
        body = new LinearLayout(this); body.setOrientation(1);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32)); scroll.addView(body);
        setContentView(scroll);
    }

    private void line(String value) {
        TextView text = ui.sub(value); text.setPadding(0, ui.dp(12), 0, ui.dp(12)); body.addView(text);
    }

    private void button(String value, Runnable action) {
        Button button = new Button(this); button.setText(value); button.setOnClickListener(view -> action.run()); body.addView(button);
    }

    private void runDiagnosis() {
        int token = ++generation;
        body.removeAllViews(); body.addView(ui.title(T.s("系统健康诊断"))); line(T.s("正在读取系统状态…"));
        worker.execute(() -> {
            List<String> rows;
            try { rows = inspect(); }
            catch (Exception error) { rows = java.util.Collections.singletonList(T.s("异常 · 诊断未完成：") + error.getClass().getSimpleName()); }
            final List<String> report = rows;
            runOnUiThread(() -> {
                if (token != generation || isFinishing() || isDestroyed()) return;
                body.removeAllViews(); body.addView(ui.title(T.s("系统健康诊断")));
                for (String value : report) line(value);
                button(T.s("重新运行诊断"), this::runDiagnosis);
                button(T.s("重新应用当前规则"), () -> { Guard.applyAsync(this); line(T.s("已提交执行；稍后重新运行诊断查看结果")); });
                button(T.s("清理 30 天前的执行事件"), () -> {
                    line(T.s("正在清理旧事件…"));
                    worker.execute(() -> {
                        try {
                            int removed = AfsRepositories.get(getApplicationContext())
                                    .eventRepository().deleteOlderThan(
                                            System.currentTimeMillis()
                                                    - 30L * 24L * 3600_000L);
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    line(T.f("已清理 %d 条旧执行事件", removed));
                                }
                            });
                        } catch (Throwable error) {
                            runOnUiThread(() -> {
                                if (!isFinishing() && !isDestroyed()) {
                                    line(T.s("清理失败：")
                                            + error.getClass().getSimpleName());
                                }
                            });
                        }
                    });
                });
                button(T.s("无障碍设置"), () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                button(T.s("电池优化设置"), () -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
                button(T.s("使用情况访问设置"), () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            });
        });
    }

    private List<String> inspect() throws Exception {
        List<String> rows = new ArrayList<>();
        boolean owner = Guard.isOwner(this);
        rows.add(owner ? T.s("正常 · 设备所有者已授权") : T.s("异常 · 尚未取得设备所有者，系统冻结不可用；请按部署说明授权"));
        rows.add(KeywordGuardService.connected() ? T.s("正常 · 关键词无障碍服务已连接")
                : T.s("警告 · 关键词无障碍服务未连接；到无障碍设置检查。此服务不影响系统冻结权限"));
        rows.add(T.s("不适用 · 此版本使用设备管理执行冻结；Shizuku 执行器属于无障碍版"));
        rows.add(GuardService.healthy() ? T.s("正常 · 后台守护已连接且近期巡检正常")
                : T.s("异常 · 未收到近期守护巡检；检查后台限制并重新打开应用"));
        SharedPreferences health = getSharedPreferences("afs_health", MODE_PRIVATE);
        long scheduled = health.getLong("alarm_request_at", 0L);
        rows.add(scheduled > 0L ? T.s("正常 · 定时请求已被系统接受：") + Guard.stamp(scheduled)
                + T.s("\n计划检查：") + Guard.stamp(health.getLong("alarm_due", 0L))
                + T.s("\n最近实际回调：") + stamp(health.getLong("alarm_fired", 0L))
                : T.s("警告 · 尚无成功排程记录；可重新应用当前规则"));
        String alarmError = health.getString("alarm_error", "");
        if (!alarmError.isEmpty()) rows.add(T.s("异常 · 定时失败：") + alarmError);
        AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 31 && (alarm == null || !alarm.canScheduleExactAlarms()))
            rows.add(T.s("警告 · 未允许精确闹钟；系统可能延迟切换，请在应用特殊权限中授权"));
        JobScheduler scheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
        boolean job = false;
        if (scheduler != null) for (JobInfo value : scheduler.getAllPendingJobs())
            if (value.getService().getClassName().equals(GuardJob.class.getName())) job = true;
        rows.add(job ? T.s("正常 · 后备任务在系统队列中") : T.s("警告 · 系统队列中没有后备任务"));
        boolean boot = getPackageManager().getReceiverInfo(new ComponentName(this, BootReceiver.class), 0).enabled;
        rows.add(boot ? T.s("正常 · 开机恢复接收器已注册；最近开机回调：") + stamp(health.getLong("boot_at", 0L))
                : T.s("异常 · 开机恢复接收器被禁用"));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        rows.add(power != null && power.isIgnoringBatteryOptimizations(getPackageName())
                ? T.s("正常 · 已忽略系统电池优化；厂商后台限制需另外检查")
                : T.s("警告 · 电池优化可能延迟后台任务；请检查电池优化设置"));
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        rows.add(notifications != null && notifications.areNotificationsEnabled()
                ? T.s("正常 · 通知权限可用") : T.s("警告 · 通知被关闭，可能看不到守护状态"));
        rows.add(AppUsageQuota.permission(this) ? T.s("正常 · 使用情况访问可用") : T.s("警告 · 使用情况访问未授权，时间限额无法正常计时"));
        int checked = 0, mismatch = 0;
        if (owner) {
            for (String pkg : Guard.enforcedRules(this).keySet()) {
                if (!Guard.installed(getPackageManager(), pkg)) continue;
                boolean expected = Guard.blockReasonNow(this, pkg) != 0;
                boolean actual = Guard.dpm(this).isPackageSuspended(Guard.admin(this), pkg)
                        || Guard.dpm(this).isApplicationHidden(Guard.admin(this), pkg);
                checked++; if (expected != actual) mismatch++;
            }
            rows.add(mismatch == 0 ? T.f("正常 · 已核对 %d 个已安装应用，系统状态与规则一致", checked)
                    : T.f("异常 · %d 个应用的系统状态与规则不一致；可重新应用当前规则", mismatch));
        } else rows.add(T.s("不适用 · 没有设备管理权限，无法核对系统冻结状态"));
        SharedPreferences status = getSharedPreferences("afs_status", MODE_PRIVATE);
        rows.add(T.s("最后一次完整成功执行：") + stamp(status.getLong("last_success_at", 0L)));
        String error = status.getString("last_error", "");
        rows.add(error.isEmpty() ? T.s("正常 · 尚无已记录的执行失败") : T.s("警告 · 最近执行失败：") + error);
        try {
            AfsRepositories repositories = AfsRepositories.get(this);
            String migration = repositories.migrationResult();
            int rules = repositories.ruleRepository().all().size();
            int pending = repositories.outbox().getPendingOperations().size();
            if ("SUCCESS".equals(migration) || "SKIPPED".equals(migration)) {
                rows.add(T.f("正常 · 本地数据库迁移 %s；规则 %d 条；待同步变更 %d 条",
                        migration, rules, pending));
            } else if ("FAILED".equals(migration)) {
                SharedPreferences data = getSharedPreferences("afs_data_status",
                        MODE_PRIVATE);
                String detail = data.getString("phase3_migration_error", "");
                rows.add(T.s("异常 · 本地数据库迁移失败；冻结仍由原规则执行")
                        + (detail.isEmpty() ? "" : T.s("\n原因：") + detail));
            } else {
                rows.add(T.s("警告 · 本地数据库迁移尚未完成；冻结仍由原规则执行"));
            }
        } catch (Throwable databaseError) {
            rows.add(T.s("异常 · 本地数据库不可用：")
                    + databaseError.getClass().getSimpleName());
        }
        return rows;
    }

    private String stamp(long at) { return at == 0L ? T.s("暂无记录") : Guard.stamp(at); }
    protected void onResume() { super.onResume(); runDiagnosis(); }
    protected void onDestroy() { generation++; worker.shutdownNow(); super.onDestroy(); }
}
