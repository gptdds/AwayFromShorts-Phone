package com.afs.guard;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 运行状态：联动与打卡罚时、逐应用当前状态、最近一次执行记录。 */
public class StatusActivity extends ThemedActivity {
    private Ui ui;
    private LinearLayout appCells;
    private TextView appCount;
    private TextView linkageState;
    private TextView linkageDetail;
    private LinearLayout linkageCard;
    private int refreshCount;
    private final Handler handler = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean busy;
    private boolean disposed;
    private int generation;
    private String renderedSignature;

    private static final class AppState {
        final String pkg;
        final String name;
        final String detail;
        final int reason;
        final boolean installed;
        final boolean frozen;
        final boolean hidden;
        final String timing;

        AppState(String pkg, String name, String detail, int reason,
                 boolean installed, boolean frozen, boolean hidden, String timing) {
            this.pkg = pkg;
            this.name = name;
            this.detail = detail;
            this.reason = reason;
            this.installed = installed;
            this.frozen = frozen;
            this.hidden = hidden;
            this.timing = timing;
        }
    }

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            refreshLinkageCard();
            if (refreshCount++ % 3 == 0) refreshApplications();
            if (!disposed) handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        ScrollView scroll = new ScrollView(this);
        ui.page(scroll);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(32));
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));

        TextView back = ui.sub(T.s("‹ 返回"));
        back.setTextSize(16);
        back.setOnClickListener(v -> finish());
        body.addView(back);

        TextView title = ui.title(T.s("运行状态"));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = ui.dp(20);
        body.addView(title, titleLp);
        body.addView(ui.sub(T.s("联动、罚时和每个受管应用此刻的状态")));

        addLinkageCard(body);
        addApplicationHeader(body);
        appCells = new LinearLayout(this);
        appCells.setOrientation(LinearLayout.VERTICAL);
        body.addView(appCells, new LinearLayout.LayoutParams(-1, -2));
        TextView loading = ui.sub(T.s("正在检查应用状态"));
        loading.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        loading.setBackground(ui.round(ui.surface2(), 14));
        appCells.addView(loading, new LinearLayout.LayoutParams(-1, -2));

        addLastRunCard(body);
        setContentView(scroll);
    }

    private void addLinkageCard(LinearLayout body) {
        linkageCard = ui.card();
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = ui.dp(18);
        body.addView(linkageCard, cardLp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(this);
        heading.setText(T.s("打卡罚时与学习联动"));
        heading.setTextSize(15);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setTextColor(ui.text());
        head.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        linkageState = ui.chip("", ui.text2(), ui.surface2());
        head.addView(linkageState);
        linkageCard.addView(head);

        linkageDetail = ui.sub("");
        linkageDetail.setTextSize(13);
        linkageDetail.setLineSpacing(ui.dp(4), 1f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = ui.dp(9);
        linkageCard.addView(linkageDetail, detailLp);
        TextView open = ui.sub(T.s("点这里去完成对应任务"));
        open.setTextColor(ui.accent());
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-1, -2);
        openLp.topMargin = ui.dp(8);
        linkageCard.addView(open, openLp);
        open.setTag("linkage-open");
        linkageCard.setOnClickListener(v -> openGateSource());
        refreshLinkageCard();
    }

    private void refreshLinkageCard() {
        if (linkageState == null || linkageDetail == null || linkageCard == null) return;
        if (!MasterSwitch.enabled(this)) {
            linkageState.setText(T.s("总开关关闭"));
            linkageState.setTextColor(ui.text2());
            linkageState.setBackground(ui.round(ui.surface2(), 99f));
            linkageDetail.setText(T.s("冻结、限额、学习联动、断网、页面拦截、锁机和亮屏灰度均已暂停；规则和名单保留。"));
            linkageDetail.setTextColor(ui.text2());
            View open = linkageCard.findViewWithTag("linkage-open");
            if (open != null) open.setVisibility(View.GONE);
            linkageCard.setClickable(false);
            return;
        }
        Calendar now = GuardClock.now(this);
        String hold = StudyGate.holdReason(this, now);
        String detail = hold.isEmpty() ? CheckinPenalty.describe(this) : hold;
        PenaltyStore.CheckinSummary penalty =
                PenaltyStore.checkinSummary(this, now.getTimeInMillis());
        boolean active = penalty.active > 0
                || PenaltyStore.any(this, now.getTimeInMillis())
                || hold.contains(T.s("原冻结结束后继续冻结中"))
                || hold.contains(T.s("延时生效中"))
                || hold.contains(T.s("不会解冻"));
        boolean pending = !hold.isEmpty() && !active;
        String state = penalty.count > 0 && penalty.active > 0 && penalty.active < penalty.count
                ? T.s("部分接续")
                : active ? T.s("生效中") : pending ? T.s("待接续") : T.s("正常");
        int fg = active ? ui.red() : pending ? ui.accent() : ui.green();
        int fill = active ? (ui.dark ? 0xFF37212A : 0xFFFCE9E8)
                : pending ? ui.surface2()
                : (ui.dark ? 0xFF17342B : 0xFFE7F7EF);
        if (!state.contentEquals(linkageState.getText())) linkageState.setText(state);
        linkageState.setTextColor(fg);
        linkageState.setBackground(ui.round(fill, 99f));
        if (!detail.contentEquals(linkageDetail.getText())) linkageDetail.setText(detail);
        linkageDetail.setTextColor(active ? ui.red() : ui.text2());
        View open = linkageCard.findViewWithTag("linkage-open");
        if (open != null) open.setVisibility(hold.isEmpty() ? View.GONE : View.VISIBLE);
        linkageCard.setClickable(!hold.isEmpty());
        linkageCard.setContentDescription(T.s("打卡罚时与学习联动") + " · " + state + " · " + detail);
    }

    private void addApplicationHeader(LinearLayout body) {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(T.s("应用状态"));
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(ui.text());
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        appCount = ui.sub(T.s("正在检查"));
        head.addView(appCount);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ui.dp(24);
        lp.bottomMargin = ui.dp(10);
        body.addView(head, lp);
    }

    private void addLastRunCard(LinearLayout body) {
        TextView section = new TextView(this);
        section.setText(T.s("最近一次执行"));
        section.setTextSize(18);
        section.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        section.setTextColor(ui.text());
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.topMargin = ui.dp(22);
        body.addView(section, sectionLp);

        SharedPreferences prefs = getSharedPreferences("afs_status", MODE_PRIVATE);
        long at = prefs.getLong("last_at", 0L);
        TextView time = ui.sub(at == 0L ? T.s("尚未执行") : T.s("最近一次应用 · ")
                + new SimpleDateFormat(T.s("M月d日 HH:mm:ss"), Locale.CHINA).format(new Date(at)));
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(-1, -2);
        timeLp.topMargin = ui.dp(5);
        body.addView(time, timeLp);

        LinearLayout card = ui.card();
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = ui.dp(10);
        body.addView(card, cardLp);
        TextView details = ui.sub(prefs.getString("last_result", T.s("尚无运行明细")));
        details.setTextSize(13);
        details.setLineSpacing(ui.dp(5), 1f);
        card.addView(details);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTick);
        refreshTick.run();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        disposed = true;
        generation++;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refreshApplications() {
        if (busy || disposed || appCells == null) return;
        busy = true;
        final int current = ++generation;
        final Calendar now = GuardClock.now(this);
        final Map<String, Rule> enforced = new LinkedHashMap<>(Guard.enforcedRules(this));
        final Set<String> packages = new LinkedHashSet<>(RuleStore.all(this).keySet());
        packages.addAll(DailyQuotaStore.packages(this));
        worker.execute(() -> {
            Set<String> keys = new LinkedHashSet<>(RuleStore.keyApps(this));
            boolean hold = StudyGate.holding(this, now);
            boolean taboo = StudyGate.tabooHolding(now.getTimeInMillis(), StudyGate.tabooUntil(this));
            boolean reward = CardStore.rewardUntil(this, now.getTimeInMillis()) > 0;
            List<AppState> states = new ArrayList<>();
            for (String pkg : packages) {
                Rule rule = enforced.get(pkg);
                int reason = Guard.blockReasonNow(this, pkg, now, enforced, keys, hold, taboo, reward);
                boolean installed = Guard.installed(getPackageManager(), pkg);
                boolean frozen = false;
                try {
                    frozen = (getPackageManager().getApplicationInfo(pkg, 0).flags
                            & android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0;
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                }
                boolean hidden = installed && reason != 0 && !frozen && Guard.hiddenNow(this, pkg);
                String name = Guard.labelOf(getPackageManager(), pkg);
                StringBuilder detail = new StringBuilder();
                if (!installed) {
                    detail.append(T.s("未安装 · 冻结和限额名单保留"));
                } else if (rule == null) {
                    detail.append(T.s("未设置冻结时段"));
                } else if (FreezeSlotStore.unassigned(rule)) {
                    detail.append(T.s("全天"));
                } else if (!rule.freeze) {
                    detail.append(T.s("原有冻结计划"));
                } else {
                    detail.append(rule.windowCount()).append(T.s(" 个冻结时段"));
                }
                org.json.JSONObject group = DailyQuotaStore.snapshot(this).group(pkg);
                int legacy = installed ? AppUsageQuota.minutes(this, pkg) : 0;
                if (group != null) {
                    org.json.JSONArray apps = group.optJSONArray("apps");
                    detail.append(" · ").append(apps != null && apps.length() > 1
                            ? T.s("共享额度 ") : T.s("额度 "))
                            .append(group.optInt("minutes")).append(T.s(" 分钟/日"));
                } else if (legacy > 0) {
                    detail.append(" · ").append(legacy).append(T.s(" 分钟/日"));
                }
                if (hidden) detail.append(T.s(" · 已隐藏"));
                String timing = installed ? StudyGate.extensionStatus(this, pkg).replace('\n', '·') : "";
                if (installed && reason == 2 && timing.isEmpty())
                    timing = T.s("延时冻结中：目标完成前不会按基础时间解冻");
                states.add(new AppState(pkg, name, detail.toString(), reason,
                        installed, frozen, hidden, timing));
            }
            Collections.sort(states, (a, b) -> a.name.compareToIgnoreCase(b.name));
            handler.post(() -> {
                busy = false;
                if (disposed || current != generation || appCells == null) return;
                renderApplications(states);
            });
        });
    }

    private void renderApplications(List<AppState> states) {
        appCount.setText(states.isEmpty() ? T.s("0 个") : states.size() + T.s(" 个"));
        StringBuilder signature = new StringBuilder();
        for (AppState state : states) {
            signature.append(state.pkg).append('|').append(state.name).append('|')
                    .append(state.detail).append('|').append(state.reason).append('|')
                    .append(state.installed).append('|').append(state.frozen).append('|')
                    .append(state.hidden).append('|').append(state.timing).append('\n');
        }
        if (renderedSignature != null && signature.toString().equals(renderedSignature)
                && appCells.getChildCount() > 0) return;
        renderedSignature = signature.toString();
        appCells.removeAllViews();
        if (states.isEmpty()) {
            TextView empty = ui.sub(T.s("还没有受冻结时段或使用限额管理的应用"));
            empty.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
            empty.setBackground(ui.round(ui.surface2(), 14));
            appCells.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (AppState state : states) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(ui.dp(13), ui.dp(12), ui.dp(13), ui.dp(12));
            cell.setBackground(ui.round(ui.surface(), 16, ui.line(), 0.75f));
            String statusText = state.installed ? reasonLabel(state.reason) : T.s("未安装");
            cell.setContentDescription(state.name + " · " + statusText
                    + " · " + state.detail);

            ImageView icon = new ImageView(this);
            icon.setImageResource(android.R.drawable.sym_def_app_icon);
            Drawable drawable = AppIcons.drawable(this, state.pkg);
            if (drawable != null) icon.setImageDrawable(drawable);
            if (state.reason != 0 || state.hidden) {
                ColorMatrix matrix = new ColorMatrix();
                matrix.setSaturation(0.25f);
                icon.setColorFilter(new ColorMatrixColorFilter(matrix));
            }
            cell.addView(icon, new LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(state.name);
            name.setTextSize(15);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setTextColor(state.installed ? ui.text() : ui.text3());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name);
            TextView detail = ui.sub(state.detail);
            detail.setTextSize(11);
            detail.setSingleLine(true);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
            detailLp.topMargin = ui.dp(3);
            labels.addView(detail, detailLp);
            LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, -2, 1f);
            labelsLp.leftMargin = ui.dp(11);
            cell.addView(labels, labelsLp);
            if (!state.timing.isEmpty()) {
                TextView timing = ui.sub(state.timing);
                timing.setTextSize(10.5f);
                timing.setTextColor(state.reason == 0 ? ui.text2() : ui.red());
                timing.setMaxLines(3);
                timing.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams timingLp = new LinearLayout.LayoutParams(-1, -2);
                timingLp.topMargin = ui.dp(4);
                labels.addView(timing, timingLp);
            }

            int color = !state.installed || state.reason < 0 ? ui.text3()
                    : state.reason == 0 ? ui.green() : ui.red();
            TextView status = ui.chip(statusText, color,
                    state.installed && state.reason == 0 ? (ui.dark ? 0xFF17342B : 0xFFE7F7EF)
                            : (ui.dark ? 0xFF37212A : 0xFFFCE9E8));
            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
            statusLp.leftMargin = ui.dp(8);
            cell.addView(status, statusLp);

            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(-1, -2);
            cellLp.bottomMargin = ui.dp(8);
            appCells.addView(cell, cellLp);
        }
    }

    private String reasonLabel(int reason) {
        return reason < 0 ? T.s("正在检查状态") : Guard.blockReasonText(reason);
    }

    private void openGateSource() {
        LearningLink.Progress memory = LearningLink.memory(this);
        LearningLink.Progress quiz = LearningLink.quiz(this);
        boolean dailyTime = GuardClock.now(this).get(Calendar.HOUR_OF_DAY) >= 20;
        if (dailyTime && StudyGate.studyPending(this) > 0) {
            startActivity(new Intent(this, CheckinActivity.class));
        } else if (dailyTime && memory.available && memory.selected && !memory.complete()) {
            startActivity(new Intent(this, MemoryActivity.class));
        } else if (dailyTime && quiz.available && quiz.selected && !quiz.complete()) {
            startActivity(new Intent(this, QuizActivity.class));
        } else if (CardStore.summaryHolding(this, System.currentTimeMillis())) {
            startActivity(new Intent(this, HistoryActivity.class)
                    .putExtra(HistoryActivity.EXTRA_WEEKLY, true));
        } else if (StudyGate.tabooActive(this)) {
            android.widget.Toast.makeText(this, T.s("禁忌惩罚到点自动解除，没有解除按钮"),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
