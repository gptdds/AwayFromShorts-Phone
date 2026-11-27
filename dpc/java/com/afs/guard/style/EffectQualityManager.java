package com.afs.guard.style;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import com.afs.guard.MotionPolicy;

/**
 * Uses actual completed frame costs, OS capability, memory class and user preference.
 * Never decide from device model alone; user override wins.
 */
public final class EffectQualityManager {
    private static final String KEY="effect_quality"; // auto|high|medium|low|reduced
    private static final int SAMPLE_MIN=10,SAMPLE_MAX=90;
    private static final java.util.ArrayDeque<Long> frames=new java.util.ArrayDeque<>();
    private static volatile int degradation;
    private static final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    private EffectQualityManager(){}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("afs_ui",0);}
    public static String preference(Context c){return prefs(c).getString(KEY,"auto");}
    public static void setPreference(Context c,String value){
        synchronized(frames){frames.clear();degradation=0;}
        prefs(c).edit().putString(KEY,value).apply();
    }
    /** Description used by the settings UI, e.g. "自动 · 高（降档至 低）". */
    public static String describe(Context c){
        String pref=preference(c);
        EffectQuality resolved=resolve(c);
        String label;
        switch(resolved){
            case HIGH:label="高";break;
            case MEDIUM:label="中";break;
            case LOW:label="低";break;
            default:label="减少动效";
        }
        if("auto".equals(pref)&&degradation>0)return "自动 · 降档至"+label;
        return label;
    }
    public static EffectQuality resolve(Context c){
        String pref=preference(c);
        if("reduced".equals(pref)||!MotionPolicy.enabled(c))return EffectQuality.REDUCED_MOTION;
        if("low".equals(pref))return EffectQuality.LOW;
        if("medium".equals(pref))return EffectQuality.MEDIUM;
        if("high".equals(pref))return EffectQuality.HIGH;
        return autoQuality(c,degradation);
    }
    public static void addListener(Runnable listener){listeners.addIfAbsent(listener);}
    public static void removeListener(Runnable listener){listeners.remove(listener);}
    public static void reportFrameNanos(long durationNanos,long intervalNanos){
        reportFrame(FrameBudget.normalizedMillis(durationNanos,intervalNanos));
    }
    /** Render cost from Window FrameMetrics, never the intentional interval between ambient ticks. */
    public static void reportFrame(long durationMs){
        if(durationMs<=0||durationMs>1000)return;
        boolean changed=false;
        synchronized(frames){
            frames.addLast(durationMs);
            while(frames.size()>SAMPLE_MAX)frames.removeFirst();
            if(frames.size()<SAMPLE_MIN)return;
            long sum=0;int slow=0;
            for(long f:frames){sum+=f;if(f>=40)slow++;}
            double average=sum/(double)frames.size();
            if(degradation<2&&average>25&&slow>=SAMPLE_MIN/2){degradation++;frames.clear();changed=true;}
        }
        if(changed)new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
            for(Runnable listener:listeners)listener.run();
        });
    }
    private static EffectQuality autoQuality(Context c,int measured){
        boolean motion=!MotionPolicy.enabled(c);
        if(motion)return EffectQuality.REDUCED_MOTION;
        android.app.ActivityManager memory=(android.app.ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
        if(memory!=null&&memory.isLowRamDevice())return EffectQuality.LOW;
        int base=Build.VERSION.SDK_INT>=33?0:Build.VERSION.SDK_INT>=28?1:2;
        int tier=Math.min(2,base+measured);
        return tier==0?EffectQuality.HIGH:tier==1?EffectQuality.MEDIUM:EffectQuality.LOW;
    }
}
