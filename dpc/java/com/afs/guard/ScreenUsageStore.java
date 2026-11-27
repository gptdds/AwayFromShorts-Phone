package com.afs.guard;

import android.app.usage.*;
import android.content.*;
import android.content.pm.*;
import java.util.*;

final class ScreenUsageStore {
    static final String SP="afs_screen_stats";
    private static volatile long collectedAt;
    private static final java.util.concurrent.atomic.AtomicBoolean queued=new java.util.concurrent.atomic.AtomicBoolean();
    static ScreenUsageData load(Context c)throws Exception{
        String raw=c.getSharedPreferences(SP,0).getString("archive","");return raw.isEmpty()?new ScreenUsageData():new ScreenUsageData(raw);
    }
    static synchronized ScreenUsageData collect(Context c)throws Exception{
        ScreenUsageData data=load(c);
        if(!StatsActivity.hasUsageAccess(c))return data;
        if(System.currentTimeMillis()-collectedAt<10000L)return data;
        UsageStatsManager manager=(UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE);
        if(manager==null)throw new IllegalStateException(T.s("统计服务不可用"));
        long now=System.currentTimeMillis();
        Calendar start=Calendar.getInstance();start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);start.add(Calendar.DAY_OF_YEAR,-10);
        if(!data.lastDay().isEmpty()){
            Calendar recent=Calendar.getInstance();recent.setTime(new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(data.lastDay()));recent.add(Calendar.DAY_OF_YEAR,-1);
            if(recent.after(start)&&recent.getTimeInMillis()<now)start=recent;
        }
        AppUsageQuota.Timeline timeline=new AppUsageQuota.Timeline(start.getTimeInMillis(),"");UsageEvents.Event event=new UsageEvents.Event();
        for(long cursor=start.getTimeInMillis()-6*3600000L;cursor<now;){
            long end=Math.min(now,cursor+3600000L);UsageEvents events=manager.queryEvents(cursor,end);
            if(events==null)throw new IllegalStateException(T.s("统计服务不可用"));
            while(events.hasNextEvent()){events.getNextEvent(event);int type=event.getEventType();
                if(type==1||type==2||type==16||type==17||type==26||type==27)timeline.accept(type,event.getPackageName(),null,event.getTimeStamp());
            }cursor=end;
        }
        Map<String,Map<String,Long>> daily=calendarDays(timeline.foreground(now));
        for(String key:daily.keySet())data.sample("day",key,daily.get(key));
        sample(c,data,manager.queryUsageStats(UsageStatsManager.INTERVAL_MONTHLY,0,now),"month");
        sample(c,data,manager.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY,0,now),"year");
        Set<String> packages=new HashSet<>(data.period("total","").keySet());
        for(String p:packages)if(!data.labels.containsKey(p))try{ApplicationInfo app=c.getPackageManager().getApplicationInfo(p,0);data.labels.put(p,c.getPackageManager().getApplicationLabel(app).toString());}catch(PackageManager.NameNotFoundException ignored){}
        if(!c.getSharedPreferences(SP,0).edit().putString("archive",data.json()).commit())throw new IllegalStateException(T.s("统计保存失败"));
        collectedAt=now;return data;
    }
    static Map<String,Map<String,Long>> calendarDays(List<AppUsageQuota.Span> spans){
        Map<String,Map<String,Long>> days=new TreeMap<>();
        for(AppUsageQuota.Span span:spans)for(long cursor=span.start;cursor<span.end;){
            Calendar midnight=Calendar.getInstance();midnight.setTimeInMillis(cursor);midnight.set(Calendar.HOUR_OF_DAY,0);midnight.set(Calendar.MINUTE,0);midnight.set(Calendar.SECOND,0);midnight.set(Calendar.MILLISECOND,0);
            String key=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(midnight.getTime());midnight.add(Calendar.DAY_OF_YEAR,1);long end=Math.min(span.end,midnight.getTimeInMillis());
            Map<String,Long> row=days.get(key);if(row==null){row=new HashMap<>();days.put(key,row);}row.put(span.pkg,(row.containsKey(span.pkg)?row.get(span.pkg):0)+end-cursor);cursor=end;
        }return days;
    }
    private static void sample(Context c,ScreenUsageData data,List<UsageStats> list,String kind){
        if(list==null)return;
        Map<String,Map<String,Long>> buckets=new TreeMap<>();
        for(UsageStats u:list){
            if(u==null||u.getFirstTimeStamp()<=0)continue;
            Calendar date=Calendar.getInstance();date.setTimeInMillis(u.getFirstTimeStamp());
            String key=new java.text.SimpleDateFormat("day".equals(kind)?"yyyy-MM-dd":"month".equals(kind)?"yyyy-MM":"yyyy",Locale.US).format(date.getTime());
            String last=new java.text.SimpleDateFormat("month".equals(kind)?"yyyy-MM":"yyyy",Locale.US).format(new Date(Math.max(u.getFirstTimeStamp(),u.getLastTimeStamp()-1)));
            if(!key.equals(last))continue;
            Map<String,Long> row=buckets.get(key);if(row==null){row=new HashMap<>();buckets.put(key,row);}
            String pkg=u.getPackageName();long previous=row.containsKey(pkg)?row.get(pkg):0;
            row.put(pkg,previous+Math.max(0,u.getTotalTimeInForeground()));
        }
        for(String k:buckets.keySet())data.sample(kind,k,buckets.get(k));
    }
    static Set<String> entertainment(Context c,Set<String> packages){
        SharedPreferences p=c.getSharedPreferences(SP,0);
        if(p.getBoolean("fun_custom",false))return new HashSet<>(p.getStringSet("fun_packages",Collections.emptySet()));
        Set<String> result=new HashSet<>(RuleStore.packages(c));
        for(String pkg:packages)try{if(isGame(c.getPackageManager().getApplicationInfo(pkg,0)))result.add(pkg);}catch(PackageManager.NameNotFoundException ignored){}
        return result;
    }
    static boolean isGame(ApplicationInfo app){return android.os.Build.VERSION.SDK_INT>=26?app.category==ApplicationInfo.CATEGORY_GAME:(app.flags&ApplicationInfo.FLAG_IS_GAME)!=0;}
    static boolean saveEntertainment(Context c,Set<String> packages){return c.getSharedPreferences(SP,0).edit().putBoolean("fun_custom",true).putStringSet("fun_packages",new HashSet<>(packages)).commit();}
    static void collectLater(Context context){
        if(!com.afs.guard.plugins.BuiltInPluginStore.enabled(context,"usage-stats")||!StatsActivity.hasUsageAccess(context))return;
        if(System.currentTimeMillis()-collectedAt<3600000L||!queued.compareAndSet(false,true))return;
        Context c=context.getApplicationContext();
        new Thread(()->{try{collect(c);}catch(Exception e){android.util.Log.w("AFSStats","usage archive unavailable",e);}finally{queued.set(false);}},"afs-usage-archive").start();
    }
}
