package com.afs.guard;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 跨设备共享额度的配置面板。
 *
 * 一个手机可以绑定多个本地额度组，每组接一个电脑共享池：
 * 视频组接电脑视频池，游戏组接电脑游戏池。面板只负责连接和配对，
 * 池子的分钟数与判定都以电脑端对应规则为准。
 */
final class LanQuotaPanel {

    private LanQuotaPanel() {
    }

    static void show(final ThemedActivity host, final Runnable after) {
        show(host, after, null, null);
    }

    /** 从某个额度格子的「设为跨设备共享」直接进来：自动打开选池流程。 */
    static void showForGroup(final ThemedActivity host, final String groupId, final String groupName, final Runnable after) {
        show(host, after, groupId, groupName);
    }

    private static void show(final ThemedActivity host, final Runnable after, final String focusGroup, final String focusName) {
        if (host == null || host.isFinishing()) {
            return;
        }
        final Ui ui = new Ui(host);
        final Handler main = new Handler(Looper.getMainLooper());

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(2), ui.dp(20), ui.dp(2));

        final AlertDialog dialog = ThemeStore.dialog(host)
                .setTitle(Lang.t(host, "跨设备共享额度", "Shared quota"))
                .setView(box)
                .setNegativeButton(T.s("关闭"), null)
                .create();

        final TextView switchValue = value(host, ui);
        final TextView serverValue = value(host, ui);
        final TextView tokenValue = value(host, ui);
        final TextView bindingValue = value(host, ui);
        final TextView state = new TextView(host);
        state.setTextSize(11);
        state.setTextColor(ui.text3());
        state.setPadding(0, ui.dp(10), 0, ui.dp(2));
        // 每个绑定的两端明细单独一块：这是「谁花了这笔时间」的答案。
        final TextView detail = new TextView(host);
        detail.setTextSize(11);
        detail.setTextColor(ui.text3());
        detail.setPadding(0, ui.dp(6), 0, ui.dp(2));

        final Runnable paint = () -> {
            boolean on = LanQuota.enabled(host);
            switchValue.setText(on ? T.s("开") : T.s("关"));
            switchValue.setTextColor(on ? ui.accent() : ui.text3());
            serverValue.setText(LanQuota.host(host).isEmpty()
                    ? T.s("未设置") : LanQuota.host(host) + ":" + LanQuota.port(host));
            tokenValue.setText(LanQuota.token(host).isEmpty() ? T.s("未设置") : maskToken(LanQuota.token(host)));

            JSONArray list = LanQuota.bindings(host);
            if (list.length() == 0) {
                bindingValue.setText(T.s("未选择"));
            } else if (list.length() == 1) {
                JSONObject only = list.optJSONObject(0);
                String local = only == null ? "" : only.optString("groupName", "");
                String pool = only == null ? "" : only.optString("poolName", "");
                bindingValue.setText(local + (pool.isEmpty() ? "" : " → " + pool));
            } else {
                bindingValue.setText(T.f("%d 个", list.length()));
            }

            if (!LanQuota.active(host)) {
                state.setText(list.length() == 0
                        ? T.s("填好电脑地址和令牌，再绑定一个额度格子，这个功能才会开始工作。")
                        : T.s("绑定已保存；打开「启用」并确认地址和令牌后开始同步。"));
            } else {
                int online = 0;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.optJSONObject(i);
                    if (item != null && LanQuota.fresh(host, item.optString("group", ""))) {
                        online++;
                    }
                }
                if (online == 0) {
                    String err = LanQuota.lastError(host);
                    state.setText(err.isEmpty()
                            ? T.s("还没有连上过电脑。手机和电脑要在同一个 WiFi 下。")
                            : T.f("连不上电脑：%s", err));
                } else {
                    state.setText(T.f("已绑定 %d 个共享格子 · %d 个已同步", list.length(), online));
                }
            }
            detail.setText(detailText(host));
        };

        box.addView(row(host, ui, T.s("启用"), switchValue, () -> {
            boolean next = !LanQuota.enabled(host);
            LanQuota.setEnabled(host, next);
            if (!next) {
                LanQuota.clearCache(host);
            }
            paint.run();
        }));

        box.addView(row(host, ui, T.s("电脑地址"), serverValue, () -> {
            String hint = LanQuota.host(host).isEmpty()
                    ? "192.168.1.5:8738" : LanQuota.host(host) + ":" + LanQuota.port(host);
            input(host, ui, T.s("电脑地址"), hint, hint, value -> {
                if (!LanQuota.setServer(host, value, LanQuota.token(host))) {
                    toast(host, T.s("只能填局域网地址，比如 192.168.1.5"));
                    return;
                }
                paint.run();
            });
        }));

        box.addView(row(host, ui, T.s("连接令牌"), tokenValue, () ->
                input(host, ui, T.s("连接令牌"), T.s("电脑端设置里那一串"), LanQuota.token(host), value -> {
                    LanQuota.setToken(host, value);
                    LanQuota.clearCache(host);
                    paint.run();
                })));

        box.addView(row(host, ui, T.s("共享的额度格子"), bindingValue, () ->
                manageBindings(host, ui, main, state, () -> {
                    paint.run();
                    if (after != null) {
                        after.run();
                    }
                })));

        TextView probe = new TextView(host);
        probe.setText(T.s("测试连接"));
        probe.setTextSize(13);
        probe.setTextColor(ui.accent());
        probe.setGravity(Gravity.CENTER);
        probe.setPadding(0, ui.dp(14), 0, ui.dp(14));
        probe.setOnClickListener(v -> probe(host, ui, main, state));
        box.addView(probe);
        box.addView(state);
        box.addView(detail);

        TextView note = new TextView(host);
        note.setTextSize(11);
        note.setTextColor(ui.text3());
        note.setPadding(0, ui.dp(8), 0, 0);
        note.setText(Lang.t(host,
                "每个本地额度格子绑定一个电脑共享池。视频组接电脑视频池、游戏组接电脑游戏池，各自合计、互不影响。池子的分钟数由电脑端对应规则决定。",
                "Each local quota bucket binds to one PC pool. Video and game groups keep separate totals and do not drain each other. The PC rule decides each pool budget."));
        box.addView(note);

        // 每 2 秒重画一次：轮询是 3 秒一轮，面板开着的时候数字该跟着动
        final Runnable tick = new Runnable() {
            @Override public void run() {
                paint.run();
                main.postDelayed(this, 2000);
            }
        };
        dialog.setOnDismissListener(d -> main.removeCallbacksAndMessages(null));

        paint.run();
        dialog.show();
        main.postDelayed(tick, 2000);

        if (focusGroup != null && !focusGroup.isEmpty() && LanQuota.bindingForGroup(host, focusGroup) == null) {
            main.postDelayed(() -> pickPoolForGroup(host, ui, main,
                    focusGroup, focusName == null ? "" : focusName, state, () -> {
                        paint.run();
                        if (after != null) {
                            after.run();
                        }
                    }), 300);
        }
    }

    /* ---------------- 绑定管理 ---------------- */

    private static void manageBindings(final ThemedActivity host, final Ui ui, final Handler main,
                                       final TextView state, final Runnable changed) {
        JSONArray list = LanQuota.bindings(host);
        if (list.length() == 0) {
            pickGroupForBinding(host, ui, main, state, changed);
            return;
        }
        final String[] labels = new String[list.length()];
        final JSONObject[] items = new JSONObject[list.length()];
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            items[i] = item;
            String local = item == null ? "" : item.optString("groupName", "");
            String pool = item == null ? "" : item.optString("poolName", "");
            if (pool.isEmpty() && item != null) {
                pool = item.optString("pool", "");
            }
            labels[i] = local + " → " + (pool.isEmpty() ? T.s("待电脑识别") : pool);
        }
        ThemeStore.dialog(host)
                .setTitle(T.s("已绑定的共享格子"))
                .setItems(labels, (d, which) -> bindingOptions(host, ui, main, items[which], state, changed))
                .setNeutralButton(T.s("添加绑定"), (d, w) -> pickGroupForBinding(host, ui, main, state, changed))
                .setNegativeButton(T.s("关闭"), null)
                .show();
    }

    private static void bindingOptions(final ThemedActivity host, final Ui ui, final Handler main,
                                       final JSONObject binding, final TextView state, final Runnable changed) {
        if (binding == null) {
            return;
        }
        final String groupId = binding.optString("group", "");
        String groupName = binding.optString("groupName", "");
        String poolName = binding.optString("poolName", "");
        if (poolName.isEmpty()) {
            poolName = binding.optString("pool", "");
        }
        ThemeStore.dialog(host)
                .setTitle(groupName + " → " + (poolName.isEmpty() ? T.s("待电脑识别") : poolName))
                .setItems(new String[]{T.s("更换电脑池"), T.s("解除绑定")}, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        pickPoolForGroup(host, ui, main, groupId, groupName, state, changed);
                    } else {
                        LanQuota.removeBinding(host, groupId);
                        toast(host, T.s("已解除绑定"));
                        if (changed != null) {
                            changed.run();
                        }
                    }
                })
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    private static void pickGroupForBinding(final ThemedActivity host, final Ui ui, final Handler main,
                                            final TextView state, final Runnable changed) {
        DailyQuotaData data = DailyQuotaStore.snapshot(host);
        JSONArray groups = data.groups();
        if (groups.length() == 0) {
            toast(host, T.s("先在使用额度里建一个额度格子"));
            return;
        }
        final String[] ids = new String[groups.length()];
        final String[] names = new String[groups.length()];
        String[] labels = new String[groups.length()];
        int checked = -1;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            ids[i] = group.optString("id");
            names[i] = group.optString("name");
            boolean bound = LanQuota.bindingForGroup(host, ids[i]) != null;
            labels[i] = names[i] + " · " + group.optInt("minutes") + T.s(" 分钟")
                    + (bound ? T.s("（已绑定）") : "");
            if (bound) {
                checked = i;
            }
        }
        ThemeStore.dialog(host).setTitle(T.s("绑定哪个本地额度格子"))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    pickPoolForGroup(host, ui, main, ids[which], names[which], state, changed);
                })
                .setNegativeButton(T.s("取消"), null).show();
    }

    private static void pickPoolForGroup(final ThemedActivity host, final Ui ui, final Handler main,
                                         final String groupId, final String groupName,
                                         final TextView state, final Runnable changed) {
        if (LanQuota.host(host).isEmpty() || LanQuota.token(host).isEmpty()) {
            toast(host, T.s("先填电脑地址和令牌"));
            return;
        }
        state.setText(T.s("正在读取电脑上的共享池…"));
        final String hostValue = LanQuota.host(host);
        final int port = LanQuota.port(host);
        final String token = LanQuota.token(host);
        new Thread(() -> {
            JSONArray pools = null;
            String error = null;
            try {
                JSONObject o = LanQuota.probe(hostValue, port, token);
                pools = o.optJSONArray("pools");
                if ((pools == null || pools.length() == 0) && o.optInt("poolMinutes", 0) > 0) {
                    // 旧版电脑端只有顶层单池，合成一个供选择。
                    JSONObject only = new JSONObject();
                    try {
                        only.put("id", o.optString("sharedRule", ""));
                        only.put("name", "共享池");
                        only.put("poolMinutes", o.optInt("poolMinutes", 0));
                        only.put("pcUsed", o.optDouble("pcUsed", 0));
                        only.put("phoneUsed", o.optDouble("phoneUsed", 0));
                        only.put("total", o.optDouble("total", 0));
                        only.put("exhausted", o.optBoolean("exhausted", false));
                    } catch (Exception ignored) {
                    }
                    pools = new JSONArray();
                    pools.put(only);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final JSONArray found = pools;
            final String failure = error;
            main.post(() -> {
                if (failure != null) {
                    state.setText(T.f("连不上：%s", failure));
                    toast(host, T.f("连不上：%s", failure));
                    return;
                }
                if (found == null || found.length() == 0) {
                    state.setText(T.s("电脑端还没有跨设备格子"));
                    toast(host, T.s("电脑端还没有跨设备格子"));
                    return;
                }
                final String[] ids = new String[found.length()];
                final String[] names = new String[found.length()];
                String[] labels = new String[found.length()];
                int checked = -1;
                String current = LanQuota.poolForGroup(host, groupId);
                for (int i = 0; i < found.length(); i++) {
                    JSONObject pool = found.optJSONObject(i);
                    ids[i] = pool.optString("id", "");
                    names[i] = pool.optString("name", "");
                    labels[i] = names[i] + " · " + pool.optInt("poolMinutes", 0) + T.s(" 分钟 · 电脑已用 ")
                            + minutes(pool.optDouble("pcUsed", 0)) + T.s(" 分钟");
                    if (!current.isEmpty() && current.equals(ids[i])) {
                        checked = i;
                    }
                }
                ThemeStore.dialog(host)
                        .setTitle(T.s("把「") + groupName + T.s("」绑定到哪个电脑池"))
                        .setSingleChoiceItems(labels, checked, (d, which) -> {
                            LanQuota.setBinding(host, groupId, groupName, ids[which], names[which]);
                            LanQuota.setEnabled(host, true);
                            state.setText(T.s("已绑定：") + groupName + " → " + names[which]);
                            toast(host, T.s("已绑定：") + groupName + " → " + names[which]);
                            if (changed != null) {
                                changed.run();
                            }
                            d.dismiss();
                        })
                        .setNegativeButton(T.s("取消"), null).show();
            });
        }, "afs-lan-pools").start();
    }

    /* ---------------- 两端明细 ---------------- */

    /** 每个绑定的「手机这一份」本地算，「电脑这一份」用该池的轮询缓存。 */
    private static String detailText(ThemedActivity host) {
        JSONArray list = LanQuota.bindings(host);
        if (!LanQuota.active(host) || list.length() == 0) {
            return "";
        }
        DailyQuotaData data = DailyQuotaStore.snapshot(host);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            JSONObject binding = list.optJSONObject(i);
            if (binding == null) {
                continue;
            }
            String groupId = binding.optString("group", "");
            String local = binding.optString("groupName", "");
            String pool = binding.optString("poolName", "");
            if (pool.isEmpty()) {
                pool = binding.optString("pool", "");
            }
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append(local.isEmpty() ? groupId : local).append(" → ")
                    .append(pool.isEmpty() ? T.s("待电脑识别") : pool);
            if (!LanQuota.fresh(host, groupId)) {
                out.append('\n').append(T.s("未连上电脑"));
                continue;
            }
            JSONObject group = data.groupById(groupId);
            out.append('\n').append(T.f("池子 %d 分钟 · 已用 %.1f 分钟 · 剩 %d 分钟",
                    LanQuota.minutes(host, groupId), LanQuota.total(host, groupId) / 60000.0,
                    Math.max(0L, LanQuota.minutes(host, groupId) * 60L - LanQuota.total(host, groupId) / 1000L) / 60));
            out.append('\n').append(T.s("手机这一份：")).append(appsText(LanQuota.appDetail(host, data, group)));
            out.append('\n').append(T.s("电脑这一份：")).append(appsText(LanQuota.pcApps(host, groupId)));
            if ("gist".equals(LanQuota.source(host, groupId))) {
                out.append('\n').append(T.s("（离线估算，数字偏乐观）"));
            }
        }
        return out.toString();
    }

    static String appsText(JSONArray list) {
        if (list == null || list.length() == 0) {
            return T.s("今天还没有用量");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            double seconds = item.optDouble("seconds", 0);
            // 不到 6 秒的条目在列表里只会显示成「0.0 分钟」，占位置又没信息
            if (seconds < 6) {
                continue;
            }
            if (out.length() > 0) {
                out.append(T.s("、"));
            }
            out.append(item.optString("name")).append(' ').append(minutes(seconds)).append(T.s(" 分钟"));
        }
        return out.length() == 0 ? T.s("今天还没有用量") : out.toString();
    }

    static String minutes(double seconds) {
        double m = seconds / 60.0;
        return m >= 100 ? String.valueOf(Math.round(m)) : String.format(T.locale(), "%.1f", m);
    }

    /* ---------------- 小部件 ---------------- */

    private static TextView value(ThemedActivity host, Ui ui) {
        TextView view = new TextView(host);
        view.setTextSize(13);
        view.setTextColor(ui.accent());
        return view;
    }

    private static LinearLayout row(ThemedActivity host, Ui ui, String title, TextView value, Runnable onClick) {
        LinearLayout line = new LinearLayout(host);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ui.dp(12), 0, ui.dp(12));
        TextView name = new TextView(host);
        name.setText(title);
        name.setTextSize(14);
        name.setTextColor(ui.text());
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(value);
        if (onClick != null) {
            line.setOnClickListener(v -> onClick.run());
        }
        return line;
    }

    private interface TextSink {
        void accept(String value);
    }

    private static void input(ThemedActivity host, Ui ui, String title, String hint, String current, final TextSink onOk) {
        final EditText input = new EditText(host);
        input.setSingleLine(true);
        input.setHint(hint);
        if (current != null) {
            input.setText(current);
        }
        input.setSelection(input.getText().length());
        LinearLayout wrap = new LinearLayout(host);
        wrap.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ThemeStore.dialog(host).setTitle(title).setView(wrap)
                .setNegativeButton(T.s("取消"), null)
                .setPositiveButton(T.s("保存"), (d, w) -> onOk.accept(input.getText().toString().trim()))
                .show();
    }

    private static void probe(final ThemedActivity host, Ui ui, final Handler main, final TextView state) {
        if (LanQuota.host(host).isEmpty() || LanQuota.token(host).isEmpty()) {
            toast(host, T.s("先填电脑地址和令牌"));
            return;
        }
        state.setText(T.s("正在连接电脑…"));
        final String hostValue = LanQuota.host(host);
        final int port = LanQuota.port(host);
        final String token = LanQuota.token(host);
        new Thread(() -> {
            String text;
            try {
                JSONObject o = LanQuota.probe(hostValue, port, token);
                JSONArray pools = o.optJSONArray("pools");
                if (pools != null && pools.length() > 0) {
                    StringBuilder out = new StringBuilder(T.f("连上了 · %d 个共享池", pools.length()));
                    for (int i = 0; i < pools.length() && i < 3; i++) {
                        JSONObject pool = pools.optJSONObject(i);
                        out.append('\n').append(pool.optString("name"))
                                .append(" · ").append(pool.optInt("poolMinutes", 0)).append(T.s(" 分钟"))
                                .append(" · ").append(T.s("电脑已用 "))
                                .append(minutes(pool.optDouble("pcUsed", 0))).append(T.s(" 分钟"));
                    }
                    text = out.toString();
                } else {
                    text = T.f("连上了 · 池子 %d 分钟，电脑已用 %.1f，手机已用 %.1f",
                            o.optInt("poolMinutes", 0),
                            o.optDouble("pcUsed", 0) / 60.0,
                            o.optDouble("phoneUsed", 0) / 60.0);
                }
            } catch (Exception error) {
                text = T.f("连不上：%s", error.getMessage() == null ? error.toString() : error.getMessage());
            }
            final String out = text;
            main.post(() -> state.setText(out));
        }, "afs-lan-probe").start();
    }

    static String maskToken(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        if (token.length() <= 6) {
            return token.substring(0, 1) + "***";
        }
        return token.substring(0, 4) + "****" + token.substring(token.length() - 3);
    }

    private static void toast(ThemedActivity host, String value) {
        Toast.makeText(host, value, Toast.LENGTH_SHORT).show();
    }
}
