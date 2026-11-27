package com.afs.guard;
import java.util.*;
public final class ScreenUsageTest {
    private static int n;
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);n++;}
    private static Map<String,Long> rows(long a,long b){Map<String,Long> r=new HashMap<>();r.put("app.a",a);r.put("app.b",b);return r;}
    public static void main(String[] args)throws Exception{
        ScreenUsageData d=new ScreenUsageData();
        d.sample("day","2026-11-12",rows(100,300));d.sample("day","2026-11-13",rows(200,400));
        check(d.period("day","2026-11-13").get("app.a")==200,"day isolated");
        check(d.period("month","2026-11").get("app.a")==300,"distinct days sum");
        d.sample("day","2026-11-13",rows(200,400));
        check(d.period("month","2026-11").get("app.a")==300,"resampling is not an increment");
        d.sample("month","2026-11",rows(1000,2000));d.sample("year","2026",rows(5000,6000));
        check(d.period("month","2026-11").get("app.a")==1000,"system month supplies older retained history");
        check(d.period("year","2026").get("app.a")==5000,"year and daily snapshots never double count");
        d.sample("year","2025",rows(100,200));
        check(d.period("total","").get("app.a")==5100,"total sums distinct years");
        d.sample("year","2026",Collections.emptyMap());
        check(d.period("total","").get("app.a")==5100,"system retention expiry does not erase archive");
        d.sample("day","2027-01-01",rows(50,50));
        check(d.period("total","").get("app.a")==5150,"new year accumulates independently");
        check(d.period("year","2027").get("app.b")==50,"daily fallback works without yearly bucket");
        check(d.period("day","2020-01-01").isEmpty(),"unavailable historical day stays unavailable");
        check(ScreenUsageData.percent(rows(100,300),Collections.singleton("app.b"))==75,"custom classification");
        check(ScreenUsageData.percent(rows(100,300),Collections.emptySet())==0,"explicit empty selection means zero entertainment");
        check(ScreenUsageData.percent(Collections.emptyMap(),Collections.singleton("app.a"))==0,"zero denominator");
        d.labels.put("app.a","Example");ScreenUsageData copy=new ScreenUsageData(d.json());
        check(copy.period("total","").equals(d.period("total","")),"archive round trip preserves totals");
        check(copy.labels.get("app.a").equals("Example"),"uninstalled labels survive");
        boolean rejected=false;try{new ScreenUsageData("{\"version\":1,\"days\":{\"2026-11-13\":{\"app.a\":-1}}}");}catch(Exception e){rejected=true;}check(rejected,"negative restored usage rejected");
        check(LocalBackup.key("afs_screen_stats","archive")&&LocalBackup.key("afs_screen_stats","fun_packages"),"archive and classification included in full backup");
        check(!LocalBackup.key("afs_screen_stats","other"),"backup whitelist remains closed");
        TimeZone previous=TimeZone.getDefault();try{
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);
            AppUsageQuota.Span span=new AppUsageQuota.Span("app.a",format.parse("2026-11-12 23:50").getTime(),format.parse("2026-11-13 00:10").getTime());
            Map<String,Map<String,Long>> split=ScreenUsageStore.calendarDays(Collections.singletonList(span));
            check(split.get("2026-11-12").get("app.a")==600000L,"cross-midnight portion belongs to previous date");
            check(split.get("2026-11-13").get("app.a")==600000L,"today receives the clipped portion");
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));format.setTimeZone(TimeZone.getDefault());
            span=new AppUsageQuota.Span("app.a",format.parse("2026-03-08 00:00").getTime(),format.parse("2026-03-09 00:00").getTime());
            split=ScreenUsageStore.calendarDays(Collections.singletonList(span));
            check(split.size()==1&&split.get("2026-03-08").get("app.a")==23*3600000L,"DST day uses calendar midnight rather than fixed 24 hours");
        }finally{TimeZone.setDefault(previous);}
        System.out.println("Screen usage: "+n+" checks passed");
    }
}
