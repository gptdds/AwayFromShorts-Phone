package com.afs.host.plugins;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.afs.guard.T;
import com.afs.guard.ThemedActivity;
import com.afs.guard.Ui;
import com.afs.guard.plugins.AppPlugin;
import com.afs.guard.plugins.BuiltInPluginStore;
import com.afs.guard.plugins.DockPages;
import com.afs.pluginapi.PluginProtocol;
import com.afs.pluginapi.PluginStatus;

import java.util.ArrayList;
import java.util.List;

/** Generic plugin management. It never names a specific plugin package. */
public final class PluginManagerActivity extends ThemedActivity {
    private Ui ui;
    private LinearLayout body;
    private LinearLayout builtInList;
    private LinearLayout pluginList;
    private boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        build();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!destroyed) refresh();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        ui.page(scroll);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(36));
        scroll.addView(body);
        setContentView(scroll);

        TextView back = new TextView(this);
        back.setText("‹  " + T.s("返回"));
        back.setTextSize(14);
        back.setTextColor(ui.accent());
        back.setPadding(0, ui.dp(6), 0, ui.dp(12));
        back.setOnClickListener(v -> finish());
        body.addView(back);
        body.addView(ui.title(T.s("插件管理")));
        TextView note = new TextView(this);
        note.setText(T.s("屏幕使用统计已内置，无需安装独立 APK。统计访问权限由系统管理。"));
        note.setTextSize(12);
        note.setTextColor(ui.text2());
        note.setPadding(0, ui.dp(6), 0, ui.dp(12));
        body.addView(note);

        LinearLayout builtIn = ui.card();
        TextView builtInTitle = new TextView(this);
        builtInTitle.setText(T.s("内置页面插件"));
        builtInTitle.setTextSize(16);
        builtInTitle.setTypeface(Typeface.DEFAULT_BOLD);
        builtInTitle.setTextColor(ui.text());
        builtIn.addView(builtInTitle);
        TextView builtInNote = new TextView(this);
        builtInNote.setText(T.s("关闭后隐藏对应入口；学习记录、统计和冻结联动都会保留。「冻结面板」可单独停用，打卡与所有卡片功能不受影响。"));
        builtInNote.setTextSize(12);
        builtInNote.setTextColor(ui.text2());
        builtInNote.setPadding(0, ui.dp(4), 0, ui.dp(6));
        builtIn.addView(builtInNote);
        builtInList = new LinearLayout(this);
        builtInList.setOrientation(LinearLayout.VERTICAL);
        builtIn.addView(builtInList);
        renderBuiltIn();
        body.addView(builtIn, ui.gap(10));

        TextView externalTitle = new TextView(this);
        externalTitle.setText(T.s("外部插件"));
        externalTitle.setTextSize(12);
        externalTitle.setTypeface(Typeface.DEFAULT_BOLD);
        externalTitle.setTextColor(ui.text3());
        externalTitle.setPadding(ui.dp(4), ui.dp(16), 0, ui.dp(6));
        body.addView(externalTitle);
        pluginList = new LinearLayout(this);
        pluginList.setOrientation(LinearLayout.VERTICAL);
        body.addView(pluginList);
    }

    private void refresh() {
        renderBuiltIn();
        HostRuntime.get(this).registry().refreshAsync(() -> {
            if (!destroyed) render();
        });
    }

    private void renderBuiltIn() {
        if (builtInList == null) return;
        builtInList.removeAllViews();
        for (final AppPlugin plugin : BuiltInPluginStore.plugins()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, ui.dp(8), 0, ui.dp(8));

            LinearLayout copy = new LinearLayout(this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(0, ui.dp(3), ui.dp(8), ui.dp(3));
            boolean enabled = BuiltInPluginStore.enabled(this, plugin.id());
            TextView name = new TextView(this);
            name.setText(plugin.label() + T.s(enabled ? " · 内置 · 已启用" : " · 内置 · 已停用"));
            name.setTextSize(14);
            name.setTextColor(ui.text());
            copy.addView(name);
            TextView detail = new TextView(this);
            detail.setText(enabled ? T.s("点击打开页面") : T.s("插件已停用"));
            detail.setTextSize(11);
            detail.setTextColor(enabled ? ui.accent() : ui.text3());
            copy.addView(detail);
            copy.setClickable(true);
            copy.setFocusable(true);
            copy.setOnClickListener(v -> {
                if (!BuiltInPluginStore.enabled(this, plugin.id())) {
                    Toast.makeText(this, T.s("插件已停用"), Toast.LENGTH_SHORT).show();
                    return;
                }
                int index = DockPages.indexOfPlugin(plugin.id());
                Intent intent = index<0?new Intent(this,plugin.activity()):DockPages.intent(this, index, false);
                if (intent != null) startActivity(intent);
            });
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

            CheckBox toggle = new CheckBox(this);
            toggle.setText(enabled ? T.s("已启用") : T.s("已停用"));
            toggle.setTextSize(13);
            toggle.setTextColor(enabled ? ui.green() : ui.text3());
            toggle.setChecked(enabled);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (checked == BuiltInPluginStore.enabled(this, plugin.id())) return;
                if (!BuiltInPluginStore.setEnabled(this, plugin.id(), checked)) {
                    Toast.makeText(this, T.s("保存失败，请重试"), Toast.LENGTH_LONG).show();
                }
                runOnUiThread(this::renderBuiltIn);
            });
            row.addView(toggle);
            builtInList.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void render() {
        pluginList.removeAllViews();
        HostRuntime runtime = HostRuntime.get(this);
        List<PluginEntry> entries = new ArrayList<>(runtime.registry().entries());
        entries.removeIf(e->"com.afs.plugin.usagestats".equals(e.packageName()));
        if (entries.isEmpty()) {
            LinearLayout card = ui.card();
            TextView text = new TextView(this);
            text.setText(T.s("没有其他外部插件"));
            text.setTextSize(13);
            text.setTextColor(ui.text2());
            text.setLineSpacing(ui.dp(4), 1f);
            card.addView(text);
            Button refresh = button(T.s("刷新"));
            refresh.setOnClickListener(v -> refresh());
            card.addView(refresh, new LinearLayout.LayoutParams(-1, ui.dp(42)));
            pluginList.addView(card, ui.gap(10));
        } else {
            for (PluginEntry entry : entries) pluginList.addView(entryCard(entry), ui.gap(10));
        }
        Button refresh = button(T.s("刷新目录"));
        refresh.setOnClickListener(v -> refresh());
        pluginList.addView(refresh, new LinearLayout.LayoutParams(-1, ui.dp(42)));
    }

    private View entryCard(PluginEntry entry) {
        LinearLayout card = ui.card();
        TextView title = new TextView(this);
        title.setText(entry.hasError() ? T.s("不可用插件") : displayName(entry));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ui.text());
        card.addView(title);

        TextView meta = new TextView(this);
        meta.setText(entry.packageName()
                + "\n" + T.s("版本") + " " + (entry.fingerprint == null ? "-" : entry.fingerprint.versionCode)
                + " · " + T.s("发布者") + " " + shortSigner(entry)
                + "\n" + T.s("协议") + " " + (entry.manifest == null ? "-"
                : entry.manifest.protocolMin + "–" + entry.manifest.protocolMax));
        meta.setTextSize(11);
        meta.setTextColor(ui.text3());
        meta.setPadding(0, ui.dp(4), 0, ui.dp(8));
        card.addView(meta);
        if(HostRuntime.get(this).registry().stateStore().storageFailed(entry.packageName())) {
            TextView failed=new TextView(this);failed.setText(T.s("无法保存插件设置，当前访问已阻断。请重试保存停用，再重新启用。"));failed.setTextColor(ui.red());card.addView(failed);
            Button retry=button(T.s("重试保存停用"));retry.setOnClickListener(v->{
                boolean saved=HostRuntime.get(this).registry().stateStore().retryDisable(entry.packageName());
                HostRuntime.get(this).sessions().invalidatePackage(entry.packageName(),PluginStatus.REVOKED);
                if(!saved)Toast.makeText(this,T.s("保存失败，当前会话已阻断，请重试"),Toast.LENGTH_LONG).show();refresh();
            });card.addView(retry);return card;
        }

        if (entry.hasError()) {
            TextView error = new TextView(this);
            error.setText(T.s("拒绝原因：") + entry.error);
            error.setTextSize(12);
            error.setTextColor(ui.red());
            card.addView(error);
            return card;
        }

        TextView scope = new TextView(this);
        scope.setText(T.s("数据范围：") + scopeOf(entry.manifest.capabilities));
        scope.setTextSize(12);
        scope.setTextColor(ui.text2());
        scope.setPadding(0, 0, 0, ui.dp(8));
        card.addView(scope);

        PluginState state = entry.state;
        TextView status = new TextView(this);
        status.setText(state == null ? T.s("未启用")
                : (state.enabled ? T.s("已启用") : T.s("已停用"))
                + (state.needsConfirmation ? T.s(" · 需要重新确认") : ""));
        status.setTextSize(12);
        status.setTextColor(state != null && state.enabled ? ui.green() : ui.text3());
        status.setPadding(0, 0, 0, ui.dp(8));
        card.addView(status);

        if (state == null || !state.enabled) {
            Button enable = button(T.s("确认启用"));
            enable.setOnClickListener(v -> confirmEnable(entry));
            card.addView(enable, new LinearLayout.LayoutParams(-1, ui.dp(44)));
        } else {
            if(entry.manifest.capabilities.contains(PluginProtocol.CAP_READ))card.addView(grantRow(entry, true), new LinearLayout.LayoutParams(-1, -2));
            if(entry.manifest.capabilities.contains(PluginProtocol.CAP_REQUEST))card.addView(grantRow(entry, false), new LinearLayout.LayoutParams(-1, -2));
            Button disable = button(T.s("停用插件"));
            disable.setOnClickListener(v -> {
                HostSessions sessions = HostRuntime.get(this).sessions();
                List<String> leases = sessions.activeLeaseIds(entry.packageName());
                HostRuntime.get(this).registry().stateStore()
                        .disable(entry.packageName());
                if(HostRuntime.get(this).registry().stateStore().storageFailed(entry.packageName()))
                    Toast.makeText(this,T.s("保存失败，当前会话已阻断，请重试"),Toast.LENGTH_LONG).show();
                disable.setEnabled(false);
                sessions.invalidatePackage(entry.packageName(), PluginStatus.REVOKED).thenRun(() ->
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        HostPluginRouter.notifyClose(this, entry, leases);
                        refresh();
                    }));
            });
            card.addView(disable, new LinearLayout.LayoutParams(-1, ui.dp(42)));
        }
        return card;
    }

    private View grantRow(PluginEntry entry, boolean read) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        TextView name = new TextView(this);
        name.setText(read ? T.s("读取聚合统计（usage.summary.read）")
                : T.s("前往宿主授权页（usage.access.request）"));
        name.setTextSize(13);
        name.setTextColor(ui.text());
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        boolean granted = read ? entry.state.readGranted : entry.state.requestGranted;
        TextView action = new TextView(this);
        action.setText(granted ? T.s("已授予 · 点击撤销") : T.s("未授予 · 点击授予"));
        action.setTextSize(12);
        action.setTextColor(granted ? ui.green() : ui.accent());
        action.setOnClickListener(v -> {
            boolean changed=HostRuntime.get(this).registry().stateStore().setGrant(entry,read,!granted);
            if(!changed){
                boolean failed=HostRuntime.get(this).registry().stateStore().storageFailed(entry.packageName());
                if(failed)HostRuntime.get(this).sessions().invalidatePackage(entry.packageName(),PluginStatus.REVOKED);
                Toast.makeText(this,T.s(failed?"保存失败，当前会话已阻断，请重试":"插件状态已变化，请重新操作"),Toast.LENGTH_LONG).show();refresh();return;
            }
            action.setEnabled(false);
            HostRuntime.get(this).sessions().invalidatePackage(entry.packageName(), PluginStatus.REVOKED)
                    .thenRun(() -> runOnUiThread(() -> { if (!destroyed) refresh(); }));
        });
        row.addView(action);
        return row;
    }

    private void confirmEnable(PluginEntry entry) {
        StringBuilder message = new StringBuilder();
        message.append(T.s("发布者：")).append(shortSigner(entry)).append("\n");
        message.append(T.s("版本：")).append(entry.fingerprint.versionCode).append("\n");
        message.append(T.s("协议：")).append(entry.manifest.protocolMin).append("–")
                .append(entry.manifest.protocolMax).append("\n\n");
        message.append(T.s("启用后该插件可以：\n"));
        if(entry.manifest.capabilities.contains(PluginProtocol.CAP_READ))message.append(T.s("· 读取宿主聚合的应用名称与时长（最多 40 排行 + 7 天趋势）\n"));
        if(entry.manifest.capabilities.contains(PluginProtocol.CAP_READ))message.append(T.s("· 已同步的电脑应用时长（日、月和累计汇总）\n"));
        if(entry.manifest.capabilities.contains(PluginProtocol.CAP_REQUEST))message.append(T.s("· 通过宿主授权页前往系统「使用情况访问」设置\n\n"));
        message.append(T.s("插件不能读取原始事件、规则、笔记或凭据，也不能修改冻结规则。已授予的权限可分别撤销。"));
        new AlertDialog.Builder(this)
                .setTitle(T.s("启用 ") + displayName(entry) + " ?")
                .setMessage(message.toString())
                .setNegativeButton(T.s("取消"), null)
                .setPositiveButton(T.s("启用并授权"), (dialog, which) -> {
                    if(!HostRuntime.get(this).registry().confirm(entry)){
                        boolean failed=HostRuntime.get(this).registry().stateStore().storageFailed(entry.packageName());
                        if(failed)HostRuntime.get(this).sessions().invalidatePackage(entry.packageName(),PluginStatus.REVOKED);
                        Toast.makeText(this,T.s(failed?"保存失败，当前会话已阻断，请重试":"插件安装或授权状态已变化，请重新确认"),Toast.LENGTH_LONG).show();
                    }
                    refresh();
                })
                .show();
    }

    private Button button(String label) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(ui.accent());
        view.setPadding(0, ui.dp(11), 0, ui.dp(11));
        view.setOnClickListener(v -> Toast.makeText(this, T.s("请使用上方按钮"), Toast.LENGTH_SHORT).show());
        return view;
    }

    private String displayName(PluginEntry entry) {
        if ("usage-stats".equals(entry.pluginId())) return T.s("屏幕使用统计");
        return entry.pluginId().isEmpty() ? entry.packageName() : entry.pluginId();
    }

    private String shortSigner(PluginEntry entry) {
        if (entry.fingerprint == null) return "-";
        String signer = entry.fingerprint.signerSha256;
        String prefix = signer.length() > 16 ? signer.substring(0, 16) + "…" : signer;
        return PluginIdentity.trusted(this, signer) ? T.s("官方签名 ") + prefix : T.s("不可信 ") + prefix;
    }

    private static String capsCsv(PluginEntry entry) {
        StringBuilder text = new StringBuilder();
        for (String cap : entry.manifest.capabilities) {
            if (text.length() > 0) text.append(',');
            text.append(cap);
        }
        return text.toString();
    }

    private static String scopeOf(List<String> capabilities) {
        StringBuilder text = new StringBuilder();
        for (String cap : capabilities) {
            if (text.length() > 0) text.append(" · ");
            if (PluginProtocol.CAP_READ.equals(cap)) text.append("usage.summary.read");
            else if (PluginProtocol.CAP_REQUEST.equals(cap)) text.append("usage.access.request");
            else text.append(cap);
        }
        return text.toString();
    }
}

