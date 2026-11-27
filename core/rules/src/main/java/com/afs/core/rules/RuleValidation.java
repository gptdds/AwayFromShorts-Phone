package com.afs.core.rules;

import java.util.ArrayList;
import java.util.List;

public final class RuleValidation {
    private RuleValidation() {}

    public static List<String> warnings(WeeklyRule rule) {
        List<String> result = new ArrayList<>();
        if (rule.days() == 0) result.add(rule.freezeMode()
                ? "未选择星期：始终放行" : "未选择星期：始终拦截");
        if (rule.windowCount() == 0) result.add(rule.freezeMode()
                ? "没有时段：始终放行" : "没有时段：始终拦截");
        boolean wholeDay = false;
        boolean overlap = false;
        boolean[] occupied = new boolean[7 * 1440];
        for (int index = 0; index < rule.windowCount(); index++) {
            int start = rule.startAt(index);
            int length = (rule.endAt(index) - start + 1440) % 1440;
            if (length == 0) { wholeDay = true; length = 1440; start = 0; }
            for (int day = 0; day < 7; day++) {
                if (!WeeklyRule.dayOn(rule.days(), day)) continue;
                for (int offset = 0; offset < length; offset++) {
                    int minute = (day * 1440 + start + offset) % occupied.length;
                    if (occupied[minute]) overlap = true;
                    occupied[minute] = true;
                }
            }
        }
        if (wholeDay) result.add("起止相同表示选中星期全天生效");
        if (overlap) result.add("时段存在重叠，将按并集合并生效");
        return result;
    }
}
