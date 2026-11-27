package com.afs.blocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.afs.core.rules.RuleValidation;
import com.afs.core.rules.WeeklyRule;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class AppRulesActivity extends Activity {
    private LinearLayout body;
    private final Map<String, TextView> states = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() { refresh(); handler.postDelayed(this, 1000L); }
    };

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(1);
        body.setPadding(24, 24, 24, 24);
        scroll.addView(body);
        setContentView(scroll);
        render();
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setPadding(12, 16, 12, 16);
        parent.addView(view);
        return view;
    }

    static String summary(WeeklyRule rule) {
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        StringBuilder value = new StringBuilder(rule.freezeMode() ? "冻结 " : "放行 ");
        if (rule.days() == 127) value.append("每天");
        else for (int index = 0; index < 7; index++)
            if (WeeklyRule.dayOn(rule.days(), index)) value.append("周").append(names[index]).append(' ');
        for (int index = 0; index < rule.windowCount(); index++)
            value.append(" · ").append(Guard.windowText(rule.startAt(index), rule.endAt(index)))
                    .append(rule.crossMidnightAt(index) ? " 次日" : "");
        return value.toString();
    }

    private void render() {
        body.removeAllViews(); states.clear();
        text(body, "应用规则", 23);
        text(body, "点击编辑星期、模式和多个时段。锁定期间不能修改。", 14);
        try {
            for (Map.Entry<String, WeeklyRule> entry : Rules.all(this).entrySet()) {
                String pkg = entry.getKey();
                LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
                ImageView icon = new ImageView(this);
                String name = pkg;
                try {
                    icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
                    name = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
                row.addView(icon, new LinearLayout.LayoutParams(72, 72));
                TextView title = text(row, name, 17);
                title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                body.addView(row);
                states.put(pkg, text(body, "", 13));
                row.setOnClickListener(view -> edit(pkg));
                states.get(pkg).setOnClickListener(view -> edit(pkg));
            }
            refresh();
        } catch (IllegalStateException error) { text(body, error.getMessage(), 15); }
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        try {
            for (Map.Entry<String, WeeklyRule> entry : Rules.all(this).entrySet()) {
                TextView view = states.get(entry.getKey());
                if (view == null) continue;
                long next = Guard.nextTransition(entry.getValue(), now, Store.forceUntil(this), TimeZone.getDefault());
                String timing = next == 0 ? "无计划切换" : "下次" + (Rules.blocked(this, entry.getKey(), now) ? "放行 " : "拦截 ")
                        + new SimpleDateFormat("M/d HH:mm:ss", Locale.CHINA).format(new Date(next));
                view.setText((Rules.blocked(this, entry.getKey(), now) ? "需拦截" : "放行") + " · "
                        + summary(entry.getValue()) + "\n" + timing);
            }
        } catch (IllegalStateException ignored) {}
    }

    private void edit(String pkg) {
        if (Rules.locked(this)) { toast("锁定中，请在放行后修改规则"); return; }
        WeeklyRule original = Rules.all(this).get(pkg);
        LinearLayout form = new LinearLayout(this); form.setOrientation(1);
        CheckBox freeze = new CheckBox(this); freeze.setText("选中时段内拦截（取消则时段内放行）");
        freeze.setChecked(original.freezeMode()); form.addView(freeze);
        CheckBox[] weekdays = new CheckBox[7];
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        LinearLayout days = new LinearLayout(this);
        for (int index = 0; index < 7; index++) {
            weekdays[index] = new CheckBox(this); weekdays[index].setText(names[index]);
            weekdays[index].setTextSize(11); weekdays[index].setChecked(WeeklyRule.dayOn(original.days(), index));
            days.addView(weekdays[index], new LinearLayout.LayoutParams(0, -2, 1));
        }
        form.addView(days);
        List<int[]> windows = new ArrayList<>();
        for (int[] segment : original.windows()) windows.add(segment);
        LinearLayout segments = new LinearLayout(this); segments.setOrientation(1); form.addView(segments);
        TextView warnings = text(form, "", 14);
        Runnable update = () -> {
            int mask = 0;
            for (int index = 0; index < 7; index++) if (weekdays[index].isChecked()) mask |= 1 << index;
            WeeklyRule rule = freeze.isChecked() ? WeeklyRule.freeze(mask, windows.toArray(new int[0][]))
                    : WeeklyRule.allow(mask, windows.toArray(new int[0][]));
            warnings.setText(summary(rule) + "\n" + android.text.TextUtils.join("\n", RuleValidation.warnings(rule)));
        };
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            segments.removeAllViews();
            for (int[] segment : new ArrayList<>(windows)) {
                LinearLayout row = new LinearLayout(this);
                Button time = new Button(this); time.setText(Guard.windowText(segment[0], segment[1]));
                time.setOnClickListener(view -> pick(segment, () -> { rebuild[0].run(); update.run(); }));
                row.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
                Button remove = new Button(this); remove.setText("删除");
                remove.setOnClickListener(view -> { windows.remove(segment); rebuild[0].run(); update.run(); });
                row.addView(remove); segments.addView(row);
            }
        };
        Button add = new Button(this); add.setText("添加时段");
        add.setOnClickListener(view -> {
            int[] segment = {1200, 1380};
            pick(segment, () -> { windows.add(segment); rebuild[0].run(); update.run(); });
        }); form.addView(add);
        freeze.setOnCheckedChangeListener((button, checked) -> update.run());
        for (CheckBox day : weekdays) day.setOnCheckedChangeListener((button, checked) -> update.run());
        rebuild[0].run(); update.run();
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("编辑应用规则").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(view -> {
            int mask = 0;
            for (int index = 0; index < 7; index++) if (weekdays[index].isChecked()) mask |= 1 << index;
            WeeklyRule rule = freeze.isChecked() ? WeeklyRule.freeze(mask, windows.toArray(new int[0][]))
                    : WeeklyRule.allow(mask, windows.toArray(new int[0][]));
            try { Rules.put(this, pkg, rule); dialog.dismiss(); render(); }
            catch (RuntimeException error) { toast(error.getMessage()); }
        })); dialog.show();
    }

    private void pick(int[] segment, Runnable saved) {
        new TimePickerDialog(this, (view, hour, minute) -> {
            int start = hour * 60 + minute;
            new TimePickerDialog(this, (endView, endHour, endMinute) -> {
                segment[0] = start; segment[1] = endHour * 60 + endMinute; saved.run();
            }, segment[1] / 60, segment[1] % 60, true).show();
        }, segment[0] / 60, segment[0] % 60, true).show();
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    protected void onResume() { super.onResume(); handler.removeCallbacks(tick); handler.post(tick); }
    protected void onPause() { handler.removeCallbacks(tick); super.onPause(); }
}
