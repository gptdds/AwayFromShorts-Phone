package com.afs.guard;
import org.json.*;
import java.util.*;
public final class DailyQuotaTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static long at(int day,int h,int m,int sec){Calendar c=Calendar.getInstance();c.clear();c.set(2026,9,day,h,m,sec);return c.getTimeInMillis();}
    private static Calendar calendar(long now){Calendar c=Calendar.getInstance();c.setTimeInMillis(now);return c;}
    private static JSONObject group(String id,int minutes,String... apps)throws Exception{return new JSONObject().put("id",id).put("name",id).put("minutes",minutes).put("apps",new JSONArray(Arrays.asList(apps)));}
    private static String config(boolean on,JSONObject... groups)throws Exception{return new JSONObject().put("enabled",on).put("groups",new JSONArray(Arrays.asList(groups))).toString();}
    private static List<AppUsageQuota.Span> spans(AppUsageQuota.Span... spans){return Arrays.asList(spans);}
    private static AppUsageQuota.Span span(String app,long start,long end){return new AppUsageQuota.Span(app,start,end);}
    private static void reject(Runnable action,String label){try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){checks++;return;}throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));String a="com.example.a",b="com.example.b";long noon=at(29,12,0,0);
        JSONObject ga=group("a",1,a),gb=group("b",2,b),shared=group("shared",2,a,b);DailyQuotaData d=new DailyQuotaData(config(true,ga,gb),"{}",noon);
        JSONObject penaltyConfig=new JSONObject(config(true,group("p1",30,a),group("p2",40,b),group("p3",50,"com.example.c","com.example.d")));
        JSONObject penaltyUsage=new JSONObject().put("date","2026-10-29").put("lastAt",noon)
                .put("targets",new JSONObject().put(a,60000L).put(b,120000L).put("com.example.c",0L));
        check(DailyQuotaData.addUsedPenalty(penaltyConfig,penaltyUsage,30)==3,
                "taboo quota penalty touches each quota group exactly once");
        check(penaltyUsage.optJSONObject("targets").optLong(a)==1860000L
                        &&penaltyUsage.optJSONObject("targets").optLong(b)==1920000L,
                "taboo quota penalty adds thirty minutes to each single-member group");
        check(penaltyUsage.optJSONObject("targets").optLong("com.example.c")==1800000L
                        &&penaltyUsage.optJSONObject("targets").optLong("com.example.d")==0L,
                "shared group is charged once on its first member, not once per app");
        check(DailyQuotaData.addUsedPenalty(penaltyConfig,penaltyUsage,1440)==3
                        &&penaltyUsage.optJSONObject("targets").optLong(a)==86400000L,
                "taboo quota penalty is capped at one full day");
        check(d.learningLinked(),"daily quota links to study gate by default");
        DailyQuotaData minuteQuota=new DailyQuotaData(config(true,ga),"{}",at(29,11,28,0),11,30);
        minuteQuota.advance(at(29,11,29,0),spans(span(a,at(29,11,28,0),at(29,11,29,0))),true,Collections.emptyMap(),11,30);
        check(minuteQuota.exceeded(a),"minute boundary preserves exhausted quota before reset");
        minuteQuota.advance(at(29,11,30,0),spans(span(a,at(29,11,28,0),at(29,11,29,0))),true,Collections.emptyMap(),11,30);
        check(minuteQuota.used(ga)==0&&!minuteQuota.exceeded(a),"minute boundary clears previous day usage exactly at reset");
        minuteQuota.advance(at(29,11,30,30),spans(span(a,at(29,11,30,0),at(29,11,30,30))),true,Collections.emptyMap(),11,30);
        check(minuteQuota.used(ga)==30000,"minute boundary counts only new day usage");
        JSONObject unlinkedConfig=new JSONObject(config(true,ga));unlinkedConfig.put("learningLink",false);
        check(!new DailyQuotaData(unlinkedConfig.toString(),"{}",noon).learningLinked(),"explicit study-link opt-out is preserved");
        List<AppUsageQuota.Span> history=spans(span(a,noon-60000,noon+30000),span(b,noon+30000,noon+60000));d.advance(noon+60000,history,true);
        check(d.used(ga)==30000&&d.used(gb)==30000,"only use since enabling counts, independently");
        d.advance(noon+60000,history,true);check(d.used(ga)==30000,"same sample cannot double count");
        DailyQuotaData merged=new DailyQuotaData(config(true,shared),d.usage.toString(),noon+60000);check(merged.used(shared)==60000,"merge sums retained per-app usage");// 重算口径：advance 收到的是「今天整天」的 spans（真实调用就是 spans(c,now,true) 的全量）
        merged.advance(noon+90000,spans(span(a,noon,noon+30000),span(b,noon+30000,noon+60000),span(a,noon+60000,noon+90000)),true);check(merged.used(shared)==90000,"shared budget combines both apps");
        DailyQuotaData split=new DailyQuotaData(config(true,ga,gb),merged.usage.toString(),noon+90000);check(split.used(ga)==60000&&split.used(gb)==30000,"split restores each exact app usage");check(split.exceeded(a)&&!split.exceeded(b),"one exhausted independent app does not block another");
        // 或集互斥：任一格今天首次产生用量后，其他格子当天冻结；已使用格保留自己的额度。
        JSONObject orA=group("or-a",3,a).put("orGroup","chain-1").put("orForceExitMinutes",2),orB=group("or-b",3,b).put("orGroup","chain-1").put("orForceExitMinutes",2);
        DailyQuotaData orSet=new DailyQuotaData(config(true,orA,orB),"{}",noon);
        orSet.advance(noon+60000,spans(span(a,noon,noon+60000)),true);
        check(orSet.used(orA)==60000&&orSet.used(orB)==0,"or-set keeps an independent ledger for each cell");
        check(orSet.orChoice("chain-1").equals("or-a")&&!orSet.exceeded(a)&&!orSet.exceeded(b),"or-set keeps the other cell usable inside the grace window");
        check(!orSet.orForceExitDue(orSet.group(b),noon+60000),"a custom two-minute exit is not due after one minute");
        orSet.advance(noon+120000,spans(span(a,noon,noon+120000)),true);
        check(orSet.orForceExitDue(orSet.group(b),noon+120000),"the first forced exit becomes due at the custom two-minute boundary");
        JSONObject fiveA=group("five-a",3,a).put("orGroup","five-chain").put("orForceExitMinutes",5),
                fiveB=group("five-b",3,b).put("orGroup","five-chain").put("orForceExitMinutes",5);
        JSONObject defaultA=group("default-a",3,a).put("orGroup","default-chain"),
                defaultB=group("default-b",3,b).put("orGroup","default-chain");
        DailyQuotaData defaultDelay=new DailyQuotaData(config(true,defaultA,defaultB),"{}",noon);
        defaultDelay.advance(noon+1000,spans(span(a,noon,noon+1000)),true);
        check(DailyQuotaData.orForceExitMinutes(defaultA)==4,
                "new OR sets default to a four-minute first forced exit");
        check(!defaultDelay.orForceExitDue(defaultB,noon+4*60_000L-1),
                "the default forced exit still waits until four minutes");
        check(defaultDelay.orForceExitDue(defaultB,noon+4*60_000L),
                "the default forced exit becomes due at four minutes");
        check(!defaultDelay.orFrozenByOther(defaultB,noon+4*60_000L)
                        &&defaultDelay.orFrozenByOther(defaultB,noon+DailyQuotaData.OR_FORGIVENESS_MS+1),
                "the four-minute default does not move the strict five-minute freeze boundary");
        check(DailyQuotaData.orForceExitMinutes(fiveA)==5,
                "an explicitly saved five-minute delay is not overwritten by the new default");
        DailyQuotaData fiveMinute=new DailyQuotaData(config(true,fiveA,fiveB),"{}",noon);
        fiveMinute.advance(noon+1000,spans(span(a,noon,noon+1000)),true);
        check(fiveMinute.orForceExitDue(fiveMinute.group(b),noon+DailyQuotaData.OR_FORGIVENESS_MS),
                "a five-minute exit becomes due at the configured boundary");
        check(!fiveMinute.orFrozenByOther(fiveB,noon+DailyQuotaData.OR_FORGIVENESS_MS),
                "mutual freezing is not active at exactly five minutes");
        check(fiveMinute.orForceExitDue(fiveMinute.group(b),noon+DailyQuotaData.OR_FORGIVENESS_MS+5000),
                "the five-second service tick may observe a five-minute exit slightly late");
        check(fiveMinute.orFrozenByOther(fiveB,noon+DailyQuotaData.OR_FORGIVENESS_MS+5000),
                "other members freeze only after the strict five-minute boundary");
        orSet.markOrForceExited("chain-1",noon+120000);
        check(!orSet.orForceExitDue(orSet.group(b),noon+180000),"the forced-exit slot is consumed after the first marked exit");
        check(!orSet.orFrozenByOther(orB,noon+DailyQuotaData.OR_FORGIVENESS_MS)
                        &&orSet.orFrozenByOther(orB,noon+DailyQuotaData.OR_FORGIVENESS_MS+1),
                "mutual freezing remains fixed at strictly over five minutes after the marker");
        orSet.advance(noon+DailyQuotaData.OR_FORGIVENESS_MS+1,spans(span(a,noon,noon+120000),span(b,noon+60000,noon+90000)),true);
        check(orSet.orChoice("chain-1").equals("or-a")&&!orSet.exceeded(a)&&orSet.exceeded(b),"the other or-set cell freezes once the five-minute boundary is exceeded");
        check(DailyQuotaData.orForceExit(orA)&&orSet.orForceExitedAt("chain-1")==noon+120000,
                "the once-per-day forced-exit marker survives usage refresh");
        JSONObject exitChoice=MemoryData.copy(orSet.config);
        DailyQuotaData.setOrForceExit(exitChoice,"chain-1",false);
        DailyQuotaData.validate(exitChoice);
        check(!DailyQuotaData.orForceExit(exitChoice.optJSONArray("groups").optJSONObject(0))
                        &&!DailyQuotaData.orForceExit(exitChoice.optJSONArray("groups").optJSONObject(1)),
                "OR forced-exit choice is synchronized across every member");
        DailyQuotaData freezeOnly=new DailyQuotaData(exitChoice.toString(),orSet.usage.toString(),noon+DailyQuotaData.OR_FORGIVENESS_MS+1);
        check(freezeOnly.orFrozenByOther(orB,noon+DailyQuotaData.OR_FORGIVENESS_MS+1)
                        &&!DailyQuotaData.orForceExit(freezeOnly.group("or-b")),
                "freeze-only keeps the five-minute boundary without forced exit");
        check(!DailyQuotaData.additive(orSet.config,exitChoice),
                "turning forced exit off is a relaxation that waits for the adjustment window");
        JSONObject mixedExit=MemoryData.copy(orSet.config);
        mixedExit.optJSONArray("groups").optJSONObject(1).put("orForceExit",false);
        reject(()->DailyQuotaData.validate(mixedExit),"mixed forced-exit modes in one OR set are rejected");
        JSONObject longerExit=new JSONObject(config(true,MemoryData.copy(orA),MemoryData.copy(orB)));
        DailyQuotaData.setOrForceExitMinutes(longerExit,"chain-1",3);
        check(!DailyQuotaData.additive(orSet.config,longerExit),
                "increasing the first forced-exit delay is a relaxation");
        JSONObject shorterExit=new JSONObject(config(true,MemoryData.copy(orA),MemoryData.copy(orB)));
        DailyQuotaData.setOrForceExitMinutes(shorterExit,"chain-1",1);
        check(DailyQuotaData.additive(orSet.config,shorterExit),
                "shortening the first forced-exit delay is allowed outside the adjustment window");
        JSONObject mixedDelay=MemoryData.copy(orSet.config);
        mixedDelay.optJSONArray("groups").optJSONObject(1).put("orForceExitMinutes",3);
        reject(()->DailyQuotaData.validate(mixedDelay),"mixed first forced-exit delays in one OR set are rejected");
        // 容错期内即使另一个格子也短暂出现，仍保留首次使用的格子。
        DailyQuotaData grace=new DailyQuotaData(config(true,orA,orB),"{}",noon);
        grace.advance(noon+120000,spans(span(a,noon,noon+60000),span(b,noon+60000,noon+90000)),true);
        check(grace.orChoice("chain-1").equals("or-a")&&!grace.exceeded(a)&&!grace.exceeded(b),"a brief use of another cell inside the grace window does not freeze it");
        DailyQuotaData orReloaded=new DailyQuotaData(config(true,orA,orB),orSet.usage.toString(),noon+DailyQuotaData.OR_FORGIVENESS_MS+1);
        check(orReloaded.orChoice("chain-1").equals("or-a")&&orReloaded.orForceExitedAt("chain-1")==noon+120000,
                "or-set selection and the one-time exit marker survive a process restart");
        check(orReloaded.exceeded(b)&&!orReloaded.orForceExitDue(orReloaded.group(b),noon+DailyQuotaData.OR_FORGIVENESS_MS+1),
                "a restarted process freezes directly without a second forced exit");
        JSONObject legacyOrUsage=new JSONObject().put("date","2026-10-29").put("lastAt",noon+120000)
                .put("targets",new JSONObject()).put("or_choices",new JSONObject().put("chain-1","or-a"));
        DailyQuotaData legacyOr=new DailyQuotaData(config(true,orA,orB),legacyOrUsage.toString(),noon+120000);
        check(legacyOr.exceeded(b),"legacy ledger without grace metadata keeps the immediate-freeze behavior");
        JSONObject plainOr=group("plain-or",120,a);
        check(!DailyQuotaData.sharedCell(plainOr)&&DailyQuotaData.sharedCell(orA),"or-set cells use shared-cell scope");
        Map<String,Rule> orScopes=new HashMap<>();orScopes.put(a,new Rule(Rule.ALL_DAYS,new int[][]{{12*60,13*60}},true,true));
        DailyQuotaData orScoped=new DailyQuotaData(config(true,orA),"{}",at(29,11,59,0));
        orScoped.advance(at(29,11,59,30),spans(span(a,at(29,11,59,0),at(29,11,59,30))),true,orScopes);
        check(orScoped.used(orA)==30000,"or-set counts usage outside a member's own schedule");
        JSONObject orJoin=new JSONObject(config(true,group("join-a",30,a),group("join-b",30,b)));
        check(DailyQuotaData.joinOrGroup(orJoin,"join-a","join-b","or-fresh"),"two cells can form a new or-set");
        check(orJoin.optJSONArray("groups").optJSONObject(0).optString("orGroup").equals("or-fresh")&&
                orJoin.optJSONArray("groups").optJSONObject(1).optString("orGroup").equals("or-fresh"),"both cells receive one or-set id");
        JSONObject leaveOr=MemoryData.copy(orJoin);DailyQuotaData.leaveOrGroup(leaveOr,"join-a");
        check(!leaveOr.optJSONArray("groups").optJSONObject(0).has("orGroup")&&!leaveOr.optJSONArray("groups").optJSONObject(1).has("orGroup"),"leaving a two-cell or-set clears the orphan tag");
        DailyQuotaData removed=new DailyQuotaData(config(true,gb),split.usage.toString(),noon+90000);removed.advance(noon+120000,spans(span(a,noon+90000,noon+120000)),true);DailyQuotaData added=new DailyQuotaData(config(true,ga,gb),removed.usage.toString(),noon+120000);check(added.used(ga)==60000&&added.exceeded(a),"remove and re-add preserves used time without charging removed intervals");
        // 额度全天累计：收束时段（默认 00:00–01:00）之外用掉的时间也必须记账，
        // 否则白天玩了几个小时，「今日已用」还是 0。
        JSONObject allDayGroup=group("allday",120,a);
        DailyQuotaData allDay=new DailyQuotaData(config(true,allDayGroup),"{}",at(29,0,1,0));
        allDay.advance(at(29,15,0,0),spans(span(a,at(29,14,0,0),at(29,15,0,0))),true);
        check(allDay.used(allDayGroup)==3600000L,"usage outside the focus window is still counted");
        // 额度行合并/删除：纯数据层，界面上“长按并到另一个额度”靠这两个函数
        JSONObject mergeConfig=new JSONObject(config(true,group("a",30,a),group("b",20,b)));
        check(DailyQuotaData.mergeInto(mergeConfig,"a","b",20),"mergeInto moves members into the target group");
        check(mergeConfig.optJSONArray("groups").length()==1,"merged config keeps one group");
        JSONObject kept=mergeConfig.optJSONArray("groups").optJSONObject(0);
        check(kept.optString("id").equals("a")&&kept.optInt("minutes")==20&&kept.optJSONArray("apps").length()==2,"merge keeps target id, smaller minutes and both apps");
        check(!DailyQuotaData.mergeInto(mergeConfig,"a","missing",20),"mergeInto refuses a group that no longer exists");
        check(DailyQuotaData.without(new JSONObject(config(true,group("a",30,a),group("b",20,b))),"a").length()==1,"without drops exactly one group");
        // 拖拽迁移：把一只应用从一格挪到另一格，被搬空的格子要一起消失
        JSONObject moveConfig=new JSONObject(config(true,group("a",30,a),group("b",20,b)));
        check(DailyQuotaData.moveInto(moveConfig,"b",a)==1,"moveInto moves an app into the target group");
        check(moveConfig.optJSONArray("groups").length()==1,"emptied source group is removed");
        JSONObject moved=moveConfig.optJSONArray("groups").optJSONObject(0);
        check(moved.optString("id").equals("b")&&moved.optJSONArray("apps").length()==2,"target keeps both apps");
        check(DailyQuotaData.moveInto(moveConfig,"b",a)==0,"moving an app into the same group is a no-op");
        check(DailyQuotaData.moveInto(moveConfig,"missing",a)==-1,"moving into a missing group is refused");
        JSONObject editedShared=group("edited",180,a,b).put("background",new JSONArray(Arrays.asList(a,b)));
        Set<String> pickedSubset=new LinkedHashSet<>(Arrays.asList(a));
        check(DailyQuotaData.editorMembers(editedShared,pickedSubset,null).equals(pickedSubset),"picker return replaces members instead of restoring unchecked apps");
        check(DailyQuotaData.editorMembers(editedShared,Collections.emptySet(),null).isEmpty(),"empty picker selection stays empty");
        check(DailyQuotaData.editorMembers(editedShared,null,null).equals(new LinkedHashSet<>(Arrays.asList(a,b))),"first editor opening uses existing members");
        check(DailyQuotaData.editorMembers(editedShared,pickedSubset,b).size()==2&&pickedSubset.size()==1,"preset app is added without mutating the picker result");
        JSONObject removedShared=new JSONObject(config(true,editedShared));
        check(DailyQuotaData.removeMember(removedShared,"edited",a)==1,"removeMember detaches the selected quota member");
        JSONObject remainingShared=removedShared.optJSONArray("groups").optJSONObject(0);
        check(remainingShared.optJSONArray("apps").length()==1&&b.equals(remainingShared.optJSONArray("apps").optString(0)),"other shared members are preserved");
        check(remainingShared.optInt("minutes")==180&&"edited".equals(remainingShared.optString("id")),"removal preserves group identity and budget");
        check(DailyQuotaData.backgroundApps(remainingShared).equals(new LinkedHashSet<>(Arrays.asList(b))),"removed app is pruned from background counting too");
        DailyQuotaData.validate(removedShared);
        DailyQuotaData quotaAfterRemoval=new DailyQuotaData(removedShared.toString(),split.usage.toString(),noon+90000);
        check(quotaAfterRemoval.usage.optJSONObject("targets").optLong(a)==60000&&quotaAfterRemoval.used(remainingShared)==30000,"removed member's history and remaining member's used time survive");
        check(DailyQuotaData.moveInto(removedShared,"edited",a)==1,"removed app can be re-added without a new quota identity");
        DailyQuotaData quotaAfterReadd=new DailyQuotaData(removedShared.toString(),quotaAfterRemoval.usage.toString(),noon+90000);
        check(quotaAfterReadd.used(removedShared.optJSONArray("groups").optJSONObject(0))==90000,"re-add restores existing same-day usage instead of resetting it");
        check(!DailyQuotaData.additive(new JSONObject(config(true,editedShared)),new JSONObject(config(true,group("edited",180,b)))),"quota member removal still requires the adjustment window");
        JSONObject emptyShared=new JSONObject(config(true,group("empty",60,a)));
        check(DailyQuotaData.removeMember(emptyShared,"empty",a)==1&&emptyShared.optJSONArray("groups").length()==0&&!emptyShared.optBoolean("enabled"),"last member removal deletes the empty group and disables an empty quota config");DailyQuotaData.validate(emptyShared);
        String unchangedShared=emptyShared.toString();check(DailyQuotaData.removeMember(emptyShared,"missing",a)==-1&&unchangedShared.equals(emptyShared.toString()),"stale quota drop cannot change another group");
        JSONObject backgroundMove=new JSONObject(config(true,group("source",30,a,"com.example.c").put("background",new JSONArray(Arrays.asList(a,"com.example.c"))),group("target",20,b)));
        check(DailyQuotaData.moveInto(backgroundMove,"target",a)==1,"background-counted app migrates to another group");DailyQuotaData.validate(backgroundMove);
        check(DailyQuotaData.backgroundApps(backgroundMove.optJSONArray("groups").optJSONObject(0)).equals(new LinkedHashSet<>(Arrays.asList("com.example.c"))),"source background members are clipped after a quota move");
        check(DailyQuotaData.backgroundApps(backgroundMove.optJSONArray("groups").optJSONObject(1)).contains(a),"background counting follows the moved app");

        List<AppUsageQuota.Span> fullDay=spans(span(a,noon,noon+30000),span(b,noon+30000,noon+60000),span(a,noon+60000,noon+90000),span(b,noon+180000,noon+210000));
        DailyQuotaData paused=new DailyQuotaData(config(false,ga,gb),added.usage.toString(),noon+120000);paused.advance(noon+180000,fullDay,true);check(!paused.exceeded(a)&&paused.used(ga)==60000,"paused quota neither blocks nor counts");
        DailyQuotaData resumed=new DailyQuotaData(config(true,ga,gb),paused.usage.toString(),noon+180000);resumed.advance(noon+210000,fullDay,true);check(resumed.used(ga)==60000&&resumed.used(gb)==60000,"resume recomputes the day and keeps per-app totals");
        List<AppUsageQuota.Span> day2=new ArrayList<>(fullDay);day2.add(span(b,noon+210000,noon+240000));
        DailyQuotaData restart=new DailyQuotaData(config(true,ga,gb),resumed.usage.toString(),noon+210000);restart.advance(noon+240000,day2,true);check(restart.used(gb)==90000,"restart recomputes the same day without losing usage");
        DailyQuotaData unreadable=new DailyQuotaData(config(true,ga,gb),"broken",noon);check(unreadable.exceeded(a)&&unreadable.exceeded(b),"corrupt ledger cannot grant another allowance");unreadable.advance(at(30,11,0,1),Collections.emptyList(),true);check(!unreadable.exceeded(a),"corrupt day's protective freeze clears on next quota day (11:00)");
        DailyQuotaData permission=new DailyQuotaData(config(true,ga),"{}",noon);permission.advance(noon+1000,Collections.emptyList(),false);check(permission.exceeded(a),"unreadable events protect enabled group");permission.advance(noon+2000,Collections.emptyList(),true);check(!permission.exceeded(a),"recovered permission clears transient protection");
        long before=at(30,10,59,59),after=at(30,11,0,1);DailyQuotaData midnight=new DailyQuotaData(config(true,ga),"{}",before,11);midnight.advance(after,spans(span(a,before,after)),true,Collections.emptyMap(),11);check(midnight.used(ga)==1000,"quota-day roll at 11:00 counts only the new day's part");
        Rule afternoon=new Rule(Rule.ALL_DAYS,new int[][]{{12*60,13*60}},true,true);
        Map<String,Rule> scopes=new HashMap<>();scopes.put(a,afternoon);
        DailyQuotaData scoped=new DailyQuotaData(config(true,shared),"{}",at(29,11,59,0));
        List<AppUsageQuota.Span> scopedSpans=new ArrayList<>();
        scopedSpans.add(span(a,at(29,11,59,0),at(29,12,0,30)));scopedSpans.add(span(b,at(29,12,0,30),at(29,12,1,0)));
        scoped.advance(at(29,12,1,0),scopedSpans,true,scopes);
        check(scoped.used(shared)==120000,"shared: every member counts all day regardless of its own slot");
        check(!DailyQuotaData.inScope(afternoon,at(29,11,59,59))&&DailyQuotaData.inScope(afternoon,noon),"slot start inclusive; outside is allowed");
        check(!DailyQuotaData.inScope(afternoon,at(29,13,0,0)),"slot end exclusive even if budget exhausted");
        check(DailyQuotaData.inScope(null,noon)&&DailyQuotaData.inScope(FreezeSlotStore.withoutSchedule(),noon),"unassigned and quota-only apps use all-day scope");
        long retained=scoped.used(shared);scopes.put(a,new Rule(Rule.ALL_DAYS,new int[][]{{13*60,14*60}},true,true));
        scopedSpans.add(span(a,at(29,12,1,0),at(29,12,2,0)));
        scoped.advance(at(29,12,2,0),scopedSpans,true,scopes);
        check(scoped.used(shared)==retained+60000,"moving slots does not interrupt shared all-day counting");
        long beforeRecompute=scoped.used(shared);scopedSpans.add(span(a,at(29,12,2,0),at(29,12,2,30)));
        scopes.remove(a);scoped.advance(at(29,12,2,30),scopedSpans,true,scopes);
        // 重算口径：改回全天后天内所有区间都按当前 scope 重算，只升不降
        check(scoped.used(shared)==210000&&scoped.used(shared)>=beforeRecompute,"moving back to all-day re-evaluates the day and never loses usage");
        Rule night=new Rule(Rule.ALL_DAYS,new int[][]{{23*60,60}},true,true);scopes.put(a,night);
        DailyQuotaData overnight=new DailyQuotaData(config(true,ga),"{}",at(29,23,59,59),11);overnight.advance(at(30,0,0,1),spans(span(a,at(29,23,59,59),at(30,0,0,1))),true,scopes,11);
        // 显式使用 11 点额度日：零点不切账本，跨零点的整段都算同一个额度日
        check(overnight.used(ga)==2000,"overnight slot keeps counting across midnight with an 11:00 reset");
        check(DailyQuotaData.inScope(night,at(30,0,59,59))&&!DailyQuotaData.inScope(night,at(30,1,0,0)),"overnight allowance ends at its exact release boundary");
        for(long stamp:new long[]{at(29,12,0,0),at(29,21,0,0),at(29,23,0,0),at(29,3,0,0)})check(!DailyQuotaData.editable(calendar(stamp)),"quota config locked outside default window "+stamp);
        for(long stamp:new long[]{at(29,0,0,0),at(29,0,59,59)})check(DailyQuotaData.editable(calendar(stamp)),"quota config editable inside default window "+stamp);
        // 自定义窗口走同一判断；start==end = 全天 = 随时可改
        check(!DailyQuotaData.editable(calendar(at(29,12,0,0)),1320,60),"custom overnight window locked at noon");
        check(DailyQuotaData.editable(calendar(at(29,23,0,0)),1320,60),"custom overnight window open at 23:00");
        check(DailyQuotaData.editable(calendar(at(29,12,0,0)),600,600),"all-day window always editable");
        // 「只进不出」闸门：窗口外随时能加，减要等收束时段
        JSONObject base=new JSONObject(config(true,group("a",30,a),group("b",20,b)));
        check(DailyQuotaData.additive(base,new JSONObject(config(true,group("a",30,a),group("b",20,b),group("c",15,"com.example.c")))),"a brand new group counts as additive");
        JSONObject linkedOff=new JSONObject(config(true,group("a",30,a),group("b",20,b)));linkedOff.put("learningLink",false);
        JSONObject linkedOn=new JSONObject(config(true,group("a",30,a),group("b",20,b)));linkedOn.put("learningLink",true);
        check(DailyQuotaData.additive(linkedOff,linkedOn),"enabling quota learning-link is additive");
        check(!DailyQuotaData.additive(linkedOn,linkedOff),"disabling quota learning-link needs the edit window");
        check(DailyQuotaData.additive(base,new JSONObject(config(true,group("a",45,a),group("b",20,b)))),"raising minutes counts as additive");
        check(DailyQuotaData.additive(base,new JSONObject(config(true,group("a",30,a,"com.example.c"),group("b",20,b)))),"adding a member to a group counts as additive");
        JSONObject renamed=group("a",30,a);renamed.put("name","renamed");
        check(DailyQuotaData.additive(base,new JSONObject(config(true,renamed,group("b",20,b)))),"renaming a group stays additive");
        JSONObject joinedA=group("a",30,a).put("orGroup","or-base"),joinedB=group("b",20,b).put("orGroup","or-base");
        JSONObject joinedConfig=new JSONObject(config(true,joinedA,joinedB));
        check(DailyQuotaData.additive(base,joinedConfig),"joining an or-set is additive");
        check(!DailyQuotaData.additive(joinedConfig,base),"leaving an or-set needs the edit window");
        JSONObject mergedNameA=group("a",30,a).put("orGroup","or-merged"),mergedNameB=group("b",20,b).put("orGroup","or-merged");
        check(DailyQuotaData.additive(joinedConfig,new JSONObject(config(true,mergedNameA,mergedNameB))),"keeping the same members connected under a new id is additive");
        JSONObject splitA=group("a",30,a).put("orGroup","or-left"),splitB=group("b",20,b).put("orGroup","or-right");
        check(!DailyQuotaData.additive(joinedConfig,new JSONObject(config(true,splitA,splitB))),"splitting an or-set is not additive");
        JSONObject orC=group("c",15,"com.example.c").put("orGroup","or-base");
        check(DailyQuotaData.additive(joinedConfig,new JSONObject(config(true,joinedA,joinedB,orC))),"adding another cell to an or-set is additive");
        check(!DailyQuotaData.additive(base,new JSONObject(config(true,group("a",30,a)))),"deleting a group is not additive");
        check(!DailyQuotaData.additive(base,new JSONObject(config(true,group("a",20,a),group("b",20,b)))),"lowering minutes is not additive");
        check(!DailyQuotaData.additive(base,new JSONObject(config(true,group("a",30,b),group("b",20,a)))),"swapping members is not additive");
        check(!DailyQuotaData.additive(base,new JSONObject(config(false,group("a",30,a),group("b",20,b)))),"switching the quota off is not additive");
        check(DailyQuotaData.additive(new JSONObject(config(false,group("a",30,a))),new JSONObject(config(true,group("a",30,a)))),"switching the quota on is additive");
        check(!DailyQuotaData.additive(null,base),"no current config means nothing is additive");
        reject(()->DailyQuotaData.requireEdit(calendar(at(29,12,0,0))),"writes rejected outside the adjustment window");
        check(DailyQuotaData.nextEdit(calendar(at(29,23,0,0)))==at(30,0,0,0),"next edit window is tomorrow 00:00");
        check(DailyQuotaData.nextEdit(calendar(at(29,10,0,0)))==at(30,0,0,0),"next edit window from daytime is tomorrow 00:00");
        check(DailyQuotaData.nextEdit(calendar(at(29,0,30,0)))==at(29,0,30,0),"inside window edits now");
        check(DailyQuotaData.nextEdit(calendar(at(29,2,0,0)),1320,60)==at(29,22,0,0),"overnight next open tonight 22:00");
        check(DailyQuotaData.nextEdit(calendar(at(29,0,30,0)),1320,60)==at(29,0,30,0),"overnight inside edits now");
        final String duplicate=config(true,ga,group("other",2,a));reject(()->new DailyQuotaData(duplicate,"{}",noon),"same app cannot be in two groups");
        final String badLink=new JSONObject(config(true,ga)).put("learningLink","yes").toString();reject(()->new DailyQuotaData(badLink,"{}",noon),"quota learning-link must be boolean");
        final String empty=config(true);reject(()->new DailyQuotaData(empty,"{}",noon),"enabled empty selection rejected");
        final String invalid=config(true,group("invalid",0,a));reject(()->new DailyQuotaData(invalid,"{}",noon),"zero allowance rejected");
        check(LocalBackup.key("afs_daily_quota","config")&&LocalBackup.key("afs_daily_quota","usage"),"local backup includes config and ledger");
        JSONObject legacy=new JSONObject().put(a,30).put(b,15);
        JSONObject remaining=DailyQuotaData.remainingLegacy(legacy,Collections.singleton(a));
        check(!remaining.has(a)&&remaining.optInt(b)==15,"migration deletes only successful apps and retains failures/conflicts");
        check(legacy.optInt(a)==30&&legacy.optInt(b)==15,"migration cleanup never mutates the backup source");
        check(DailyQuotaData.remainingLegacy(legacy,Collections.emptySet()).length()==2,"no successful migration leaves all legacy limits");
        long change=DayBoundary.changeAt(noon,0,0);
        check(change==at(30,0,0,0),"reset time change is deferred until current quota day ends");
        DailyQuotaData resetPreserved=new DailyQuotaData(config(true,ga),"{}",noon);
        List<AppUsageQuota.Span> resetSpans=new ArrayList<>();
        resetSpans.add(span(a,noon,noon+30000));
        resetPreserved.advance(noon+30000,resetSpans,true);
        resetSpans.add(span(a,noon+30000,noon+60000));
        resetPreserved.advance(noon+60000,resetSpans,true,Collections.emptyMap(),0);
        check(resetPreserved.used(ga)==60000&&resetPreserved.exceeded(a),"pending reset edit preserves and continues today's spent allowance");
        // ── 挂后台也计时（1.88.0）：切到后台「还在跑」＝前台服务在运行；关掉就停，切走闲置不计 ──
        JSONObject bgGroup=new JSONObject().put("id","bg").put("name","bg").put("minutes",1)
                .put("apps",new JSONArray(Arrays.asList(a))).put("background",new JSONArray(Arrays.asList(a)));
        // 前台服务区间与前台区间取并集：5 秒前台 + 50 秒挂后台播放 = 55 秒
        DailyQuotaData burn=new DailyQuotaData(config(true,bgGroup),"{}",noon);
        List<AppUsageQuota.Span> fgMerged=AppUsageQuota.effective(spans(span(a,noon,noon+5000)),spans(span(a,noon+10000,noon+60000)),burn.backgroundPackages());
        burn.advance(noon+60000,fgMerged,true);
        check(burn.used(bgGroup)==55000,"background FGS time joins the usage as a union");
        // 与前台重叠不双倍：前台 30 秒，服务 10s-40s → 一共 40 秒
        DailyQuotaData noDouble=new DailyQuotaData(config(true,bgGroup),"{}",noon);
        List<AppUsageQuota.Span> overlapped=AppUsageQuota.effective(spans(span(a,noon,noon+30000)),spans(span(a,noon+10000,noon+40000)),noDouble.backgroundPackages());
        noDouble.advance(noon+60000,overlapped,true);
        check(noDouble.used(bgGroup)==40000,"overlapping foreground and background merge into one interval");
        // 应用已经关掉：只计前台那 5 秒，不再「打开就一直烧」（1.87.0 的坑）
        DailyQuotaData closed=new DailyQuotaData(config(true,bgGroup),"{}",noon);
        closed.advance(noon+60000,spans(span(a,noon,noon+5000)),true);
        check(closed.used(bgGroup)==5000&&!closed.exceeded(a),"closed app stops counting at its last foreground second");
        // 重算自愈：中间一次读不到用量（系统事件延迟 / 进程重启 / 权限抖动），下一次重算把整段补回来
        DailyQuotaData heal=new DailyQuotaData(config(true,ga),"{}",noon);
        heal.advance(noon+60000,spans(span(a,noon,noon+60000)),true);
        long healed=heal.used(ga);
        heal.advance(noon+120000,Collections.emptyList(),true);
        check(heal.used(ga)==healed,"empty sample never erases counted usage");
        heal.advance(noon+180000,spans(span(a,noon,noon+180000)),true);
        check(heal.used(ga)==180000,"a later sample heals the minutes an earlier refresh missed");
        // 只用「今天这段」：昨天的用量不进今天的账
        DailyQuotaData yesterday=new DailyQuotaData(config(true,ga),new JSONObject().put("date","2026-10-28").put("lastAt",at(28,23,0,0)).put("targets",new JSONObject()).toString(),at(29,12,0,0),11);
        yesterday.advance(at(29,12,0,0),spans(span(a,at(29,9,0,0),at(29,10,0,0)),span(a,at(29,11,0,0),at(29,11,30,0))),true,Collections.emptyMap(),11);
        check(yesterday.used(ga)==1800000,"only the current quota day counts");
        // 没勾的应用完全不受前台服务影响：同样的区间，不勾就只计前台
        JSONObject plainGroup=new JSONObject().put("id","p").put("name","p").put("minutes",1).put("apps",new JSONArray(Arrays.asList(a)));
        DailyQuotaData unflagged=new DailyQuotaData(config(true,plainGroup),"{}",noon);
        List<AppUsageQuota.Span> withFgs=AppUsageQuota.effective(spans(span(a,noon,noon+5000)),spans(span(a,noon+10000,noon+60000)),unflagged.backgroundPackages());
        check(withFgs.size()==1&&withFgs.get(0).end==noon+5000,"unflagged apps ignore foreground-service intervals");
        // 服务还开着（区间没闭合）：一直计到 now
        DailyQuotaData running=new DailyQuotaData(config(true,bgGroup),"{}",noon);
        running.advance(noon+60000,AppUsageQuota.effective(Collections.emptyList(),spans(span(a,noon+10000,noon+60000)),running.backgroundPackages()),true);
        check(running.used(bgGroup)==50000,"a still-running foreground service counts up to now");
        // 自身冻结时段对挂后台区间同样豁免：12:00-13:00 冻结，服务 11:59-12:01 → 只计 11:59-12:00 那一分钟
        Rule freezeNoon=new Rule(Rule.ALL_DAYS,new int[][]{{12*60,13*60}},true,true);
        Map<String,Rule> bgScopes=new HashMap<>();bgScopes.put(a,freezeNoon);
        DailyQuotaData scopedBg=new DailyQuotaData(config(true,bgGroup),"{}",at(29,11,58,0));
        List<AppUsageQuota.Span> scopedFgs=AppUsageQuota.effective(Collections.emptyList(),spans(span(a,at(29,11,59,0),at(29,12,1,0))),scopedBg.backgroundPackages());
        scopedBg.advance(at(29,12,1,0),scopedFgs,true,bgScopes);
        check(scopedBg.used(bgGroup)==60000,"background counting still respects the app's own freeze slot");
        // union 纯函数：重叠与相邻都合成一段，不同应用互不合并
        List<AppUsageQuota.Span> united=AppUsageQuota.union(spans(span(a,0,100),span(a,50,200),span(a,200,300),span(b,100,150)));
        check(united.size()==2,"union keeps packages separate");
        AppUsageQuota.Span mergedA=united.get(0),onlyB=united.get(1);
        check(mergedA.pkg.equals(a)&&mergedA.start==0&&mergedA.end==300,"overlap and adjacency collapse into one interval");
        check(onlyB.pkg.equals(b)&&onlyB.start==100&&onlyB.end==150,"different packages stay separate");
        // 可加不可减：新增挂后台随时可以，取消要等调整时段
        JSONObject plainConfig=new JSONObject(config(true,group("p",30,a)));
        JSONObject withBg=new JSONObject(plainConfig.toString());withBg.optJSONArray("groups").optJSONObject(0).put("background",new JSONArray(Arrays.asList(a)));
        check(DailyQuotaData.additive(plainConfig,withBg),"adding background counting is additive");
        check(!DailyQuotaData.additive(withBg,plainConfig),"cancelling background counting needs the edit window");
        // 校验：挂后台名单必须是本格子成员
        JSONObject stray=new JSONObject().put("id","x").put("name","x").put("minutes",30)
                .put("apps",new JSONArray(Arrays.asList(a))).put("background",new JSONArray(Arrays.asList(b)));
        JSONObject strayConfig=new JSONObject().put("enabled",true).put("groups",new JSONArray(Arrays.asList(stray)));
        reject(()->DailyQuotaData.validate(strayConfig),"background app outside the group is rejected");
        // ── 卸载应用不自动清名单：冻结/限额只允许在调整时段里手动移出 ──
        JSONObject pruneUsage=new JSONObject().put("date","2026-10-29").put("lastAt",noon)
                .put("targets",new JSONObject().put(a,40000).put(b,20000).put("com.example.gone",120000));
        JSONObject pruneConfig=new JSONObject(config(true,group("shared",2,a,b,"com.example.gone"),group("solo",30,"com.example.gone2")));
        DailyQuotaData retainedQuota=new DailyQuotaData(pruneConfig.toString(),pruneUsage.toString(),noon);
        check(retainedQuota.packages().contains("com.example.gone"),"uninstalled app stays in its quota cell");
        check(retainedQuota.group("com.example.gone2").optString("id").equals("solo"),"an uninstalled app's standalone quota is retained");
        check(pruneConfig.optJSONArray("groups").length()==2&&pruneUsage.optJSONObject("targets").optLong("com.example.gone")==120000,"retained quota data remains readable");
        // ── 共享格子优先于自身冻结时段（用户：nextplayer 应该因共享额度用尽冻结）──
        check(DailyQuotaData.exceededBy(true,true,false,false),"shared cell freezes even when the app is inside its own allow window");
        check(DailyQuotaData.exceededBy(true,false,false,true),"shared cell freezes when the cross-device pool is exhausted");
        check(!DailyQuotaData.exceededBy(true,false,false,false),"shared cell allows while it still has budget");
        check(DailyQuotaData.exceededBy(false,true,true,false),"independent cell still freezes inside its scope when exhausted");
        check(!DailyQuotaData.exceededBy(false,true,false,false),"independent cell keeps the in-scope gate (outside scope it lets go)");
        // NextPlayer 真机回归：前台服务被正确检测，但放行时段曾把共享用量全部滤成 0。
        JSONObject sharedBg=new JSONObject().put("id","nextplayer-shared").put("name","shared").put("minutes",1)
                .put("apps",new JSONArray(Arrays.asList(a,b))).put("background",new JSONArray(Arrays.asList(a)));
        Rule allowAll=new Rule(Rule.ALL_DAYS,new int[][]{{12*60,13*60}},true,false);
        Map<String,Rule> sharedRules=new HashMap<>();sharedRules.put(a,allowAll);sharedRules.put(b,allowAll);
        DailyQuotaData nextPlayer=new DailyQuotaData(config(true,sharedBg),"{}",noon);
        List<AppUsageQuota.Span> sharedSpans=AppUsageQuota.effective(spans(span(a,noon,noon+5000),span(b,noon,noon+10000)),
                spans(span(a,noon,noon+60000)),nextPlayer.backgroundPackages());
        nextPlayer.advance(noon+60000,sharedSpans,true,sharedRules);
        check(nextPlayer.used(sharedBg)==70000,"shared background counts during member allow windows without foreground double count");
        check(nextPlayer.exceeded(a)&&nextPlayer.exceeded(b),"counted background exhausts the same shared budget for every member");
        nextPlayer.advance(noon+60000,sharedSpans,true,sharedRules);
        check(nextPlayer.used(sharedBg)==70000,"shared background recomputation is idempotent");
        DailyQuotaData reloaded=new DailyQuotaData(config(true,sharedBg),nextPlayer.usage.toString(),noon+60000);
        reloaded.advance(noon+120000,AppUsageQuota.effective(Collections.emptyList(),spans(span(a,noon,noon+120000)),reloaded.backgroundPackages()),true,sharedRules);
        check(reloaded.usage.optJSONObject("targets").optLong(a)==120000,"persisted shared background continues across process restart");
        DailyQuotaData independent=new DailyQuotaData(config(true,bgGroup),"{}",noon);
        independent.advance(noon+60000,spans(span(a,noon,noon+60000)),true,sharedRules);
        check(independent.used(bgGroup)==0,"independent quota retains its own schedule scope");
        JSONObject modesOn=group("mode",120,a).put("background",new JSONArray(Arrays.asList(a))),modesOff=group("mode",120,a);
        DailyQuotaData epoch=new DailyQuotaData(config(true,modesOn),"{}",noon);
        epoch.advance(noon+3600000,spans(span(a,noon,noon+3600000)),true);
        JSONObject offConfig=new JSONObject(config(true,modesOff));epoch.checkpoint(offConfig,noon+3600000);
        DailyQuotaData off=new DailyQuotaData(offConfig.toString(),epoch.usage.toString(),noon+3600000);
        off.advance(noon+3660000,spans(span(a,noon,noon+600000),span(a,noon+3600000,noon+3660000)),true);
        check(off.used(modesOff)==3660000,"switch off preserves old background and immediately adds subsequent foreground");
        off.advance(noon+3660000,spans(span(a,noon,noon+600000),span(a,noon+3600000,noon+3660000)),true);
        check(off.used(modesOff)==3660000,"mode checkpoint recomputation is idempotent");
        JSONObject onConfig=new JSONObject(config(true,modesOn));off.checkpoint(onConfig,noon+3660000);
        DailyQuotaData onAgain=new DailyQuotaData(onConfig.toString(),off.usage.toString(),noon+3660000);
        onAgain.advance(noon+3720000,spans(span(a,noon,noon+3720000)),true);
        check(onAgain.used(modesOn)==3720000,"switch on counts after transition without retroactive background deduction");
        long nextDay=DayBoundary.startMs(at(30,12,0,0),DayBoundary.DEFAULT_HOUR);
        DailyQuotaData roll=new DailyQuotaData(config(true,modesOn),onAgain.usage.toString(),nextDay+3600000);
        roll.advance(nextDay+3600000,spans(span(a,nextDay,nextDay+3600000)),true);
        roll=new DailyQuotaData(config(true,modesOn),roll.usage.toString(),nextDay+3660000);
        roll.advance(nextDay+3660000,spans(span(a,nextDay,nextDay+3660000)),true);
        check(roll.used(modesOn)==3660000,"next-day backfill keeps growing after reload instead of plateauing");
        check(!roll.usage.has("counting_epochs"),"quota-day rollover clears old mode baselines");
        JSONObject mergeModes=new JSONObject(config(true,group("dest",120,a).put("background",new JSONArray(Arrays.asList(a))),group("src",60,b).put("background",new JSONArray(Arrays.asList(b)))));
        check(DailyQuotaData.mergeInto(mergeModes,"dest","src",60),"merge background groups succeeds");DailyQuotaData.validate(mergeModes);
        check(DailyQuotaData.backgroundApps(mergeModes.optJSONArray("groups").optJSONObject(0)).equals(new HashSet<>(Arrays.asList(a,b))),"merge retains both background flags");
        JSONObject foreignBackground=new JSONObject(config(true,group("first",120,a),group("second",60,b).put("background",new JSONArray(Arrays.asList(a)))));
        reject(()->DailyQuotaData.validate(foreignBackground),"background cannot reference another group's member");
        JSONObject wrongBackground=new JSONObject(config(true,group("badtype",60,a).put("background",true)));
        reject(()->DailyQuotaData.validate(wrongBackground),"malformed background type is rejected");
        JSONObject brokenEpoch=MemoryData.copy(onAgain.usage);brokenEpoch.put("counting_epochs",new JSONObject().put(a,new JSONObject().put("at",noon).put("used",-1)));
        check(new DailyQuotaData(config(true,modesOn),brokenEpoch.toString(),noon).exceeded(a),"invalid mode baseline fails closed");
        // ---- 共享格子：星期冻结（选中的日子整天冻结，不看额度）----
        int monSat=(1<<0)|(1<<5);
        JSONObject weekGroup=group("week",30,a,b).put("freezeDays",monSat);
        DailyQuotaData.validate(new JSONObject(config(true,weekGroup)));
        check(DailyQuotaData.freezeDays(weekGroup)==monSat,"freezeDays bitmap round trips");
        check(DailyQuotaData.weekdayLabel(monSat).contains(T.s("周一"))&&DailyQuotaData.weekdayLabel(monSat).contains(T.s("周六")),"weekday label lists selected days");
        check(DailyQuotaData.weekdayLabel(0).isEmpty(),"no selection has no label");
        Calendar week=Calendar.getInstance();week.clear();
        week.set(Calendar.DAY_OF_WEEK,Calendar.MONDAY);check(DailyQuotaData.freezeToday(weekGroup,week),"Monday is frozen");
        week.set(Calendar.DAY_OF_WEEK,Calendar.SATURDAY);check(DailyQuotaData.freezeToday(weekGroup,week),"Saturday is frozen");
        week.set(Calendar.DAY_OF_WEEK,Calendar.TUESDAY);check(!DailyQuotaData.freezeToday(weekGroup,week),"Tuesday is not frozen");
        week.set(Calendar.DAY_OF_WEEK,Calendar.SUNDAY);check(!DailyQuotaData.freezeToday(weekGroup,week),"Sunday is not frozen");
        check(!DailyQuotaData.freezeToday(group("plain",30,a,b),week),"no selection freezes nothing");
        final JSONObject bad128=new JSONObject(config(true,group("bad",30,a,b).put("freezeDays",128)));
        final JSONObject badNeg=new JSONObject(config(true,group("bad",30,a,b).put("freezeDays",-1)));
        final JSONObject badStr=new JSONObject(config(true,group("bad",30,a,b).put("freezeDays",T.s("周一"))));
        reject(()->DailyQuotaData.validate(bad128),"out of range weekday mask rejected");
        reject(()->DailyQuotaData.validate(badNeg),"negative weekday mask rejected");
        reject(()->DailyQuotaData.validate(badStr),"non-integer weekday mask rejected");
        JSONObject weekBase=new JSONObject(config(true,weekGroup));
        check(DailyQuotaData.additive(weekBase,new JSONObject(config(true,group("week",30,a,b).put("freezeDays",monSat|(1<<2))))),"adding a freeze day is additive");
        check(!DailyQuotaData.additive(weekBase,new JSONObject(config(true,group("week",30,a,b).put("freezeDays",1<<0)))),"removing a freeze day needs the edit window");
        System.out.println("Daily independent/shared quota: "+checks+" checks passed");
    }
}
