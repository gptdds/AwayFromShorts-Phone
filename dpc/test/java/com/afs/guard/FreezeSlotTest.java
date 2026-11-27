package com.afs.guard;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Schedule-first grouping must preserve existing app behavior and key-app exemptions. */
public final class FreezeSlotTest {
    private static int checks;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    public static void main(String[] args) {
        Rule night = new Rule(127, new int[][]{{0,360}}, true, true);
        Rule keyNight = night.withGate(false);
        check(FreezeSlotStore.scheduleKey(night).equals(FreezeSlotStore.scheduleKey(keyNight)), "critical apps share the same schedule container");
        Rule legacy = new Rule(127, 360, 0, false);
        check(FreezeSlotStore.scheduleKey(legacy).equals(FreezeSlotStore.scheduleKey(night)), "legacy allow windows migrate without behavior changes");
        Rule wrap = new Rule(127, new int[][]{{1380,1260}}, true, true);
        Rule split = new Rule(127, new int[][]{{0,1260},{1380,0}}, true, true);
        check(FreezeSlotStore.scheduleKey(wrap).equals(FreezeSlotStore.scheduleKey(split)), "equivalent midnight segments do not create duplicate slots");
        check(!FreezeSlotStore.scheduleKey(night).equals(FreezeSlotStore.scheduleKey(night.withDays(31))), "weekdays remain part of the schedule");
        Map<String, Rule> rules = new LinkedHashMap<>();
        rules.put("normal", night); rules.put("critical", keyNight); rules.put("pending", FreezeSlotStore.withoutSchedule());
        FreezeSlotStore.Slot slot = new FreezeSlotStore.Slot("night", night);
        check(FreezeSlotStore.members(slot, rules).equals(Arrays.asList("normal","critical")), "membership depends on schedule rather than exemption");
        check(FreezeSlotStore.unassigned(FreezeSlotStore.withoutSchedule()), "new apps remain in the application tray");
        check(!FreezeSlotStore.unassigned(new Rule(127,new int[][]{{0,0}},true,true)), "all-day freeze is not unassigned");
        check(!rules.get("critical").gate && rules.get("normal").gate, "grouping does not mutate app exemptions");
        java.util.Calendar monday = java.util.Calendar.getInstance(); monday.clear(); monday.set(2026, 9, 19);
        check(Arrays.deepEquals(FreezeSlotStore.frozenSegments(night, monday), new int[][]{{0,360}}), "night interval maps onto the 24-hour rail");
        Rule crossing = new Rule(127, new int[][]{{1320,120}}, true, true);
        check(Arrays.deepEquals(FreezeSlotStore.frozenSegments(crossing, monday), new int[][]{{0,120},{1320,1440}}), "overnight interval appears at both ends of the rail");
        java.util.Calendar tuesday = (java.util.Calendar)monday.clone(); tuesday.add(java.util.Calendar.DATE,1);
        check(Arrays.deepEquals(FreezeSlotStore.frozenSegments(new Rule(1,new int[][]{{1320,120}},true,true),tuesday), new int[][]{{0,120}}), "weekly overnight interval carries into the following day");
        check(FreezeSlotStore.frozenSegments(FreezeSlotStore.withoutSchedule(),monday).length == 0, "unassigned schedule has no frozen portion");
        check(Arrays.deepEquals(FreezeSlotStore.frozenSegments(new Rule(127,new int[][]{{0,0}},true,true),monday),new int[][]{{0,1440}}), "all-day freeze fills the complete rail");
        monday.set(java.util.Calendar.HOUR_OF_DAY,20);
        check(Guard.blockReason(night,false,true,false,monday)==2,"camera outside its own night window reports study hold");
        check(Guard.blockReason(night,true,true,false,monday)==0,"key app remains exempt outside its own window");
        check(Guard.blockReason(night,false,false,false,monday)==0,"ordinary app is available when no extension is active");
        check(Guard.blockReason(night,false,true,true,monday)==3,"taboo penalty is identified independently");
        monday.set(java.util.Calendar.HOUR_OF_DAY,2);
        check(Guard.blockReason(night,false,true,false,monday)==1,"own schedule has priority in displayed reason");
        java.util.Calendar saturday=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        saturday.clear();saturday.set(2026,9,24,2,0);
        check(FreezeWeekdayPolicy.disabledOn(1<<5,saturday),"Saturday policy mask is Monday-first");
        int allScopes=FreezeWeekdayPolicy.ALL_SCOPES;
        check(FreezeWeekdayPolicy.pausedOn(0x60,allScopes,FreezeWeekdayPolicy.SCOPE_SCHEDULE,saturday)
                        &&FreezeWeekdayPolicy.pausedOn(0x60,allScopes,FreezeWeekdayPolicy.SCOPE_QUOTA,saturday)
                        &&FreezeWeekdayPolicy.pausedOn(0x60,allScopes,FreezeWeekdayPolicy.SCOPE_OR_SET,saturday)
                        &&FreezeWeekdayPolicy.pausedOn(0x60,allScopes,FreezeWeekdayPolicy.SCOPE_STUDY,saturday)
                        &&FreezeWeekdayPolicy.pausedOn(0x60,allScopes,FreezeWeekdayPolicy.SCOPE_PENALTY,saturday),
                "global weekday pause covers all five restriction families");
        int quotaOnly=FreezeWeekdayPolicy.SCOPE_QUOTA;
        check(FreezeWeekdayPolicy.pausedOn(0x60,quotaOnly,FreezeWeekdayPolicy.SCOPE_QUOTA,saturday)
                        &&!FreezeWeekdayPolicy.pausedOn(0x60,quotaOnly,FreezeWeekdayPolicy.SCOPE_STUDY,saturday)
                        &&!FreezeWeekdayPolicy.pausedOn(0x60,quotaOnly,FreezeWeekdayPolicy.SCOPE_PENALTY,saturday),
                "custom weekday scopes release only the selected family");
        check(FreezeWeekdayPolicy.scopesText(allScopes).equals(T.s("全局暂停")),
                "all scopes are labelled as a global pause");
        int subset=FreezeWeekdayPolicy.SCOPE_SCHEDULE|FreezeWeekdayPolicy.SCOPE_QUOTA;
        check(FreezeWeekdayPolicy.scopesText(subset).contains(T.s("冻结时段"))
                        &&FreezeWeekdayPolicy.scopesText(subset).contains(T.s("时间限额"))
                        &&!FreezeWeekdayPolicy.scopesText(subset).contains(T.s("学习联动")),
                "custom scope labels list only selected families");
        check(DailyQuotaData.enforced(true,false,false,false)
                        &&!DailyQuotaData.enforced(true,false,true,false),
                "quota pause releases exhausted quota");
        check(DailyQuotaData.enforced(false,true,false,false)
                        &&!DailyQuotaData.enforced(false,true,false,true),
                "OR-set pause releases mutual exclusion without releasing quota");
        check(DailyQuotaData.enforced(true,true,true,false)
                        &&DailyQuotaData.enforced(true,true,false,true),
                "quota and OR-set pauses remain independent");
        check(Guard.blockReason(night,false,false,false,false,saturday,false,true)==0,
                "weekday pause releases the app's own freeze schedule");
        check(Guard.blockReason(night,false,true,false,false,saturday,false,true)==2,
                "weekday pause keeps the study linkage hold");
        check(Guard.blockReason(night,false,false,true,false,saturday,false,true)==3,
                "weekday pause keeps taboo penalties");
        check(Guard.blockReason(night,false,false,false,true,saturday,false,true)==3,
                "weekday pause keeps check-in penalties");
        check(Guard.blockReason(night,false,false,false,false,saturday,false,false)==1,
                "outside the selected weekday the freeze schedule remains active");
        check(LocalBackup.key("afs_guard","freeze_weekday_disabled_mask"),
                "weekday pause policy is included in local backups");
        check(LocalBackup.key("afs_guard","freeze_weekday_pause_scopes"),
                "weekday pause scope selection is included in local backups");
        java.util.Calendar mondayLate=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        mondayLate.clear();mondayLate.set(2026,9,19,23,59,59);
        long midnight=FreezeWeekdayPolicy.nextMidnight(mondayLate);
        java.util.Calendar nextDay=(java.util.Calendar)mondayLate.clone();nextDay.setTimeInMillis(midnight);
        check(nextDay.get(java.util.Calendar.DAY_OF_MONTH)==20
                        &&nextDay.get(java.util.Calendar.HOUR_OF_DAY)==0
                        &&nextDay.get(java.util.Calendar.MINUTE)==0,
                "weekday policy schedules a precise local-midnight transition");
        check(!FreezeSlotStore.membershipEditable(monday,22*60,22*60+30),"membership is locked outside the adjustment window");
        check(!FreezeSlotStore.membershipEditable(monday,3*60,4*60),"another adjustment window does not unlock membership");
        monday.set(java.util.Calendar.HOUR_OF_DAY,6);
        check(FreezeSlotStore.membershipEditable(monday,6*60,7*60),"membership opens at the adjustment-window boundary");
        check(!FreezeSlotStore.membershipEditable(null,22*60,23*60),"null clock never unlocks membership");
        monday.set(java.util.Calendar.HOUR_OF_DAY,23);
        check(!FreezeSlotStore.membershipEditable(monday,22*60,23*60),"closed adjustment-window edge stays locked");
        tuesday.set(java.util.Calendar.HOUR_OF_DAY,1);
        check(FreezeSlotStore.membershipEditable(tuesday,22*60,2*60),"overnight adjustment window unlocks after midnight");
        tuesday.set(java.util.Calendar.HOUR_OF_DAY,2);
        check(!FreezeSlotStore.membershipEditable(tuesday,22*60,2*60),"overnight adjustment window closes at its right edge");
        // Differential check against enforcement for every minute of each weekday.
        java.util.Random random=new java.util.Random(1493);
        for(int sample=0;sample<48;sample++) {
            int[][] windows=new int[sample%5][2];
            for(int[] w:windows){w[0]=random.nextInt(1440);w[1]=random.nextInt(1440);}
            if(windows.length>0&&sample%4==0)windows[0][1]=windows[0][0];
            for(boolean freeze:new boolean[]{false,true}) {
                Rule candidate=new Rule(random.nextInt(128),windows,true,freeze);
                for(int weekday=0;weekday<7;weekday++) {
                    java.util.Calendar date=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
                    date.clear();date.set(2026,9,19+weekday);
                    boolean[] rail=new boolean[1440];
                    for(int[] span:FreezeSlotStore.frozenSegments(candidate,date))Arrays.fill(rail,span[0],span[1],true);
                    for(int minute=0;minute<1440;minute++) {
                        date.set(java.util.Calendar.HOUR_OF_DAY,minute/60);date.set(java.util.Calendar.MINUTE,minute%60);
                        if(rail[minute]==candidate.inWindow(date))throw new AssertionError("rail differs at "+sample+":"+weekday+":"+minute);
                    }
                    checks++;
                }
            }
        }
        System.out.println("Freeze slots: " + checks + " checks passed");
    }
}
