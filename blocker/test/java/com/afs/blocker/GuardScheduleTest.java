package com.afs.blocker;

import com.afs.core.rules.WeeklyRule;
import com.afs.core.rules.TransitionText;

import java.time.Instant;
import java.util.TimeZone;

public final class GuardScheduleTest {
    private static int checks;
    private static void check(boolean value,String label) { checks++; if (!value) throw new AssertionError(label); }
    public static void main(String[] args) {
        long now = Instant.parse("2026-11-16T19:59:30Z").toEpochMilli();
        TimeZone zone = TimeZone.getTimeZone("UTC");
        WeeklyRule evening = WeeklyRule.allow(127,new int[][]{{1200,1380}});
        check(Guard.nextTransition(evening,now,0,zone) == now+30000,"normal boundary");
        check(Guard.nextTransition(evening,now,now+60000,zone) == now+60000,"force delays release");
        long midnight = Instant.parse("2026-11-17T00:00:00Z").toEpochMilli();
        check(Guard.nextTransition(evening,now,midnight,zone) == midnight+20*3600000L,"expired force remains blocked until next window");
        WeeklyRule allDay = WeeklyRule.allow(127,new int[][]{{0,0}});
        check(Guard.nextTransition(allDay,now,0,zone) == 0,"no invented switch time");
        check(Guard.nextTransition(allDay,now,now+1000,zone) == now+1000,"force end opens all-day app");
        check(TransitionText.remaining(now,now+30000).equals("00:00:30"),"seconds are accurate");
        check(TransitionText.remaining(now,now-1000).equals("00:00:00"),"expired countdown is clamped");
        System.out.println("Guard schedule: " + checks + " checks passed");
    }
}
