package com.afs.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Shared editor for the whole-day weekday pause policy. */
final class FreezeWeekdayPolicyDialog {
    private FreezeWeekdayPolicyDialog() {
    }

    static void show(Activity host, Ui look, Runnable after) {
        if (host == null || host.isFinishing()) return;
        int current = FreezeWeekdayPolicy.disabledDays(host);
        int currentScopes = FreezeWeekdayPolicy.pauseScopes(host);
        boolean editable = TimeWindow.canEdit(host);

        LinearLayout sheet = new LinearLayout(host);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(host, look, 18), dp(host, look, 4), dp(host, look, 18), dp(host, look, 4));

        TextView note = text(host, look, editable
                ? T.s("所选日期可暂停全部五类限制，也可逐项选择。规则、名单和已用时间都会保留。")
                : T.f("当前只能查看。全局停用与调整时段绑定，请在 %s 内修改。", TimeWindow.label(host)),
                12, look.text2());
        note.setLineSpacing(dp(host, look, 2), 1f);
        note.setPadding(dp(host, look, 12), dp(host, look, 10),
                dp(host, look, 12), dp(host, look, 10));
        note.setBackground(look.round(look.surface2(), 13));
        sheet.addView(note, gap(host, look, 8));

        ScrollView scroll = new ScrollView(host);
        LinearLayout options = new LinearLayout(host);
        options.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(options);

        RadioGroup modes = new RadioGroup(host);
        modes.setOrientation(RadioGroup.VERTICAL);
        int globalId = View.generateViewId();
        int customId = View.generateViewId();
        RadioButton global = new RadioButton(host);
        global.setId(globalId);
        global.setText(T.s("全局暂停（五类）"));
        global.setTextSize(15);
        global.setTextColor(look.text());
        global.setPadding(dp(host, look, 8), 0, dp(host, look, 8), 0);
        global.setMinHeight(dp(host, look, 48));
        RadioButton custom = new RadioButton(host);
        custom.setId(customId);
        custom.setText(T.s("自定义范围"));
        custom.setTextSize(15);
        custom.setTextColor(look.text());
        custom.setPadding(dp(host, look, 8), 0, dp(host, look, 8), 0);
        custom.setMinHeight(dp(host, look, 48));
        modes.addView(global, new LinearLayout.LayoutParams(-1, dp(host, look, 48)));
        modes.addView(custom, new LinearLayout.LayoutParams(-1, dp(host, look, 48)));
        modes.check(currentScopes == FreezeWeekdayPolicy.ALL_SCOPES ? globalId : customId);
        modes.setEnabled(editable);
        global.setEnabled(editable);
        custom.setEnabled(editable);
        options.addView(modes);

        LinearLayout scopeBox = new LinearLayout(host);
        scopeBox.setOrientation(LinearLayout.VERTICAL);
        scopeBox.setPadding(dp(host, look, 8), dp(host, look, 2),
                dp(host, look, 8), dp(host, look, 8));
        final CheckBox[] scopeChecks = new CheckBox[5];
        int[] scopeValues = {
                FreezeWeekdayPolicy.SCOPE_SCHEDULE,
                FreezeWeekdayPolicy.SCOPE_QUOTA,
                FreezeWeekdayPolicy.SCOPE_OR_SET,
                FreezeWeekdayPolicy.SCOPE_STUDY,
                FreezeWeekdayPolicy.SCOPE_PENALTY
        };
        String[] scopeLabels = {
                T.s("冻结时段"), T.s("时间限额"), T.s("或集"), T.s("学习联动"), T.s("罚时")
        };
        for (int i = 0; i < scopeValues.length; i++) {
            CheckBox check = new CheckBox(host);
            check.setText(scopeLabels[i]);
            check.setTextSize(14);
            check.setTextColor(look.text2());
            check.setChecked((currentScopes & scopeValues[i]) != 0);
            check.setMinHeight(dp(host, look, 44));
            check.setPadding(dp(host, look, 10), 0, dp(host, look, 10), 0);
            scopeChecks[i] = check;
            scopeBox.addView(check, new LinearLayout.LayoutParams(-1, dp(host, look, 44)));
        }
        options.addView(scopeBox);

        Runnable paintMode = () -> {
            boolean globalMode = modes.getCheckedRadioButtonId() == globalId;
            for (CheckBox check : scopeChecks) {
                check.setEnabled(editable && !globalMode);
                check.setAlpha(editable && !globalMode ? 1f : .72f);
                if (globalMode) check.setChecked(true);
            }
        };
        modes.setOnCheckedChangeListener((group, checkedId) -> paintMode.run());
        paintMode.run();

        final CheckBox[] checks = new CheckBox[7];
        for (int i = 0; i < 7; i++) {
            CheckBox check = new CheckBox(host);
            check.setText(T.s("周") + T.s(Rule.DAY_NAMES[i]));
            check.setTextSize(15);
            check.setTextColor(look.text());
            check.setChecked((current & (1 << i)) != 0);
            check.setGravity(Gravity.CENTER_VERTICAL);
            check.setMinHeight(dp(host, look, 48));
            check.setPadding(dp(host, look, 8), 0, dp(host, look, 8), 0);
            check.setEnabled(editable);
            checks[i] = check;
            options.addView(check, new LinearLayout.LayoutParams(-1, dp(host, look, 48)));
        }
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, dp(host, look, 390)));

        final AlertDialog dialog = ThemeStore.dialog(host)
                .setTitle(T.s("全局按星期停用"))
                .setView(sheet)
                .setNegativeButton(editable ? T.s("取消") : T.s("关闭"), null)
                .setPositiveButton(T.s("保存"), null)
                .create();
        dialog.setOnShowListener(shown -> {
            if (!editable) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
                return;
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!TimeWindow.canEdit(host)) {
                    toast(host, T.f("全局停用只能在调整时段 %s 内修改", TimeWindow.label(host)));
                    return;
                }
                int next = 0;
                for (int i = 0; i < 7; i++) if (checks[i].isChecked()) next |= 1 << i;
                int scopes = modes.getCheckedRadioButtonId() == globalId
                        ? FreezeWeekdayPolicy.ALL_SCOPES : 0;
                if (modes.getCheckedRadioButtonId() == customId) {
                    for (int i = 0; i < scopeChecks.length; i++)
                        if (scopeChecks[i].isChecked()) scopes |= scopeValues[i];
                }
                if (next != 0 && scopes == 0) {
                    toast(host, T.s("自定义范围至少选择一项；不需要暂停时请取消全部星期"));
                    return;
                }
                try {
                    FreezeWeekdayPolicy.setPolicy(host, next, scopes);
                } catch (RuntimeException error) {
                    toast(host, error.getMessage());
                    return;
                }
                dialog.dismiss();
                if (after != null) after.run();
                toast(host, next == 0
                        ? T.s("已恢复全部星期的五类限制")
                        : T.f("已更新按星期停用：%s · %s",
                        Rule.daysText(next), FreezeWeekdayPolicy.scopesText(scopes)));
            });
        });
        dialog.show();
    }

    private static int dp(Activity host, Ui look, float value) {
        return look.dp(value);
    }

    private static TextView text(Activity host, Ui look, String value, int size, int color) {
        TextView view = new TextView(host);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static LinearLayout.LayoutParams gap(Activity host, Ui look, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(host, look, bottom);
        return lp;
    }

    private static void toast(Activity host, String value) {
        Toast.makeText(host, value == null ? T.s("操作未完成") : value, Toast.LENGTH_SHORT).show();
    }
}
