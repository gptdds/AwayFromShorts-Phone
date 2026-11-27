package com.afs.guard;

import org.json.*;
import java.util.*;

public final class CardDataTest {
    private static int checks;
    private static long at(int year,int month,int day,int hour,int minute){Calendar c=Calendar.getInstance();c.clear();c.set(year,month-1,day,hour,minute);return c.getTimeInMillis();}
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static void rejects(Runnable run,String message){try{run.run();}catch(IllegalArgumentException e){checks++;return;}throw new AssertionError(message);}
    private static void rewards(){
        long now=at(2026,10,27,10,0);CardData d=new CardData(now);
        long cooldown=CardData.CLAIM_COOLDOWN_MS;
        rejects(()->d.saveLove(null,"练琴",0),"zero minutes rejected");rejects(()->d.saveLove(null,"练琴",61),"hard maximum enforced");
        String a=d.saveLove(null,"练琴",30),b=d.saveLove(null,"绘画",60);
        rejects(()->d.claim(a,now),"photo is required before claiming a ticket");
        check(d.claim(a,now,"photo-a.jpg"),"card A earns a ticket");
        check("photo-a.jpg".equals(d.earned().optJSONObject(0).optString("photo")),"ticket stores its photo path");
        check(d.claim(b,now,"photo-b.jpg"),"card B earns a ticket the same day (per-card accumulation)");
        check(!d.claim(a,now+1,"photo-a-2.jpg"),"same card cannot earn twice in one day");
        check(d.tickets()==2&&d.tickets(a)==1&&d.tickets(b)==1,"tickets tracked per card");
        check(d.availableTickets(now)==0&&d.waitingTickets(now)==2,"new tickets are locked for 24 hours");
        check(d.availableAt(d.earned().optJSONObject(0))==now+cooldown,"ticket availability is claim time plus 24 hours");
        check(d.nextAvailableAt(a,now)==now+cooldown,"next availability reports the 24-hour boundary");
        check(d.use(a,now)==0,"a freshly claimed ticket cannot be used");
        check(d.use(a,now+cooldown-1)==0,"one millisecond before 24 hours is still locked");
        long tomorrow=at(2026,10,28,10,0);
        check(d.claim(a,tomorrow,"photo-a-next.jpg"),"new day earns another ticket on the same card");
        check(d.tickets()==3&&d.tickets(a)==2,"tickets accumulate across days");
        check(tomorrow==now+cooldown,"calendar fixture is exactly 24 hours later");
        check(d.availableTickets(tomorrow)==2&&d.waitingTickets(tomorrow)==1,"only tickets older than 24 hours are available");
        CardData restored=new CardData(d.db.toString());
        check(restored.tickets()==3&&restored.claimed(a,tomorrow)&&restored.earnedToday(tomorrow),"restart preserves tickets and per-day cap");
        check(restored.availableTickets(tomorrow)==2&&restored.waitingTickets(tomorrow)==1,"restart preserves the 24-hour lock state");
        d.archive(a,true);check(d.tickets(a)==2,"archiving a card keeps its tickets");d.archive(a,false);
        // 手动使用：只能消耗已经满 24 小时的券，窗口时长取该卡配置的分钟数
        check(d.use(a,tomorrow)==30,"ticket unlocks exactly at 24 hours");
        long used=tomorrow+60000L;
        check(d.use(b,used)==60,"using a card spends one ticket for its configured minutes");
        check(d.tickets(b)==0&&d.tickets()==1,"spent tickets are consumed");
        check(d.rewardUntil(used)==used+60*60000L,"unfreeze window ends after card minutes");
        check(d.rewardUntil(used+60*60000L)==0,"window expires exactly at end");
        check(d.use(b,used+1000L)==0,"no ticket left returns 0");
        long secondA=tomorrow+cooldown;
        check(d.use(a,secondA-1)==0,"same card's next ticket still waits for its own 24 hours");
        check(d.use(a,secondA)==30,"same card's next ticket unlocks at its own boundary");
        check(d.tickets()==0,"all available tickets are consumed");
        // 旧抽奖账本迁移：earned 带 minutes、uses 带 refund/closed
        CardData legacyBase=new CardData(now);String oldId=legacyBase.saveLove(null,"旧卡",45);legacyBase.claim(oldId,now,"photo-old.jpg");
        JSONObject oldEntry=legacyBase.earned().optJSONObject(0);oldEntry.remove("used");MemoryData.put(oldEntry,"minutes",37);
        JSONObject oldUse=new JSONObject();MemoryData.put(oldUse,"start",now);MemoryData.put(oldUse,"end",now+600000L);MemoryData.put(oldUse,"refund",0);MemoryData.put(oldUse,"closed",false);
        legacyBase.db.optJSONArray("uses").put(oldUse);
        CardData migrated=new CardData(legacyBase.db.toString());
        check(migrated.migrated,"legacy ledger is migrated");
        check(migrated.tickets(oldId)==1,"legacy earned entry becomes one ticket");
        check(migrated.availableTickets(oldId,now)==0,"legacy ticket without a new field still waits 24 hours");
        check(migrated.availableTickets(oldId,now+cooldown)==1,"legacy ticket unlocks from its stored claim time");
        check(migrated.rewardUntil(now)==0,"legacy automatic remission window is dropped");
        check(new CardData(migrated.db.toString()).tickets(oldId)==1,"migrated ledger reloads cleanly");
        // 回归：用券后的新格式账本（uses 带合法解冻窗口）绝不能被迁移清掉
        CardData spent=new CardData(now);String spentId=spent.saveLove(null,"运动",60);spent.claim(spentId,now,"photo-spent.jpg");
        check(spent.use(spentId,now+cooldown)==60,"ticket spent");
        CardData reloaded=new CardData(spent.db.toString());
        check(reloaded.rewardUntil(now+cooldown)==now+cooldown+60*60000L,"live unfreeze window survives a reload");
        check(!reloaded.migrated,"new-format ledger is never re-migrated");
        check(reloaded.db.optJSONArray("uses").length()==1,"used window is preserved across reloads");
        JSONObject corrupt=MemoryData.copy(migrated.db);corrupt.optJSONArray("earned").put(corrupt.optJSONArray("earned").optJSONObject(0));
        rejects(()->new CardData(corrupt.toString()),"duplicate ticket in storage rejected");
        // 热爱券共同豁免时段冻结、应用使用额度和每日限额；学习联动与罚时仍按原规则。
        Calendar c=Calendar.getInstance();c.setTimeInMillis(now);Rule rule=new Rule(Rule.ALL_DAYS,new int[][]{{0,0}},true,true);
        check(Guard.blockReason(rule,false,false,false,c,true)==0,"ticket can override timed freeze");
        check(Guard.blockReason(rule,false,true,false,c,true)==2,"ticket cannot override learning gate");
        check(Guard.blockReason(rule,false,true,true,c,true)==3,"ticket cannot override taboo punishment");
        check(Guard.blockReason(rule,true,true,true,c,true)==0,"key-app exemption still follows existing semantics");
        check(Guard.blockReason(rule,false,false,false,c,false)==1,"expiry restores timed freeze");
        check(Guard.rewardAdjustedReason(1,true)==0,"reward waives the app schedule");
        check(Guard.rewardAdjustedReason(4,true)==0,"reward waives app usage quota");
        check(Guard.rewardAdjustedReason(5,true)==0,"reward waives exhausted daily quota");
        check(Guard.rewardAdjustedReason(2,true)==2,"reward never waives study linkage");
        check(Guard.rewardAdjustedReason(3,true)==3,"reward never waives punishments");
        check(Guard.rewardAdjustedReason(6,true)==6,"reward never waives key gates");
    }
    private static void summaries(){
        long start=at(2026,10,27,10,0);CardData d=new CardData(start);CycleSummary s=d.summary(start);
        check(s.pending(start)==null,"weekly summary hidden before last day");
        long sun=at(2026,11,1,0,0);CycleSummary.Period p=s.pending(sun);
        check(p!=null&&"2026-10-26".equals(p.start)&&"2026-11-01".equals(p.through),"weekly period calendar boundaries");
        check(!p.holds(at(2026,11,1,19,59))&&p.holds(at(2026,11,1,20,0)),"deadline is local 20:00");
        check(p.holds(at(2026,11,1,23,59)),"23:59 never auto-completes");
        long mon=at(2026,11,2,9,0);CycleSummary.Period overdue=s.pending(mon);
        check(overdue!=null&&overdue.key.equals(p.key)&&overdue.holds(mon),"unfinished summary carries into next cycle and remains holding");
        s.configure("custom",3,"2026-11-02",mon);check(s.queued()!=null&&s.pending(mon).key.equals(p.key),"cycle edits queued without erasing debt");
        check(s.complete(p.key,"本周收获",mon),"overdue summary can be completed on Monday");
        check(!s.complete(p.key,"repeat",mon),"repeat confirmation has no effect");
        check(s.pending(mon)==null,"completion releases summary hold");
        check(s.mode().equals("custom")&&s.days()==3&&s.queued()==null,"queued custom configuration applies after debt completion");
        check(s.recorded(p.key).note.equals("本周收获"),"summary text retained");
        CardData loaded=new CardData(d.db.toString());check(loaded.summary(mon).pending(mon)==null,"restart preserves completed period and cursor");
        check(s.pending(at(2026,11,8,20,0))!=null,"next week requires another summary");
        CardData migrated=new CardData(start);migrated.summary(start).migrateWeek("2026-10-26");
        check(migrated.summary(start).pending(sun)==null,"previous weekly confirmation migrates without new mandatory task");
        CardData daily=new CardData(start);CycleSummary ds=daily.summary(start);ds.configure("daily",1,"2026-10-27",start);
        check(ds.pending(start)!=null&&!ds.pending(start).holds(start),"daily summary available with correct deadline");
        long threeDays=at(2026,10,30,8,0);int completed=0;while(ds.pending(threeDays)!=null&&ds.pending(threeDays).holds(threeDays)){ds.complete(ds.pending(threeDays).key,"补充",threeDays);completed++;}
        check(completed==3,"every missed daily period remains pending until completed");
        check(!ds.pending(threeDays).holds(threeDays),"current day's summary only holds after deadline");
        CardData monthly=new CardData(start);CycleSummary ms=monthly.summary(start);ms.configure("monthly",7,"2026-10-27",start);
        check(ms.pending(at(2026,10,30,23,59))==null,"monthly card not shown early");
        check("2026-10-31".equals(ms.pending(at(2026,10,31,0,0)).through),"monthly final day computed from calendar");
        CardData leap=new CardData(at(2024,2,10,10,0));CycleSummary ls=leap.summary(at(2024,2,10,10,0));ls.configure("monthly",7,"2024-02-10",at(2024,2,10,10,0));
        check(ls.pending(at(2024,2,28,20,0))==null&&ls.pending(at(2024,2,29,20,0))!=null,"leap-year month ends on correct day");
        CardData custom=new CardData(start);CycleSummary cs=custom.summary(start);cs.configure("custom",3,"2026-10-27",start);
        check(cs.pending(at(2026,10,28,20,0))==null&&cs.pending(at(2026,10,29,0,0))!=null,"custom period uses configured start and number of days");
        check(cs.pending(at(2026,10,30,9,0)).holds(at(2026,10,30,9,0)),"custom overdue summary remains enforced");
        rejects(()->new CardData(start).summary(start).configure("custom",0,"2026-10-27",start),"zero-day cycle rejected");
        rejects(()->new CardData(start).summary(start).configure("custom",366,"2026-10-27",start),"invalid cycle length rejected");
        rejects(()->new CardData(start).summary(start).configure("custom",3,"2026-02-30",start),"invalid calendar date rejected");
        rejects(()->s.complete("not-this-period","wrong",mon),"stale confirmation cannot complete another period");
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));long dstStart=at(2026,10,27,10,0);CardData dst=new CardData(dstStart);
        dst.summary(dstStart).configure("custom",7,"2026-10-27",dstStart);
        check("2026-11-02".equals(dst.summary(dstStart).pending(at(2026,11,2,20,0)).through),"custom calendar cycle survives daylight saving change");
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }
    private static Set<String> modes(String... values){return new LinkedHashSet<>(Arrays.asList(values));}
    private static void overlappingSummaries(){
        long start=at(2026,5,25,9,0),end=at(2026,5,31,20,0),monday=at(2026,6,1,9,0);
        CardData d=new CardData(start);CycleSummary s=d.summary(start);
        s.configureAll(modes("weekly","monthly"),7,"2026-05-25",start);
        check(s.enabled("weekly")&&s.enabled("monthly")&&!s.enabled("daily"),"weekly and monthly enabled together");
        check(s.allPending(at(2026,5,30,23,59)).isEmpty(),"both cycles hidden before last day");
        List<CycleSummary.Period> tasks=s.allPending(end);check(tasks.size()==2,"overlapping month and week create two cards");
        check(!tasks.get(0).key.equals(tasks.get(1).key),"independent keys for coincident deadlines");
        CycleSummary.Period weekly=tasks.get(0),monthly=tasks.get(1);
        check(weekly.through.equals(monthly.through)&&weekly.holds(end)&&monthly.holds(end),"both independently due at 20:00");
        check(s.complete(weekly.key,"每周",monday),"weekly report completes independently");
        check(s.allPending(monday).size()==1&&s.pending(monday).key.equals(monthly.key)&&s.pending(monday).holds(monday),"monthly debt remains after weekly completion across rollover");
        CycleSummary restored=new CardData(d.db.toString()).summary(monday);
        check(restored.allPending(monday).size()==1&&restored.recorded(weekly.key)!=null,"restart retains active cycles and independent records");
        check(restored.complete(monthly.key,"每月",monday)&&restored.pending(monday)==null,"last completion releases summary hold");
        check(restored.records().length()==2&&restored.recorded(monthly.key).note.equals("每月"),"archive includes all cycles");
        rejects(()->restored.complete("daily|2026-05-31/2026-06-01","wrong",monday),"unknown cycle cannot complete a different card");
        check(!restored.complete(monthly.key,"again",monday),"duplicate monthly completion is idempotent");
        CardData disabled=new CardData(start);CycleSummary debt=disabled.summary(start);debt.configureAll(modes("weekly","monthly"),7,"2026-05-25",start);
        debt.configureAll(modes("monthly"),7,"2026-05-25",end);
        check(!debt.enabled("weekly")&&debt.allPending(monday).size()==2,"unselecting cycle preserves its already due debt");
        String weekKey=debt.allPending(monday).get(0).key;debt.complete(weekKey,"补完",monday);
        check(debt.allPending(at(2026,6,7,20,0)).size()==1,"disabled cycle stops new cards after retained debt is paid");
        debt.configureAll(modes("weekly","monthly"),7,"2026-05-25",at(2026,6,8,9,0));
        check(debt.allPending(at(2026,6,8,9,0)).size()==1,"re-enabling does not backfill periods while disabled");
        CycleSummary coincident=new CardData(start).summary(start);coincident.configureAll(modes("weekly","custom"),7,"2026-05-25",start);
        List<CycleSummary.Period> same=coincident.allPending(end);
        check(same.size()==2&&same.get(0).start.equals(same.get(1).start)&&!same.get(0).key.equals(same.get(1).key),"identical date ranges retain separate cycle identity");
        coincident.complete(same.get(1).key,"自定义",end);check(coincident.allPending(end).size()==1,"completing custom cannot consume weekly with same dates");
        CycleSummary freshOnSunday=new CardData(end).summary(end);freshOnSunday.configureAll(modes("weekly","monthly"),7,"2026-05-31",end);
        check(freshOnSunday.allPending(end).size()==2&&freshOnSunday.settings("monthly").mode().equals("monthly"),"adding monthly on a Sunday creates monthly immediately without queued weekly default");
        CycleSummary legacy=new CardData(start).summary(start);legacy.migrateWeek("2026-05-25");legacy.configureAll(modes("weekly","monthly"),7,"2026-05-25",start);
        check(legacy.allPending(end).size()==1&&legacy.records().length()==1,"legacy completed week retained when adding monthly");
        rejects(()->legacy.configureAll(modes(),7,"2026-05-25",start),"empty cycle selection rejected");
        rejects(()->legacy.configureAll(modes("monthly","custom"),0,"2026-05-25",start),"invalid custom cycle rejected before changing other plans");
    }
    private static void arbitraryDaySummaries(){
        long start=at(2026,10,29,9,0);CardData d=new CardData(start);CycleSummary s=d.summary(start);s.configureAll(modes("custom"),21,"2026-10-29",start);
        check(s.settings("custom").days()==21&&s.pending(at(2026,11,17,23,59))==null,"21-day cycle does not open a day early");
        CycleSummary.Period p=s.pending(at(2026,11,18,0,0));check(p!=null&&p.start.equals("2026-10-29")&&p.through.equals("2026-11-18"),"21-day cycle spans month boundary using exact calendar days");
        check(p.holds(at(2026,11,18,20,0))&&s.pending(at(2026,11,19,9,0)).key.equals(p.key),"21-day overdue task survives rollover");
        check(s.complete(p.key,"21天总结",at(2026,11,19,9,0))&&s.pending(at(2026,11,19,9,0))==null,"completing 21-day task clears only its debt");
        check(s.pending(at(2026,12,9,0,0)).through.equals("2026-12-09"),"21-day cycle repeats from anchor without drifting to week or month");
        CardData m=new CardData(start);CycleSummary forty=m.summary(start);forty.configureAll(modes("weekly","monthly","custom"),40,"2026-10-29",start);
        CycleSummary custom=forty.settings("custom");check(custom.pending(at(2026,12,6,23,59))==null,"40-day cycle hidden before its actual final day");
        CycleSummary.Period last=custom.pending(at(2026,12,7,0,0));check(last!=null&&last.through.equals("2026-12-07"),"40-day end is independent of month length");
        custom.complete(last.key,"40天总结",at(2026,12,7,20,0));check(custom.pending(at(2027,1,16,0,0)).through.equals("2027-01-16"),"40-day cycle repeats across year boundary");
        CycleSummary loaded=new CardData(m.db.toString()).summary(at(2026,12,8,0,0));check(loaded.settings("custom").days()==40&&loaded.enabled("weekly")&&loaded.enabled("monthly"),"restart retains arbitrary days and concurrent calendar cycles");
    }
    public static void main(String[] args){TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));rewards();summaries();overlappingSummaries();arbitraryDaySummaries();System.out.println("Card rewards and cycles: "+checks+" checks passed");}
}
