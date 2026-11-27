package com.afs.core.rules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class RuleBackupDocument {
    public final String variant;
    public final Map<String, WeeklyRule> rules;
    public final Set<String> keyApps;
    public final long forceUntil;
    public final int start, end;

    public RuleBackupDocument(String variant, Map<String, WeeklyRule> rules, Set<String> keys,
                              long forceUntil, int start, int end) {
        if (!"device-owner".equals(variant) && !"accessibility".equals(variant))
            throw new IllegalArgumentException("备份版本类型无效");
        if (forceUntil < 0 || start < 0 || start > 1439 || end < 0 || end > 1439)
            throw new IllegalArgumentException("备份时间无效");
        if (!rules.keySet().containsAll(keys)) throw new IllegalArgumentException("关键应用缺少对应规则");
        this.variant = variant; this.rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        this.keyApps = Collections.unmodifiableSet(new LinkedHashSet<>(keys));
        this.forceUntil = forceUntil; this.start = start; this.end = end;
    }

    public String encode() throws JSONException {
        return new JSONObject().put("format","afs-rule-backup").put("version",1).put("variant",variant)
                .put("rules",new JSONObject(RuleJson.encode(rules))).put("key_apps",new JSONArray(keyApps))
                .put("force_until",forceUntil).put("start_min",start).put("end_min",end).toString(2);
    }

    public static RuleBackupDocument parse(String raw, String expectedVariant) throws JSONException {
        JSONObject document = new JSONObject(raw);
        if (!"afs-rule-backup".equals(document.getString("format"))
                || !(document.get("version") instanceof Integer) || document.getInt("version") != 1)
            throw new IllegalArgumentException("不支持的备份格式或版本");
        if (!expectedVariant.equals(document.getString("variant"))) throw new IllegalArgumentException("请选择当前 APK 版本导出的备份");
        Map<String, WeeklyRule> rules = RuleJson.parse(document.getJSONObject("rules").toString());
        Set<String> keys = new LinkedHashSet<>();
        JSONArray names = document.getJSONArray("key_apps");
        for (int index = 0; index < names.length(); index++) keys.add(names.getString(index));
        Object force = document.get("force_until"), start = document.get("start_min"), end = document.get("end_min");
        if (!(force instanceof Number) || !(start instanceof Number) || !(end instanceof Number)
                || ((Number) force).doubleValue() != ((Number) force).longValue()
                || ((Number) start).doubleValue() != ((Number) start).intValue()
                || ((Number) end).doubleValue() != ((Number) end).intValue())
            throw new IllegalArgumentException("备份时间必须为整数");
        return new RuleBackupDocument(expectedVariant,rules,keys,((Number)force).longValue(),
                ((Number)start).intValue(),((Number)end).intValue());
    }

    public static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("无法打开备份文件");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 1024 * 1024) throw new IOException("规则备份不能超过 1 MB");
            output.write(buffer,0,count);
        }
        return new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
    }

    public Map<String, WeeklyRule> combined(Map<String, WeeklyRule> existing, boolean replace) {
        Map<String, WeeklyRule> result = replace ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        result.putAll(rules);
        return result;
    }

    public String preview(Map<String, WeeklyRule> existing) {
        int changed = 0, added = 0;
        for (Map.Entry<String, WeeklyRule> entry : rules.entrySet()) {
            WeeklyRule before = existing.get(entry.getKey());
            if (before == null) added++;
            else if (!before.signature().equals(entry.getValue().signature())) changed++;
        }
        StringBuilder text = new StringBuilder("备份包含 ").append(rules.size()).append(" 个应用\n新增 ")
                .append(added).append(" 个 · 时段变更 ").append(changed).append(" 个\n");
        int shown = 0;
        for (Map.Entry<String, WeeklyRule> entry : rules.entrySet()) {
            if (shown++ == 12) { text.append("…\n"); break; }
            text.append(entry.getKey()).append(" · ").append(entry.getValue().freezeMode() ? "冻结" : "放行")
                    .append(" · ").append(entry.getValue().windowCount()).append(" 个时段\n");
            for (String warning : RuleValidation.warnings(entry.getValue())) text.append(warning).append('\n');
        }
        if ("accessibility".equals(variant)) {
            text.append("\n强制模式结束时间：").append(forceUntil == 0L ? "未设置" :
                    new java.text.SimpleDateFormat("yyyy/M/d HH:mm:ss",java.util.Locale.CHINA).format(new java.util.Date(forceUntil)));
            text.append(String.format(java.util.Locale.ROOT,"\n新应用默认放行：%02d:%02d–%02d:%02d\n",start/60,start%60,end/60,end%60));
        } else text.append("\n不参与学习闸门的关键应用：").append(keyApps.size()).append(" 个\n");
        return text.append("\n合并：保留其他应用，备份中的同名应用覆盖时段。\n替换：按备份重建名单。\n恢复前会保存当前规则；强制模式不能被缩短。").toString();
    }
}
