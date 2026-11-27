package com.afs.guard;

import java.util.Calendar;

public final class TemporaryPardonTest {
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    public static void main(String[] args) {
        long monday2300 = at(2026, Calendar.JANUARY, 5, 23, 0);
        long tuesday0100 = at(2026, Calendar.JANUARY, 6, 1, 0);
        TemporaryPardon.Window range =
                new TemporaryPardon.Window("com.example.app", monday2300, tuesday0100);

        check(!TemporaryPardon.active(range, monday2300 - 1),
                "one minute before start is inactive");
        check(TemporaryPardon.active(range, monday2300),
                "start minute is active");
        check(TemporaryPardon.active(range, tuesday0100 - 1),
                "last minute is active");
        check(!TemporaryPardon.active(range, tuesday0100),
                "end minute is inactive");
        check(TemporaryPardon.nextBoundaryOf(range, monday2300 - 1) == monday2300,
                "next boundary is the start");
        check(TemporaryPardon.nextBoundaryOf(range, monday2300 + 60_000L) == tuesday0100,
                "next boundary is the end while active");
        check(TemporaryPardon.nextBoundaryOf(range, tuesday0100) == Long.MAX_VALUE,
                "expired range has no boundary");
        check(TemporaryPardon.nextBoundaryOf(null, monday2300) == Long.MAX_VALUE,
                "missing range has no boundary");

        String cross = TemporaryPardon.rangeText(monday2300, tuesday0100);
        check(cross.contains("1月5日 23:00") && cross.contains("次日 01:00"),
                "cross-midnight text keeps the date and next-day clock");
        String sameDay = TemporaryPardon.rangeText(
                at(2026, Calendar.JANUARY, 5, 22, 0), monday2300);
        check(sameDay.contains("1月5日 22:00") && sameDay.contains("23:00"),
                "same-day text keeps both clock times");
        String multiDay = TemporaryPardon.rangeText(
                monday2300, at(2026, Calendar.JANUARY, 7, 1, 0));
        check(multiDay.contains("1月7日 01:00"),
                "multi-day text shows the ending date");

        TemporaryPardon.Window futureDaily = TemporaryPardon.fromDaily(
                "com.example.app", 23 * 60, 60,
                at(2026, Calendar.JANUARY, 5, 10, 0));
        check(futureDaily.start == monday2300, "daily migration uses tonight's start");
        check(futureDaily.end == tuesday0100, "daily migration uses tomorrow's end");

        TemporaryPardon.Window activeDaily = TemporaryPardon.fromDaily(
                "com.example.app", 23 * 60, 60,
                at(2026, Calendar.JANUARY, 6, 0, 30));
        check(activeDaily.start == monday2300 && activeDaily.end == tuesday0100,
                "daily migration keeps a currently active range");

        TemporaryPardon.Window tomorrowDaily = TemporaryPardon.fromDaily(
                "com.example.app", 23 * 60, 60,
                at(2026, Calendar.JANUARY, 6, 2, 0));
        check(tomorrowDaily.start == at(2026, Calendar.JANUARY, 6, 23, 0),
                "daily migration moves an expired range to the next occurrence");
        check(tomorrowDaily.end == at(2026, Calendar.JANUARY, 7, 1, 0),
                "daily migration keeps the next end across midnight");

        check(Guard.pardonAdjustedReason(1, true) == 0, "pardon waives the schedule");
        check(Guard.pardonAdjustedReason(4, true) == 0, "pardon waives app usage quota");
        check(Guard.pardonAdjustedReason(5, true) == 0, "pardon waives daily quota exhaustion");
        check(Guard.pardonAdjustedReason(2, true) == 2, "pardon never waives study linkage");
        check(Guard.pardonAdjustedReason(3, true) == 3, "pardon never waives punishment");
        check(Guard.pardonAdjustedReason(6, true) == 6, "pardon never waives key gates");
        check(Guard.pardonAdjustedReason(1, false) == 1, "expiry restores schedule freeze");

        System.out.println("TemporaryPardon: " + checks + " checks passed");
    }
}
