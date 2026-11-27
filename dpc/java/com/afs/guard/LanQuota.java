package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 跨设备共享额度：手机这一侧的客户端。
 *
 * 现在一个手机可以同时绑定多个电脑池：「视频」本地额度组绑定电脑的视频池，
 * 「游戏」本地额度组绑定电脑的游戏池。每个绑定各报各的用量，各看各的剩余，
 * 一个池用尽不会连带另一个池。
 *
 * 电脑仍然是权威：它每秒知道自己用了多久，也是唯一能杀进程的一端；
 * 手机每次轮询把自己各个组的用量报上去，同时拿回每个池的「电脑已用 + 合计」。
 *
 * 断网时不能瞎判：
 *   · 缓存 30 秒内 → 用该池的合计判超
 *   · 缓存过期     → 交回本地判定，宁可少冻也不把用户永久锁在外面
 */
final class LanQuota {

    private static final String SP = "afs_lan_quota";
    private static final String TAG = "AfsLanQuota";

    /** 3 秒一轮；两端都是这个量级，合计误差不超过一次轮询。 */
    static final long POLL_MS = 3000L;
    /** 同一时刻只允许一个在飞，避免弱网下排队堆积。 */
    private static final long MIN_GAP_MS = 2200L;
    /** 超过这个年龄的缓存不再参与判定（局域网：一次往返就够新了）。 */
    static final long STALE_MS = 30_000L;
    /**
     * Gist 兜底数据的老化窗口。
     * PC 每 15 分钟推一次，所以 90 分钟等于「连着 6 次没推上来」才当作无效。
     */
    static final long GIST_STALE_MS = 90 * 60_000L;
    /** Gist 拉取间隔：比局域网宽松得多，raw 本身就有 CDN 缓存。 */
    private static final long GIST_RETRY_MS = 90_000L;

    private static final int CONNECT_MS = 2500;
    private static final int READ_MS = 3500;

    private static final Object LOCK = new Object();
    private static long lastStart;
    private static long lastGistTry;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "afs-lan-quota");
        t.setDaemon(true);
        return t;
    });

    private LanQuota() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Exception ignored) {
        }
    }

    private static JSONObject binding(String group, String groupName, String pool, String poolName) {
        JSONObject out = new JSONObject();
        put(out, "group", group == null ? "" : group);
        put(out, "groupName", groupName == null ? "" : groupName);
        put(out, "pool", pool == null ? "" : pool);
        put(out, "poolName", poolName == null ? "" : poolName);
        return out;
    }

    /* ---------------- 绑定 ---------------- */

    /**
     * 本机额度组 → 电脑共享池。
     *
     * 旧版只存一个 group/groupName；第一次读到没有 bindings 的旧配置时把它转成
     * 一条 pool 为空的绑定。电脑第一次回包后会自动补上池 id，旧数据不会丢。
     */
    static JSONArray bindings(Context c) {
        SharedPreferences p = sp(c);
        String raw = p.getString("bindings", "");
        if (!raw.isEmpty()) {
            try {
                JSONArray parsed = new JSONArray(raw);
                if (parsed.length() > 0) {
                    return parsed;
                }
            } catch (Exception ignored) {
            }
        }
        JSONArray out = new JSONArray();
        String legacyGroup = p.getString("group", "");
        if (!legacyGroup.isEmpty()) {
            out.put(binding(legacyGroup, p.getString("groupName", ""), "", ""));
        }
        return out;
    }

    private static void writeBindings(Context c, JSONArray list) {
        String firstGroup = "";
        String firstName = "";
        if (list.length() > 0) {
            JSONObject first = list.optJSONObject(0);
            if (first != null) {
                firstGroup = first.optString("group", "");
                firstName = first.optString("groupName", "");
            }
        }
        sp(c).edit()
                .putString("bindings", list.toString())
                // 旧字段只镜像第一条，供旧页面 / 旧逻辑读取。
                .putString("group", firstGroup)
                .putString("groupName", firstName)
                .apply();
    }

    static JSONObject bindingForGroup(Context c, String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            return null;
        }
        JSONArray list = bindings(c);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && groupId.equals(item.optString("group", ""))) {
                return item;
            }
        }
        return null;
    }

    static String poolForGroup(Context c, String groupId) {
        JSONObject item = bindingForGroup(c, groupId);
        return item == null ? "" : item.optString("pool", "");
    }

    /** 电脑给这个池定的名字（跨设备格子 1、2…）；还没同步到时返回空串。 */
    static String poolNameForGroup(Context c, String groupId) {
        JSONObject item = bindingForGroup(c, groupId);
        return item == null ? "" : item.optString("poolName", "").trim();
    }

    static String groupForPool(Context c, String poolId) {
        if (poolId == null || poolId.isEmpty()) {
            return "";
        }
        JSONArray list = bindings(c);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item != null && poolId.equals(item.optString("pool", ""))) {
                return item.optString("group", "");
            }
        }
        return "";
    }

    /**
     * 一组本地额度只能接一个电脑池；一个电脑池也只接一组本地额度。
     * 再次保存同一组就是换池；同一个池原来绑在别的组上时，旧绑定会被替换。
     */
    static void setBinding(Context c, String groupId, String groupName, String poolId, String poolName) {
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        String pool = poolId == null ? "" : poolId;
        JSONArray next = new JSONArray();
        JSONArray old = bindings(c);
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (groupId.equals(item.optString("group", ""))) {
                continue;
            }
            if (!pool.isEmpty() && pool.equals(item.optString("pool", ""))) {
                continue;
            }
            next.put(item);
        }
        next.put(binding(groupId, groupName, pool, poolName));
        writeBindings(c, next);
    }

    static void removeBinding(Context c, String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        JSONArray next = new JSONArray();
        JSONArray old = bindings(c);
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null || groupId.equals(item.optString("group", ""))) {
                continue;
            }
            next.put(item);
        }
        writeBindings(c, next);
    }

    /** 兼容旧入口：没有绑定时先挂一条等待电脑回包补池 id 的绑定。 */
    static void setGroup(Context c, String groupId, String name) {
        if (groupId == null || groupId.isEmpty()) {
            return;
        }
        JSONObject existing = bindingForGroup(c, groupId);
        if (existing != null) {
            setBinding(c, groupId, name, existing.optString("pool", ""), existing.optString("poolName", ""));
        } else {
            setBinding(c, groupId, name, "", "");
        }
    }

    static String group(Context c) {
        JSONObject first = firstBinding(c);
        return first == null ? "" : first.optString("group", "");
    }

    static String groupName(Context c) {
        JSONObject first = firstBinding(c);
        return first == null ? "" : first.optString("groupName", "");
    }

    private static JSONObject firstBinding(Context c) {
        JSONArray list = bindings(c);
        return list.length() > 0 ? list.optJSONObject(0) : null;
    }

    /* ---------------- 连接配置 ---------------- */

    static boolean active(Context c) {
        if (!sp(c).getBoolean("enabled", false)) return false;
        return !sp(c).getString("host", "").isEmpty()
                && !sp(c).getString("token", "").isEmpty()
                && bindings(c).length() > 0;
    }

    static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    static String host(Context c) {
        return sp(c).getString("host", "");
    }

    static int port(Context c) {
        return sp(c).getInt("port", 8738);
    }

    static String token(Context c) {
        return sp(c).getString("token", "");
    }

    /** 这个额度组是不是已经绑定了某个电脑池。 */
    static boolean isShared(Context c, String groupId) {
        return active(c) && bindingForGroup(c, groupId) != null;
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("enabled", on).apply();
    }

    /** 单独存令牌：首次配置时通常先填地址再填令牌，不能要求地址已经合法。 */
    static void setToken(Context c, String token) {
        sp(c).edit().putString("token", token == null ? "" : token.trim()).apply();
    }

    /**
     * 保存服务器地址。允许直接粘 `192.168.1.5` / `192.168.1.5:8738` / `http://...`。
     * 只接受私有网段：明文 HTTP 已经为它放开了，不能让一个公网域名借这条路走明文。
     */
    static boolean setServer(Context c, String raw, String token) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("http://")) text = text.substring(7);
        if (text.startsWith("https://")) text = text.substring(8);
        int slash = text.indexOf('/');
        if (slash >= 0) text = text.substring(0, slash);
        String host = text;
        int port = 8738;
        int colon = text.lastIndexOf(':');
        if (colon > 0) {
            String tail = text.substring(colon + 1);
            try {
                int parsed = Integer.parseInt(tail);
                if (parsed > 0 && parsed < 65536) {
                    port = parsed;
                    host = text.substring(0, colon);
                }
            } catch (NumberFormatException ignored) { }
        }
        if (!privateHost(host)) return false;
        sp(c).edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("token", token == null ? "" : token.trim())
                .apply();
        return true;
    }

    /** 10/8、172.16/12、192.168/16、169.254/16 与 localhost；其余一律不当局域网看。 */
    private static boolean privateHost(String host) {
        if (host == null || host.isEmpty()) return false;
        if ("localhost".equalsIgnoreCase(host)) return true;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        int a;
        int b;
        try {
            a = Integer.parseInt(parts[0]);
            b = Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            return false;
        }
        if (a == 10) return true;
        if (a == 172 && b >= 16 && b <= 31) return true;
        if (a == 192 && b == 168) return true;
        if (a == 169 && b == 254) return true;
        return false;
    }

    /* ---------------- 每池缓存 ---------------- */

    private static JSONObject poolStates(Context c) {
        SharedPreferences p = sp(c);
        String raw = p.getString("poolStates", "");
        if (!raw.isEmpty()) {
            try {
                return new JSONObject(raw);
            } catch (Exception ignored) {
            }
        }
        // 旧版把唯一一个池摊在顶层字段里。把它们搬进 "" 这个占位池，
        // 等电脑回包补上真实 id 后再改名。
        JSONObject legacy = new JSONObject();
        if (p.contains("at")) {
            put(legacy, "minutes", p.getInt("minutes", 0));
            put(legacy, "pcUsed", p.getLong("pcUsed", 0L));
            put(legacy, "phoneUsed", p.getLong("phoneUsed", 0L));
            put(legacy, "total", p.getLong("total", 0L));
            put(legacy, "exhausted", p.getBoolean("exhausted", false));
            put(legacy, "at", p.getLong("at", 0L));
            put(legacy, "source", p.getString("source", "lan"));
            put(legacy, "pcAppsJson", p.getString("pcAppsJson", "[]"));
        }
        JSONObject out = new JSONObject();
        if (legacy.length() > 0) {
            put(out, "", legacy);
        }
        return out;
    }

    private static void writePoolStates(Context c, JSONObject states) {
        sp(c).edit().putString("poolStates", states.toString()).apply();
    }

    private static JSONObject stateForGroup(Context c, String groupId) {
        JSONObject item = bindingForGroup(c, groupId);
        String pool = item == null ? "" : item.optString("pool", "");
        JSONObject states = poolStates(c);
        JSONObject state = states.optJSONObject(pool);
        return state == null ? new JSONObject() : state;
    }

    static int minutes(Context c, String groupId) {
        return stateForGroup(c, groupId).optInt("minutes", 0);
    }

    static long pcUsed(Context c, String groupId) {
        return stateForGroup(c, groupId).optLong("pcUsed", 0L);
    }

    static long phoneUsed(Context c, String groupId) {
        return stateForGroup(c, groupId).optLong("phoneUsed", 0L);
    }

    static long total(Context c, String groupId) {
        return stateForGroup(c, groupId).optLong("total", 0L);
    }

    static long at(Context c, String groupId) {
        return stateForGroup(c, groupId).optLong("at", 0L);
    }

    static JSONArray pcApps(Context c, String groupId) {
        return parseArray(stateForGroup(c, groupId).optString("pcAppsJson", ""));
    }

    static String source(Context c, String groupId) {
        return stateForGroup(c, groupId).optString("source", "lan");
    }

    static boolean fresh(Context c, String groupId) {
        long stamp = at(c, groupId);
        if (stamp <= 0) return false;
        long age = System.currentTimeMillis() - stamp;
        return age <= ("gist".equals(source(c, groupId)) ? GIST_STALE_MS : STALE_MS);
    }

    static boolean exhausted(Context c, String groupId) {
        return fresh(c, groupId) && stateForGroup(c, groupId).optBoolean("exhausted", false);
    }

    static int minutes(Context c) {
        return minutes(c, group(c));
    }

    static long pcUsed(Context c) {
        return pcUsed(c, group(c));
    }

    static long phoneUsed(Context c) {
        return phoneUsed(c, group(c));
    }

    static long total(Context c) {
        return total(c, group(c));
    }

    static JSONArray pcApps(Context c) {
        return pcApps(c, group(c));
    }

    static String source(Context c) {
        return source(c, group(c));
    }

    static boolean fresh(Context c) {
        return fresh(c, group(c));
    }

    static boolean exhausted(Context c) {
        return exhausted(c, group(c));
    }

    private static JSONArray parseArray(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (Exception error) {
            return new JSONArray();
        }
    }

    /**
     * 本机组内每个应用的用量（秒），用于随上报一起发给电脑，也用于手机自己展示。
     * 名称用应用标题而不是包名：对面是给人看的列表。
     */
    static JSONArray appDetail(Context c, DailyQuotaData data, JSONObject group) {
        JSONArray out = new JSONArray();
        if (data == null || group == null) {
            return out;
        }
        JSONArray apps = group.optJSONArray("apps");
        JSONObject targets = data.usage.optJSONObject("targets");
        if (apps == null || targets == null) {
            return out;
        }
        for (int i = 0; i < apps.length() && out.length() < 30; i++) {
            String pkg = apps.optString(i);
            long ms = targets.optLong(pkg);
            if (ms <= 0) {
                continue;
            }
            JSONObject item = new JSONObject();
            put(item, "name", Guard.labelOf(c.getPackageManager(), pkg));
            put(item, "seconds", Math.round(ms / 100.0) / 10.0);
            out.put(item);
        }
        return out;
    }

    /** 最近一次轮询失败的原因；成功后清空。设置页拿它诊断「配了但不同步」。 */
    static String lastError(Context c) {
        return sp(c).getString("lastError", "");
    }

    static void clearCache(Context c) {
        sp(c).edit().remove("poolStates")
                .remove("minutes").remove("pcUsed").remove("phoneUsed")
                .remove("total").remove("exhausted").remove("at")
                .remove("source").remove("pcAppsJson").apply();
    }

    /* ---------------- 轮询 ---------------- */

    /**
     * 一次上报所有绑定。
     *
     * reports 里每一项：{id: 电脑池 id, group: 本地额度组 id, usedSeconds, apps}。
     */
    static void pollAsync(final Context c, final JSONArray reports, final Runnable onResult) {
        if (!active(c) || reports == null || reports.length() == 0) return;
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - lastStart < MIN_GAP_MS) return;
            lastStart = now;
        }
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            try {
                poll(app, reports);
                sp(app).edit().remove("lastError").apply();
            } catch (Throwable error) {
                Log.w(TAG, "lan quota poll failed: " + error);
                String detail = error.getMessage() == null ? error.toString() : error.getMessage();
                sp(app).edit().putString("lastError", detail).apply();
                try { pullGist(app); } catch (Throwable ignored) { }
            } finally {
                if (onResult != null) {
                    try { onResult.run(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    /**
     * 兼容旧调用：只报第一条绑定。新代码请用带 reports 的重载。
     */
    static void pollAsync(final Context c, final long phoneUsedMs, final JSONArray apps, final Runnable onResult) {
        JSONObject item = firstBinding(c);
        if (item == null) {
            return;
        }
        JSONObject report = new JSONObject();
        put(report, "id", item.optString("pool", ""));
        put(report, "group", item.optString("group", ""));
        put(report, "usedSeconds", Math.max(0L, phoneUsedMs) / 1000.0);
        if (apps != null && apps.length() > 0) {
            put(report, "apps", apps);
        }
        JSONArray reports = new JSONArray();
        reports.put(report);
        pollAsync(c, reports, onResult);
    }

    private static void poll(Context c, JSONArray reports) throws Exception {
        SharedPreferences p = sp(c);
        String host = p.getString("host", "");
        int port = p.getInt("port", 8738);
        String token = p.getString("token", "");
        if (host.isEmpty() || token.isEmpty()) return;
        JSONObject body = new JSONObject();
        put(body, "pools", reports);
        // 同时带一份顶层旧字段：万一是旧电脑端，至少第一条绑定还能工作。
        if (reports.length() > 0) {
            JSONObject first = reports.optJSONObject(0);
            if (first != null) {
                put(body, "usedSeconds", first.optDouble("usedSeconds", 0));
                put(body, "group", first.optString("group", ""));
                if (first.has("apps")) {
                    put(body, "apps", first.optJSONArray("apps"));
                }
            }
        }
        JSONObject o = call(host, port, token, body);
        apply(c, o);
    }

    /** 设置页「测试连接」用：不写缓存，只把结果抛出来给界面显示。 */
    static JSONObject probe(String host, int port, String token) throws Exception {
        return call(host, port, token, null);
    }

    private static JSONObject call(String host, int port, String token, JSONObject body) throws Exception {
        String url = "http://" + host + ":" + port + "/quota?token=" + token;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_MS);
            conn.setReadTimeout(READ_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "AFS-Guard");
            conn.setRequestProperty("Cache-Control", "no-store");
            if (body != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            } else {
                conn.setRequestMethod("GET");
            }
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IllegalStateException(T.s("电脑端返回 HTTP ") + status);
            }
            try (InputStream in = conn.getInputStream()) {
                return new JSONObject(read(in));
            }
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject stateFromPool(JSONObject pool, long now, String source) {
        JSONObject state = new JSONObject();
        put(state, "minutes", pool.optInt("poolMinutes", 0));
        put(state, "pcUsed", (long) (pool.optDouble("pcUsed", 0) * 1000));
        put(state, "phoneUsed", (long) (pool.optDouble("phoneUsed", 0) * 1000));
        put(state, "total", (long) (pool.optDouble("total", 0) * 1000));
        put(state, "exhausted", pool.optBoolean("exhausted", false));
        put(state, "at", now);
        put(state, "source", source);
        JSONArray pcApps = pool.optJSONArray("pcApps");
        put(state, "pcAppsJson", pcApps == null ? "[]" : pcApps.toString());
        return state;
    }

    private static void apply(Context c, JSONObject o) {
        long now = System.currentTimeMillis();
        JSONObject states = poolStates(c);
        JSONArray list = bindings(c);
        JSONArray pools = o.optJSONArray("pools");
        if (pools != null && pools.length() > 0) {
            boolean bindingsChanged = false;
            for (int i = 0; i < pools.length(); i++) {
                JSONObject pool = pools.optJSONObject(i);
                if (pool == null) {
                    continue;
                }
                String poolId = pool.optString("id", "");
                if (poolId.isEmpty()) {
                    continue;
                }
                JSONObject target = null;
                for (int j = 0; j < list.length(); j++) {
                    JSONObject candidate = list.optJSONObject(j);
                    if (candidate != null && poolId.equals(candidate.optString("pool", ""))) {
                        target = candidate;
                        break;
                    }
                }
                if (target == null) {
                    // 旧绑定没有池 id：电脑第一次回包时把它认领到第一个返回的池。
                    for (int j = 0; j < list.length(); j++) {
                        JSONObject candidate = list.optJSONObject(j);
                        if (candidate != null && candidate.optString("pool", "").isEmpty()) {
                            target = candidate;
                            put(target, "pool", poolId);
                            JSONObject legacy = states.optJSONObject("");
                            if (legacy != null && !states.has(poolId)) {
                                put(states, poolId, legacy);
                            }
                            if (legacy != null) {
                                states.remove("");
                            }
                            bindingsChanged = true;
                            break;
                        }
                    }
                }
                if (target == null) {
                    continue;
                }
                String poolName = pool.optString("name", "").trim();
                if (!poolName.isEmpty() && !poolName.equals(target.optString("poolName", ""))) {
                    put(target, "poolName", poolName);
                    bindingsChanged = true;
                }
                put(states, poolId, stateFromPool(pool, now, "lan"));
                int minutes = pool.optInt("poolMinutes", 0);
                if (minutes > 0) {
                    DailyQuotaStore.syncSharedMinutes(c, target.optString("group", ""), minutes);
                }
            }
            if (bindingsChanged) {
                writeBindings(c, list);
            }
            writePoolStates(c, states);
            return;
        }
        // 旧版电脑端：只有顶层一个池。把它记到第一条绑定上，别影响其他组。
        JSONObject first = list.length() > 0 ? list.optJSONObject(0) : null;
        if (first == null) {
            writePoolStates(c, states);
            return;
        }
        String poolId = first.optString("pool", "");
        JSONObject legacy = new JSONObject();
        put(legacy, "minutes", o.optInt("poolMinutes", 0));
        put(legacy, "pcUsed", (long) (o.optDouble("pcUsed", 0) * 1000));
        put(legacy, "phoneUsed", (long) (o.optDouble("phoneUsed", 0) * 1000));
        put(legacy, "total", (long) (o.optDouble("total", 0) * 1000));
        put(legacy, "exhausted", o.optBoolean("exhausted", false));
        put(legacy, "at", now);
        put(legacy, "source", "lan");
        JSONArray pcApps = o.optJSONArray("pcApps");
        put(legacy, "pcAppsJson", pcApps == null ? "[]" : pcApps.toString());
        put(states, poolId, legacy);
        writePoolStates(c, states);
        int minutes = o.optInt("poolMinutes", 0);
        if (minutes > 0) {
            DailyQuotaStore.syncSharedMinutes(c, first.optString("group", ""), minutes);
        }
    }

    /**
     * 兜底：从同步 Gist 拉 PC 推上来的 quota-pool.json。
     * Gist 只有旧版单池格式，所以只补给第一条绑定；其他池仍按局域网数据。
     */
    private static void pullGist(Context c) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - lastGistTry < GIST_RETRY_MS) return;
            lastGistTry = now;
        }
        JSONObject first = firstBinding(c);
        if (first == null) return;
        SyncTarget target = SyncConfig.target(c);
        if (target == null) return;
        String raw;
        try {
            raw = StudyGate.fetchRaw(target.rawUrl("quota-pool.json") + "?t=" + now);
        } catch (Exception error) {
            Log.w(TAG, "quota-pool gist fetch failed: " + error);
            return;
        }
        if (raw == null || !raw.trim().startsWith("{")) return;
        JSONObject o;
        try {
            o = new JSONObject(raw);
        } catch (Exception error) {
            return;
        }
        if (!DayBoundary.key(c, System.currentTimeMillis()).equals(o.optString("date", ""))) return;
        int minutes = o.optInt("minutes", 0);
        if (minutes <= 0) return;
        long pcUsed = (long) (o.optDouble("pcUsed", 0) * 1000);
        // Gist 上的 phoneUsed 是上一次上报的旧值，不能拿来用；用当前本机账本。
        long phoneUsed = 0L;
        DailyQuotaData data = DailyQuotaStore.snapshot(c);
        JSONObject group = data.groupById(first.optString("group", ""));
        if (group != null) {
            phoneUsed = data.used(group);
        }
        long total = pcUsed + phoneUsed;
        JSONObject state = new JSONObject();
        put(state, "minutes", minutes);
        put(state, "pcUsed", pcUsed);
        put(state, "phoneUsed", phoneUsed);
        put(state, "total", total);
        put(state, "exhausted", total >= minutes * 60_000L);
        put(state, "at", System.currentTimeMillis());
        put(state, "source", "gist");
        JSONArray pcApps = o.optJSONArray("pcApps");
        put(state, "pcAppsJson", pcApps == null ? "[]" : pcApps.toString());
        JSONObject states = poolStates(c);
        put(states, first.optString("pool", ""), state);
        writePoolStates(c, states);
        DailyQuotaStore.syncSharedMinutes(c, first.optString("group", ""), minutes);
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /** 给额度页显示用：「与电脑共享 · 剩 12 分钟」；Gist 兜底时标「离线估算」。 */
    static String summary(Context c, String groupId) {
        if (!isShared(c, groupId)) return "";
        if (!fresh(c, groupId)) return T.s("未连接电脑");
        int minutes = minutes(c, groupId);
        long remainSec = Math.max(0L, minutes * 60L - total(c, groupId) / 1000L);
        if ("gist".equals(source(c, groupId))) return T.f("离线估算 · 剩 %d 分钟", remainSec / 60);
        return T.f("与电脑共享 · 剩 %d 分钟", remainSec / 60);
    }

    static String summary(Context c) {
        return summary(c, group(c));
    }
}
