package com.afs.blocker;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class DiagnosticsActivity extends Activity {
    private LinearLayout body;

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this); body.setOrientation(1); body.setPadding(24,24,24,24);
        scroll.addView(body); setContentView(scroll);
    }

    private void row(String value) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(15);
        text.setPadding(0,18,0,18); body.addView(text);
    }

    private void button(String value, Runnable action) {
        Button button = new Button(this); button.setText(value); button.setOnClickListener(view -> action.run()); body.addView(button);
    }

    private void inspect() {
        body.removeAllViews(); row("系统健康诊断");
        row("不适用 · 此版本使用无障碍拦截，不需要设备所有者");
        row(RuntimeStatus.connected ? "正常 · 无障碍服务已连接" : "异常 · 无障碍服务未连接；请检查无障碍设置");
        row(RuntimeStatus.healthy() ? "正常 · 后台巡检近期运行正常" : "警告 · 尚未收到近期巡检；检查后台限制");
        row(Heal.serviceEnabled(this) ? "正常 · 系统已启用服务；连接状态单独核对" : "警告 · 系统没有启用服务");
        try {
            boolean binder = rikka.shizuku.Shizuku.pingBinder();
            row(binder ? "正常 · Shizuku Binder 已连接" : "警告 · Shizuku 未连接，无法强化停止应用");
            row(binder && rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ? "正常 · Shizuku 已授权" : "警告 · Shizuku 未授权或不可用，请到 Shizuku 授权");
        } catch (RuntimeException error) { row("警告 · 无法查询 Shizuku：" + error.getClass().getSimpleName()); }
        SharedPreferences runtime = getSharedPreferences("afs_runtime",MODE_PRIVATE);
        row(runtime.getLong("heal_scheduled",0) == 0 ? "警告 · 尚无成功排程记录"
                : "正常 · 系统已接受后备巡检排程：" + stamp(runtime.getLong("heal_scheduled",0))
                + "\n最近实际回调：" + stamp(runtime.getLong("heal_fired",0)));
        try {
            row(getPackageManager().getReceiverInfo(new ComponentName(this,HealReceiver.class),0).enabled
                    ? "正常 · 开机恢复已注册；最近回调：" + stamp(runtime.getLong("boot_at",0)) : "异常 · 开机恢复被禁用");
        } catch (android.content.pm.PackageManager.NameNotFoundException error) { row("异常 · 缺少开机恢复接收器"); }
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        row(power != null && power.isIgnoringBatteryOptimizations(getPackageName()) ? "正常 · 已忽略电池优化；厂商限制需另查"
                : "警告 · 电池优化可能延迟后台工作");
        try { row("正常 · 已完整解析 " + Rules.all(this).size() + " 个应用的规则"); }
        catch (IllegalStateException error) { row("异常 · " + error.getMessage()); }
        row("最后一次成功执行：" + stamp(runtime.getLong("last_success",0)) + "\n" + runtime.getString("last_action","尚未执行拦截"));
        row("最近执行失败：" + runtime.getString("last_failure","暂无记录"));
        button("重新运行诊断",this::inspect);
        button("无障碍设置",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("电池优化设置",()->startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
    }

    private String stamp(long at) {
        return at == 0 ? "暂无记录" : new java.text.SimpleDateFormat("M/d HH:mm:ss",java.util.Locale.CHINA).format(new java.util.Date(at));
    }
    protected void onResume() { super.onResume(); inspect(); }
}
