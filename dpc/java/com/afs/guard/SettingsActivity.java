package com.afs.guard;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.afs.guard.plugins.DockPages;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/**
 * 设置：外观、语言、权限体检、检查更新、隐私说明。额度设置在冻结页的「时间限额」里。
 *
 * 这一页主要做查看和跳转；「冻结前提醒」是自律区里的独立快捷开关和提前量设置。
 * 每个权限项都显示真实状态，跳不过去的（设备所有者）直说要走部署包，不给假按钮。
 */
public final class SettingsActivity extends ThemedActivity {

    private Ui ui;
    private LinearLayout body;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean destroyed;
    private TextView updateLine;
    private TextView pluginLine;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        FloatingNav.fullscreen(this);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        // 从系统设置页返回后状态会变，重画一次，不让用户看旧勾。
        if (body != null) render();
        try { com.afs.host.plugins.HostRuntime.get(this).registry().refreshAsync(() -> {
            if (!destroyed && pluginLine != null) pluginLine.setText(pluginSummary());
        }); }
        catch (RuntimeException ignored) { }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void render() {
        Bundle position = PageScrollState.capture(this);
        ScrollView scroll = new ScrollView(this);
        ui.page(scroll);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(36));
        scroll.addView(body);

        body.addView(header());
        body.addView(ui.title(Lang.t(this, "设置", "Settings")));

        /* ---------- 外观 ---------- */
        body.addView(groupTitle(Lang.t(this, "外观", "Appearance")));
        LinearLayout look = ui.card();
        body.addView(look, ui.gap(10));
        row(look, Lang.t(this, "主题", "Theme"), themeSummary(), () ->
                startActivity(new Intent(this, ThemeActivity.class)));
        row(look, Lang.t(this, "开屏动画", "Opening animation"),
                StartupPreferences.enabled(this) ? T.s("已开启") : T.s("已关闭"),
                this::toggleStartupAnimation);
        row(look, Lang.t(this, "界面语言", "Language"), Lang.modeName(this), this::pickLanguage);

        /* ---------- 自律 ---------- */
        body.addView(groupTitle(Lang.t(this, "自律", "Self-control")));
        LinearLayout control = ui.card();
        body.addView(control, ui.gap(10));
        row(control, Lang.t(this, "总开关", "Master switch"),
                MasterSwitch.enabled(this)
                        ? T.s("已开启 · 仅调整时段可修改")
                        : T.s("已关闭 · 调整时段已清零（可预设）"),
                this::toggleMasterSwitch);
        row(control, Lang.t(this, "自律规则", "Self-control rules"), ControlActivity.summary(this),
                () -> startActivity(new Intent(this, ControlActivity.class)));
        row(control, T.s("全局按星期停用"), FreezeWeekdayPolicy.summary(this),
                () -> FreezeWeekdayPolicyDialog.show(this, ui, this::render));
        row(control, T.s("AI/外部调整"), AiAdjustmentStore.summary(this),
                this::showAiAdjustmentStats);
        row(control, T.s("学习联动绑定"), LearningBindings.summary(this),
                this::editLearningBindings);
        row(control, T.s("每日时间限额重置时间"), DayBoundary.summary(this),
                this::editQuotaResetTime);
        row(control, T.s("亮屏时间上限"), ScreenOnLimit.summary(this),
                this::editScreenOnLimit);
        row(control, T.s("冻结前提醒"), FreezeWarn.enabled(this) ? T.s("已开启") : T.s("已关闭"),
                this::toggleFreezeWarn);
        row(control, T.s("提醒提前量"), T.f("%d 分钟", FreezeWarn.minutes(this)),
                this::editFreezeWarnMinutes);
        row(control, Lang.t(this, "调整时段", "Adjustment window"), TimeWindow.summary(this),
                () -> TimeWindow.pick(this, this::render));
        row(control, Lang.t(this, "断网调整时段", "Cut-off adjustment window"), NetWindow.summary(this),
                () -> {
                    // 时段本身也是设置：能随便改时段就等于绕过了断网的锁。
                    if (NetWindow.canEdit(this)) NetWindow.pick(this, this::render);
                });

        /* ---------- 应用 ---------- */
        body.addView(groupTitle(Lang.t(this, "应用", "App")));
        LinearLayout app = ui.card();
        body.addView(app, ui.gap(10));
        pluginLine = row(app, Lang.t(this, "插件管理", "Plugins"), pluginSummary(),
                () -> startActivity(new Intent(this, com.afs.host.plugins.PluginManagerActivity.class)));
        row(app, Lang.t(this, "成就", "Achievements"), AchievementCenter.summary(this),
                () -> startActivity(new Intent(this, AchievementActivity.class)));
        row(app, Lang.t(this, "数据与备份", "Data & backup"),
                Lang.t(this, "本地存储 / 云端配置", "Local storage / cloud sync"),
                () -> startActivity(new Intent(this, DataManagementActivity.class)));

        /* ---------- 跨设备 ---------- */
        body.addView(groupTitle(Lang.t(this, "跨设备", "Across devices")));
        LinearLayout cross = ui.card();
        body.addView(cross, ui.gap(10));
        row(cross, Lang.t(this, "共享额度", "Shared quota"), lanSummary(), this::lanQuotaEditor);

        /* ---------- 权限 ---------- */
        body.addView(groupTitle(Lang.t(this, "权限体检", "Permissions")));
        LinearLayout perms = ui.card();
        body.addView(perms, ui.gap(10));
        row(perms, T.s("部署与权限检查"), "›", () -> SetupGuide.show(this));
        row(perms,T.s("系统健康诊断"),"›",()->startActivity(new Intent(this,DiagnosticsActivity.class)));
        permRow(perms, T.s("设备所有者"), Guard.isOwner(this), Guard.isOwner(this)
                ? T.s("冻结与锁机的基础能力")
                : T.s("需要部署包授权，系统设置里开不了"), null);
        permRow(perms, T.s("使用情况访问"), AppUsageQuota.permission(this),
                T.s("时间限额、用量统计要用；不授权就不计时"), () ->
                        open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        permRow(perms, T.s("通知"), notifications(), Lang.t(this,
                "守卫常驻状态与备忘录提醒（定时提醒不占通知）",
                "Guard status and memo reminders (rest reminders never post notifications)"), () -> {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            open(intent);
        });
        permRow(perms, T.s("精确闹钟"), exactAlarm(), T.s("边界到点才能准点执行"), () -> {
            if (Build.VERSION.SDK_INT >= 31) {
                open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName())));
            } else {
                toast(T.s("当前系统版本不需要单独授权"));
            }
        });
        permRow(perms, T.s("忽略电池优化"), batteryFree(), T.s("否则后台守卫生存时间会被压缩"), () -> {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            open(intent);
        });
        permRow(perms, T.s("无障碍服务"), Capability.accessibilityOn(this),
                T.s("应用内关键词拦截要用；开了才读得到屏幕文字"), () ->
                        open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permRow(perms, T.s("能力等级"), Capability.level(this) >= Capability.ACCESSIBILITY,
                Capability.summary(this) + T.s(" · 点这一行切换"), () -> Capability.pick(this));

        /* ---------- 关于 ---------- */
        body.addView(groupTitle(Lang.t(this, "关于", "About")));
        LinearLayout about = ui.card();
        body.addView(about, ui.gap(10));
        row(about, Lang.t(this, "版本", "Version"), version(), null);
        updateLine = new TextView(this);
        updateLine.setText(Lang.t(this, "点下面检查更新", "Tap below to check for updates"));
        updateLine.setTextSize(12);
        updateLine.setTextColor(ui.text3());
        updateLine.setPadding(0, ui.dp(6), 0, ui.dp(10));
        about.addView(updateLine);
        row(about, Lang.t(this, "检查更新", "Check for updates"), "›", this::checkUpdate);
        row(about, Lang.t(this, "隐私说明", "Privacy"), "›", this::showPrivacy);

        setContentView(scroll);
        PageScrollState.restore(this, position);
    }

    private void editQuotaResetTime() {
        if (!requireQuotaEditWindow()) return;
        android.app.TimePickerDialog picker = new android.app.TimePickerDialog(this, (view, hour, minute) -> {
            if (!requireQuotaEditWindow()) return;
            if (!DayBoundary.saveTime(this, hour, minute)) {
                toast(T.s("重置时间未能保存，请重试"));
                return;
            }
            toast(DayBoundary.summary(this));
            render();
        }, DayBoundary.selectedHour(this), DayBoundary.selectedMinute(this), true);
        picker.setTitle(T.s("每日时间限额重置时间"));
        picker.setMessage(T.s("新时间在下一次原重置时刻生效，不立即清空已用时间。"));
        picker.show();
    }

    private void toggleMasterSwitch() {
        if (!MasterSwitch.canEdit(this)) {
            toast(T.f("关闭总开关只能在调整时段 %s 内进行；开启不受限制",
                    TimeWindow.label(this)));
            return;
        }
        if (!MasterSwitch.enabled(this)) {
            if (!MasterSwitch.setEnabled(this, true)) {
                toast(T.s("总开关未能开启"));
                return;
            }
            toast(T.s("总开关已开启，原有规则已恢复执行"));
            render();
            return;
        }
        ThemeStore.dialog(this)
                .setTitle(T.s("关闭总开关"))
                .setMessage(T.s("关闭后会立即解除应用冻结、使用限额、学习联动、断网、页面拦截、锁机和亮屏灰度；调整时段会清零。全部规则与名单都会保留，之后随时可以重新开启。"))
                .setNegativeButton(T.s("取消"), null)
                .setPositiveButton(T.s("关闭"), (dialog, which) -> {
                    if (!MasterSwitch.setEnabled(this, false)) {
                        toast(T.f("关闭总开关只能在调整时段 %s 内进行",
                                TimeWindow.label(this)));
                        return;
                    }
                    toast(T.s("总开关已关闭，调整时段已清零；规则和名单已保留"));
                    render();
                })
                .show();
    }

    private void showAiAdjustmentStats() {
        ThemeStore.dialog(this)
                .setTitle(T.s("AI/外部调整统计"))
                .setMessage(AiAdjustmentStore.detail(this))
                .setPositiveButton(T.s("知道了"), null)
                .show();
    }

    private void editScreenOnLimit() {
        if (!requireQuotaEditWindow()) return;
        int[] presets = {60, 120, 180, 240, 360, 480};
        final String[] labels = new String[presets.length + 2];
        labels[0] = T.s("关闭");
        for (int i = 0; i < presets.length; i++) {
            labels[i + 1] = T.f("每天 %d 小时", presets[i] / 60);
        }
        labels[labels.length - 1] = T.s("自定义…");
        ThemeStore.dialog(this)
                .setTitle(T.s("亮屏时间上限"))
                .setItems(labels, (dialog, which) -> {
                    if (!requireQuotaEditWindow()) return;
                    if (which == 0) {
                        ScreenOnLimit.configure(this, false, ScreenOnLimit.minutes(this));
                        render();
                    } else if (which <= presets.length) {
                        ScreenOnLimit.configure(this, true, presets[which - 1]);
                        render();
                    } else {
                        customScreenOnLimit();
                    }
                }).show();
    }

    private void customScreenOnLimit() {
        if (!requireQuotaEditWindow()) return;
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(T.s("分钟，例如 150"));
        input.setText(String.valueOf(ScreenOnLimit.minutes(this)));
        input.setSelectAllOnFocus(true);
        int pad = ui.dp(18);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, ui.dp(4), pad, 0);
        box.addView(input, new android.widget.FrameLayout.LayoutParams(-1, -2));
        android.app.AlertDialog dialog = ThemeStore.dialog(this)
                .setTitle(T.s("自定义亮屏上限"))
                .setMessage(T.f("每天累计亮屏达到这个时长后，整机自动变灰；%s恢复。",
                        DayBoundary.label(this)))
                .setView(box)
                .setNegativeButton(T.s("取消"), null)
                .setPositiveButton(T.s("保存"), null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (!requireQuotaEditWindow()) return;
                    int minutes;
                    try {
                        minutes = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException error) {
                        toast(T.s("请输入 5–1440 之间的分钟数"));
                        return;
                    }
                    if (minutes < 5 || minutes > 24 * 60) {
                        toast(T.s("请输入 5–1440 之间的分钟数"));
                        return;
                    }
                    ScreenOnLimit.configure(this, true, minutes);
                    dialog.dismiss();
                    render();
                }));
        dialog.show();
    }

    private boolean requireQuotaEditWindow() {
        if (DailyQuotaStore.editable(this)) return true;
        toast(T.f("自律规则只在调整时段 %s 内可修改", TimeWindow.label(this)));
        return false;
    }

    private void toggleFreezeWarn() {
        if (!DailyQuotaStore.editable(this)) {
            toast(T.f("自律规则只在调整时段 %s 内可修改", TimeWindow.label(this)));
            return;
        }
        boolean enabled = !FreezeWarn.enabled(this);
        FreezeWarn.setEnabled(this, enabled);
        if (enabled && !RestReminderActivity.backgroundAllowed(this)) {
            toast(T.s("请允许后台提醒弹窗"));
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException error) {
                toast(T.s("请在系统设置中允许悬浮窗"));
            }
        }
        render();
    }

    /**
     * 学习来源和承接目标分开展示，五项都在同一页，含义不会被一个复选框揉在一起。
     */
    private void editLearningBindings() {
        LearningBindings.Binding current = LearningBindings.read(this);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(ui.dp(22), ui.dp(6), ui.dp(22), ui.dp(4));

        TextView sourceTitle = new TextView(this);
        sourceTitle.setText(T.s("学习目标来源"));
        sourceTitle.setTextSize(14);
        sourceTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sourceTitle.setTextColor(ui.text());
        sourceTitle.setPadding(0, ui.dp(8), 0, ui.dp(2));
        panel.addView(sourceTitle);

        CheckBox memory = bindingCheck(T.s("记忆"), current.memory);
        CheckBox quiz = bindingCheck(T.s("刷题"), current.quiz);
        panel.addView(memory);
        panel.addView(quiz);

        TextView targetTitle = new TextView(this);
        targetTitle.setText(T.s("任务未完成时继续约束"));
        targetTitle.setTextSize(14);
        targetTitle.setTypeface(Typeface.DEFAULT_BOLD);
        targetTitle.setTextColor(ui.text());
        targetTitle.setPadding(0, ui.dp(14), 0, ui.dp(2));
        panel.addView(targetTitle);

        CheckBox gate = bindingCheck(T.s("冻结时段"), current.gate);
        CheckBox quota = bindingCheck(T.s("时间限额"), current.quota);
        CheckBox network = bindingCheck(T.s("断网"), current.network);
        panel.addView(gate);
        panel.addView(quota);
        panel.addView(network);
        CheckBox reward = bindingCheck(T.s("奖励卡"), current.reward);
        panel.addView(reward);

        TextView note = new TextView(this);
        note.setText(T.f("勾选新的绑定可随时保存；取消绑定属于放宽，只在调整时段 %s 内开放。关键应用仍豁免。",
                TimeWindow.label(this)));
        note.setTextSize(12);
        note.setTextColor(ui.text3());
        note.setPadding(0, ui.dp(12), 0, 0);
        panel.addView(note);

        final AlertDialog dialog = ThemeStore.dialog(this)
                .setTitle(T.s("学习联动绑定"))
                .setMessage(T.s("记忆、刷题可以分别触发冻结时段、时间限额、断网和奖励卡。"))
                .setView(panel)
                .setNegativeButton(T.s("取消"), null)
                .setPositiveButton(T.s("保存"), null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    LearningBindings.Binding next = new LearningBindings.Binding(
                            memory.isChecked(), quiz.isChecked(), gate.isChecked(),
                            quota.isChecked(), network.isChecked(), reward.isChecked());
                    try {
                        LearningBindings.save(this, next);
                    } catch (RuntimeException error) {
                        toast(error.getMessage() == null ? T.s("学习联动绑定未能保存") : error.getMessage());
                        return;
                    }
                    dialog.dismiss();
                    toast(T.s("学习联动绑定已保存"));
                    render();
                }));
        dialog.show();
    }

    private CheckBox bindingCheck(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(15);
        box.setTextColor(ui.text());
        box.setChecked(checked);
        box.setPadding(0, ui.dp(3), 0, ui.dp(3));
        return box;
    }

    private void editFreezeWarnMinutes() {
        if (!DailyQuotaStore.editable(this)) {
            toast(T.f("自律规则只在调整时段 %s 内可修改", TimeWindow.label(this)));
            return;
        }
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setContentDescription(T.s("冻结前提醒提前量（分钟）"));
        input.setHint(T.f("1–180 分钟，默认 %d 分钟", FreezeWarn.DEFAULT_MINUTES));
        input.setText(String.valueOf(FreezeWarn.minutes(this)));
        input.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        ThemeStore.excludeFont(input);
        final AlertDialog dialog = ThemeStore.dialog(this)
                .setTitle(T.s("提醒提前量"))
                .setMessage(T.s("冻结前多少分钟提醒一次。"))
                .setView(input)
                .setNegativeButton(T.s("取消"), null)
                .setPositiveButton(T.s("保存"), null)
                .create();
        dialog.setOnShowListener(shown ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException error) {
                        toast(T.f("请填写 %d–%d 之间的整数分钟",
                                FreezeWarn.MIN_MINUTES, FreezeWarn.MAX_MINUTES));
                        return;
                    }
                    if (value < FreezeWarn.MIN_MINUTES || value > FreezeWarn.MAX_MINUTES) {
                        toast(T.f("请填写 %d–%d 之间的整数分钟",
                                FreezeWarn.MIN_MINUTES, FreezeWarn.MAX_MINUTES));
                        return;
                    }
                    FreezeWarn.setMinutes(this, value);
                    dialog.dismiss();
                    toast(T.f("将在冻结前 %d 分钟提醒", value));
                    render();
                }));
        dialog.show();
        input.requestFocus();
        input.selectAll();
    }

    private String pluginSummary() {
        String builtIn = T.f("内置 %d / %d",
                com.afs.guard.plugins.BuiltInPluginStore.enabledCount(this),
                com.afs.guard.plugins.BuiltInPluginStore.plugins().size());
        int enabled = 0, total = 0;
        try {
            for (com.afs.host.plugins.PluginEntry entry
                    : com.afs.host.plugins.HostRuntime.get(this).registry().entries()) {
                if("com.afs.plugin.usagestats".equals(entry.packageName()))continue;
                total++;
                if (entry.usable()) enabled++;
            }
        } catch (RuntimeException ignored) { }
        if (total == 0) return builtIn + T.s(" · 外部未安装");
        return builtIn + Lang.t(this, " · 外部 " + enabled + " / " + total + " 已启用",
                " · " + enabled + " / " + total + " external enabled");
    }

    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, ui.dp(18));
        TextView back = new TextView(this);
        back.setText("‹  " + Lang.t(this, "返回", "Back"));
        back.setTextSize(14);
        back.setMinHeight(ui.dp(48));
        back.setTextColor(ui.accent());
        back.setPadding(0, ui.dp(6), ui.dp(12), ui.dp(6));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        return bar;
    }

    private TextView groupTitle(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(12);
        view.setTextColor(ui.text3());
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(ui.dp(4), ui.dp(16), 0, ui.dp(6));
        return view;
    }

    private TextView row(LinearLayout parent, String title, String value, Runnable onClick) {
        boolean stacked=value!=null && (value.codePointCount(0,value.length())>14
                || title.codePointCount(0,title.length())>7);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(ui.dp(56));
        line.setPadding(0, ui.dp(12), 0, ui.dp(12));

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(15);
        name.setTextColor(ui.text());
        line.addView(name, stacked?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tail = new TextView(this);
        tail.setText(value == null ? "" : value);
        tail.setTextSize(13);
        tail.setTextColor(ui.text2());
        tail.setSingleLine(!stacked);
        if(stacked){
            LinearLayout.LayoutParams detail=new LinearLayout.LayoutParams(-1,-2);
            detail.topMargin=ui.dp(4);line.addView(tail,detail);
        }else{
            tail.setMaxWidth((int)(getResources().getDisplayMetrics().widthPixels*.46f));
            tail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tail.setPadding(ui.dp(12),0,0,0);line.addView(tail);
        }
        if(onClick!=null){
            android.util.TypedValue ripple=new android.util.TypedValue();
            if(getTheme().resolveAttribute(android.R.attr.selectableItemBackground,ripple,true))line.setBackgroundResource(ripple.resourceId);
        }

        if (onClick != null) line.setOnClickListener(v -> onClick.run());
        parent.addView(line);
        return tail;
    }

    private void permRow(LinearLayout parent, String title, boolean granted, String why, Runnable onClick) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.VERTICAL);
        line.setPadding(0, ui.dp(12), 0, ui.dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(15);
        name.setTextColor(ui.text());
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView state = new TextView(this);
        state.setText(granted ? T.s("已开启") : T.s("未开启"));
        state.setTextSize(13);
        state.setTextColor(granted ? ui.green() : ui.red());
        top.addView(state);
        line.addView(top);

        // why 保留在签名里兼容调用点，但不再渲染解释性小字。
        if (onClick != null) line.setOnClickListener(v -> onClick.run());
        parent.addView(line);
    }

    /* ==================== 交互 ==================== */

    private void pickLanguage() {
        final String[] labels = {T.s("跟随系统"), T.s("中文"), "English"};
        final String[] values = {Lang.SYSTEM, Lang.ZH, Lang.EN};
        int checked = 0;
        String current = Lang.mode(this);
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) checked = i;
        ThemeStore.dialog(this)
                .setTitle(Lang.t(this, "界面语言", "Language"))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Lang.save(this, values[which]);
                    dialog.dismiss();
                    toast(T.s("界面语言已切换"));
                    recreate();
                })
                .setNegativeButton(Lang.t(this, "取消", "Cancel"), null)
                .show();
    }

    private void toggleStartupAnimation() {
        boolean enabled = !StartupPreferences.enabled(this);
        StartupPreferences.setEnabled(this, enabled);
        toast(enabled ? T.s("开屏动画已开启") : T.s("开屏动画已关闭"));
        render();
    }

    private void checkUpdate() {
        final String[] sources = UpdateCheck.sources();
        if (sources.length == 0) {
            updateLine.setText(T.s("还没有配置更新源。当前版本 ") + version() + T.s("，发布后这里会直接告诉你有没有新版。"));
            return;
        }
        updateLine.setText(T.s("正在检查…"));
        final String local = localVersion();
        final long code = versionCode();
        new Thread(() -> {
            UpdateCheck.Release release = null;
            String answerFailure = null;    // 有源回了话（404 / 看不懂），这个说法比"超时"可信
            String networkFailure = null;   // 只是连不上：DNS、超时、被墙
            for (int i = 0; i < sources.length; i++) {
                final String address = sources[i];
                if (i > 0) {
                    main.post(() -> {
                        if (!destroyed && updateLine != null) updateLine.setText(T.s("官方源没响应，正在试镜像…"));
                    });
                    sleep(200);
                }
                try {
                    release = fetchRelease(address);
                    break;
                } catch (Exception error) {
                    String reason = error.getMessage();
                    String text = T.s("检查失败：") + (reason == null || reason.trim().isEmpty()
                            ? error.getClass().getSimpleName() : reason);
                    if (error instanceof java.io.IOException) networkFailure = text;
                    else answerFailure = text;
                }
            }
            String failure = null;
            if (release == null) {
                if (answerFailure != null) failure = answerFailure;
                else if (networkFailure != null) failure = T.s("检查失败：官方源和镜像都连不上，过会儿再试");
            }
            final UpdateCheck.Release found = release;
            final String problem = failure;
            main.post(() -> {
                if (destroyed || updateLine == null) return;
                if (found == null) {
                    updateLine.setText(problem == null ? T.s("检查失败：更新源给的内容看不懂") : problem);
                    return;
                }
                if (!UpdateCheck.newer(found, local, code)) {
                    updateLine.setText(T.s("已经是最新版本（") + local + T.s("）"));
                    return;
                }
                updateLine.setText(T.s("发现新版本 ") + found.version);
                offerUpdate(found, local);
            });
        }, "afs-update-check").start();
    }

    /** 等一小会儿再试下一条源：镜像切换别把手机网络打满。 */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 拉取并解析更新源；出错抛人话异常，由调用方显示。 */
    private UpdateCheck.Release fetchRelease(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(6000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "afs-guard/" + localVersion());
        try {
            int response = connection.getResponseCode();
            if (response == 404) throw new IllegalStateException(T.s("更新源还没有发布过版本"));
            if (response == 403) throw new IllegalStateException(T.s("更新源今天被问得太多，明天再试"));
            if (response != 200) throw new IllegalStateException(T.s("服务器返回 ") + response);
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) > 0) buffer.write(chunk, 0, read);
            input.close();
            UpdateCheck.Release release = UpdateCheck.parse(buffer.toString("UTF-8"), address);
            if (release == null) throw new IllegalStateException(T.s("更新源给的内容看不懂"));
            return release;
        } finally {
            connection.disconnect();
        }
    }

    /** 有新版本才弹，说明里放更新源给的说明；没有说明就报版本号。 */
    private void offerUpdate(UpdateCheck.Release release, String local) {
        String text = release.notes == null ? "" : release.notes.trim();
        if (text.length() > 600) text = text.substring(0, 600) + "…";
        if (text.isEmpty()) {
            text = String.format(T.s("当前版本 %s，可以升到 %s。"), local, release.version);
        }
        final String[] downloads = release.downloads();
        AlertDialog.Builder dialog = ThemeStore.dialog(this)
                .setTitle(T.s("发现新版本 ") + release.version)
                .setMessage(text)
                .setNegativeButton(T.s("以后再说"), null)
                .setPositiveButton(downloads.length > 0 ? T.s("去下载") : T.s("知道了"), (d, which) -> {
                    if (downloads.length > 0) browse(downloads[0]);
                });
        // 只有清单里额外给了镜像才出现：「去下载」打不开时还能换一条。
        if (downloads.length > 1) {
            dialog.setNeutralButton(T.s("换个源"), (d, which) -> browse(downloads[1]));
        }
        dialog.show();
    }

    /** 用浏览器打开更新包的下载地址。 */
    private void browse(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException error) {
            toast(T.s("没有能打开这个链接的浏览器"));
        }
    }

    private void showPrivacy() {
        String text = T.s("存在手机里的\n")
                + T.s("· 打卡目标、笔记、记忆卡、题库、额度与冻结规则，全部在本机应用私有目录。\n")
                + T.s("· 使用时长只取自系统使用记录，在本机算，不上传。\n\n")
                + T.s("会联网的地方\n")
                + T.s("· 只有你主动开启同步时，才把打卡与目标推到你自己配置的 Gist。\n")
                + T.s("· 检查更新只在你点按钮时去发布页取一份版本清单（官方源连不上会换镜像），只读不写，也不带任何本机信息。\n\n")
                + T.s("不会做的事\n")
                + T.s("· 不收集设备标识，不上报崩溃，没有广告或统计 SDK。\n")
                + T.s("· 不把屏幕内容发到任何服务器；关键词拦截在本地匹配。");
        ThemeStore.dialog(this)
                .setTitle(Lang.t(this, "隐私说明", "Privacy"))
                .setMessage(text)
                .setPositiveButton(T.s("知道了"), null)
                .show();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            toast(T.s("这个系统没有对应的设置页"));
        }
    }

    private String themeSummary() {
        AppearanceConfig appearance = ThemeStore.load(this);
        com.afs.guard.style.RendererFactory renderer = com.afs.guard.style.StyleRegistry.find(
                com.afs.guard.style.StyleManager.selected(this));
        String skin = renderer != null ? renderer.displayName(this) : T.s("简约风格");
        String mode = Ui.themeName(this);
        if ("auto".equals(mode)) mode = T.s("跟随系统");
        else if ("dark".equals(mode)) mode = T.s("深色");
        else if ("light".equals(mode)) mode = T.s("浅色");
        return skin + " · " + mode;
    }

    private boolean notifications() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && manager.areNotificationsEnabled();
    }

    private boolean exactAlarm() {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager manager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    private boolean batteryFree() {
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private String version() {
        return localVersion() + T.s("（") + versionCode() + T.s("）");
    }

    private String localVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "—" : info.versionName;
        } catch (Exception error) {
            return "—";
        }
    }

    @SuppressWarnings("deprecation")
    private int versionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (Exception error) {
            return 0;
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    /* ---------------- 跨设备共享额度 ---------------- */

    private String lanSummary() {
        if (!LanQuota.enabled(this)) return Lang.t(this, "关", "Off");
        if (!LanQuota.active(this)) return Lang.t(this, "未绑定", "Not bound");
        int bindings = LanQuota.bindings(this).length();
        if (bindings > 1) return T.f("%d 格共享", bindings);
        // 这一行的右边只放得下几个字：完整描述放进弹窗，否则标题会被挤成竖排。
        if (!LanQuota.fresh(this)) return Lang.t(this, "未连上电脑", "Offline");
        long remainSec = Math.max(0L, LanQuota.minutes(this) * 60L - LanQuota.total(this) / 1000L);
        return T.f("剩 %d 分钟", remainSec / 60);
    }

    private void lanQuotaEditor() {
        // 面板本体在 LanQuotaPanel：额度页那条跨设备格子上也有同一个入口。
        LanQuotaPanel.show(this, this::render);
    }
}
