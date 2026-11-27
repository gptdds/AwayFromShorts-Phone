package com.afs.guard.plugins;
import android.app.Activity;
import com.afs.guard.StatsActivity;
public final class UsageStatsPlugin implements AppPlugin {
    public String id(){return "usage-stats";}
    public String label(){return "屏幕使用统计";}
    public Class<? extends Activity> activity(){return StatsActivity.class;}
}
