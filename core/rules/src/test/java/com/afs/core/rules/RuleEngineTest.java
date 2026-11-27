package com.afs.core.rules;

import java.time.Instant;
import java.util.TimeZone;

public final class RuleEngineTest {
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static long at(String value) { return Instant.parse(value).toEpochMilli(); }

    public static void main(String[] args) {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long monday = at("2026-11-16T00:00:00Z");
        WeeklyRule work = WeeklyRule.allow(WeeklyRule.WORKDAYS, new int[][]{{480, 600}, {570, 720}, {1320, 60}});
        check(work.blocked(0, 479) && !work.blocked(0, 480), "left boundary");
        check(!work.blocked(0, 719) && work.blocked(0, 720), "overlap union and right boundary");
        check(!work.blocked(5, 59) && work.blocked(5, 60), "Friday midnight extends to Saturday");
        check(work.blocked(6, 30), "Saturday did not start a window");
        check(work.nextTransition(monday + 590 * 60000L, utc) == monday + 720 * 60000L,
                "overlap endpoint is not a transition");
        WeeklyRule weekend = WeeklyRule.freeze(WeeklyRule.WEEKEND, new int[][]{{0, 0}});
        check(!weekend.blocked(4, 1439) && weekend.blocked(5, 0), "weekend starts Saturday");
        check(weekend.nextTransition(monday, utc) == monday + 5 * 86400000L, "weekday rollover");
        WeeklyRule always = WeeklyRule.allow(127, new int[][]{{12, 12}});
        check(!always.blocked(0, 0) && always.nextTransition(monday, utc) == 0, "all day has no false countdown");
        check(always.nextFlip(monday, utc) == monday + 3600000L, "background recheck remains compatible");
        check(WeeklyRule.allow(0, new int[][]{{0, 0}}).blocked(0, 0), "empty weekdays allow mode");
        check(!WeeklyRule.freeze(0, new int[][]{{0, 0}}).blocked(0, 0), "empty weekdays freeze mode");
        for (int day = 0; day < 7; day++) {
            WeeklyRule one = WeeklyRule.allow(1 << day, new int[][]{{60, 120}});
            for (int other = 0; other < 7; other++)
                check(one.blocked(other, 90) == (day != other), "weekday bit " + day + "/" + other);
        }
        TimeZone newYork = TimeZone.getTimeZone("America/New_York");
        WeeklyRule spring = WeeklyRule.allow(127, new int[][]{{180, 240}});
        check(spring.nextTransition(at("2026-03-08T06:59:01Z"), newYork) == at("2026-03-08T07:00:00Z"),
                "spring gap uses actual 03:00 instant");
        WeeklyRule missing = WeeklyRule.allow(127, new int[][]{{120, 150}});
        check(missing.nextTransition(at("2026-03-08T06:59:01Z"), newYork) == at("2026-03-09T06:00:00Z"),
                "nonexistent window is skipped");
        WeeklyRule autumn = WeeklyRule.allow(127, new int[][]{{90, 100}});
        check(autumn.nextTransition(at("2026-11-01T05:40:01Z"), newYork) == at("2026-11-01T06:30:00Z"),
                "autumn repeated hour opens twice");
        WeeklyRule local = WeeklyRule.allow(127, new int[][]{{480, 540}});
        check(local.nextTransition(monday, utc) == monday + 480 * 60000L, "UTC boundary");
        check(local.nextTransition(monday, TimeZone.getTimeZone("Asia/Shanghai")) == monday + 60 * 60000L,
                "zone changes are recalculated");
        check(RuleValidation.warnings(work).size() == 1, "overlap is a warning");
        check(RuleValidation.warnings(always).size() == 1, "all day is a warning");
        System.out.println("Rule engine: " + checks + " checks passed");
    }
}
