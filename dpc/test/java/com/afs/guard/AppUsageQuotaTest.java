package com.afs.guard;
import java.util.Calendar;
public final class AppUsageQuotaTest {
    private static int checks;
    private static long total(java.util.List<AppUsageQuota.Span> spans){long total=0;for(AppUsageQuota.Span s:spans)total+=s.end-s.start;return total;}
    private static long time(int day,int hour,int minute,int second){Calendar c=Calendar.getInstance();c.clear();c.set(2026,9,day,hour,minute,second);return c.getTimeInMillis();}
    private static void eq(long expected,long actual,String label){checks++;if(expected!=actual)throw new AssertionError(label+": "+actual+" != "+expected);}
    public static void main(String[] args){
        Rule day=new Rule(Rule.ALL_DAYS,new int[][]{{600,660}},true,true);
        eq(60000,AppUsageQuota.overlap(time(28,9,59,30),time(28,10,0,30),null),"global exact seconds");
        eq(30000,AppUsageQuota.overlap(time(28,9,59,30),time(28,10,0,30),day),"only in-slot seconds");
        eq(0,AppUsageQuota.overlap(time(28,9,0,0),time(28,9,59,59),day),"outside slot ignored");
        eq(3600000,AppUsageQuota.overlap(time(28,9,0,0),time(28,12,0,0),day),"clipped interval");
        eq(1000,AppUsageQuota.overlap(time(28,10,59,59),time(28,11,0,1),day),"end exclusive");
        Rule night=new Rule(Rule.ALL_DAYS,new int[][]{{1380,60}},true,true);
        eq(7200000,AppUsageQuota.overlap(time(28,22,0,0),time(29,2,0,0),night),"overnight window");
        eq(0,AppUsageQuota.overlap(time(28,10,0,0),time(28,10,0,0),day),"empty usage");
        eq(0,AppUsageQuota.overlap(time(28,10,0,1),time(28,10,0,0),day),"reversed timestamps");
        Rule wednesday=new Rule(4,new int[][]{{1380,60}},true,true);
        eq(3600000,AppUsageQuota.overlap(time(29,0,0,0),time(29,2,0,0),wednesday),"carry weekday across midnight");
        eq(0,AppUsageQuota.overlap(time(30,0,0,0),time(30,2,0,0),wednesday),"wrong weekday");
        AppUsageQuota.Timeline live=new AppUsageQuota.Timeline(1000,"own");
        live.accept(1,"player",null,1500);
        eq(2500,total(live.foreground(4000)),"open foreground counts before pause");
        eq(1,live.foreground(4000).get(0).active?1:0,"live foreground is marked active");
        live.accept(2,"player",null,4500);
        eq(3000,total(live.foreground(9000)),"paused foreground stops exactly at event");
        AppUsageQuota.Timeline media=new AppUsageQuota.Timeline(1000,"own");
        media.accept(19,"player","one",500);media.accept(19,"own","guard",600);
        eq(3000,total(media.services(4000)),"pre-boundary service clipped and own guard excluded");
        media.accept(19,"player","two",2000);media.accept(20,"player","one",3000);
        eq(4000,total(media.services(5000)),"stopping one service leaves another active without double counting");
        media.accept(1,"player",null,3500);media.accept(26,null,null,6000);
        eq(5000,total(media.services(9000)),"shutdown closes background services");
        eq(2500,total(media.foreground(9000)),"same shutdown also closes foreground");
        eq(0,media.services(9000).get(0).active?1:0,"shutdown clears active service flags");
        AppUsageQuota.Timeline carry=new AppUsageQuota.Timeline(1000,"own");
        carry.accept(21,"player","one",1000);carry.accept(22,"player","one",2000);
        carry.accept(21,"player","one",2000);
        eq(3000,total(carry.services(4000)),"rollover and continuing intervals merge across chunks");
        carry.accept(27,null,null,4500);
        eq(1000,total(carry.services(8000)),"startup discards unmatched open service instead of charging powered-off gap");
        eq(time(29,0,0,0),AppUsageQuota.queryStart(time(29,8,0,0)),"8am quota retains system midnight continuing events");
        eq(time(28,18,0,0),AppUsageQuota.queryStart(time(29,0,0,0)),"midnight quota retains six-hour lookback");
        AppUsageQuota.Timeline locked=new AppUsageQuota.Timeline(0,"own");
        locked.accept(1,"player",null,1000);locked.accept(19,"player","service",1000);locked.accept(16,null,null,2000);locked.accept(17,null,null,2100);
        eq(1000,total(locked.foreground(5000)),"screen off stops foreground charging");
        eq(4000,total(locked.services(5000)),"screen off and keyguard do not stop background service charging");
        locked.accept(20,"player","service",6000);
        eq(5000,total(locked.services(9000)),"service stop prevents later idle charging");
        System.out.println("App usage quota: "+checks+" checks passed");
    }
}
