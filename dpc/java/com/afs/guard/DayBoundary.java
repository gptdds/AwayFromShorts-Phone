package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 每日额度的「一天」从几点开始。
 *
 * 默认零点（{@link #DEFAULT_HOUR}），可在自律设置中修改小时和分钟；
 * 对习惯熬夜的人，可以改成更晚的时刻，避免凌晨刚过就换到新额度日。
 * 它与「调整时段」({@link TimeWindow}) 是两套独立配置，互不联动。
 * 注意：跨设备共享要求两边同一天翻页，若要改这里必须同步电脑端。
 *
 * 语义只有一个：{@link #startMs(long, int)} 返回「此刻所属额度日的起点」。
 * 所有日界判断（账本翻页、使用记录拉取起点）都必须走它，别各自 set(HOUR_OF_DAY,0)。
 */
public final class DayBoundary {

    static final String SP = "afs_day_boundary";
    static final String KEY_HOUR = "reset_hour";
    /** 一次性迁移标记：旧版的 8 点/11 点默认值统一迁到零点，只做一次。 */
    static final String KEY_UNIFIED_0 = "unified_0am";

    /**
     * 零点。旧版默认的 8 点/11 点会在升级后迁到这里；自定义时刻保留。
     *
     * 改这个值等于移动日界：在新日界之后改，今天的账本键不变、用量保留；
     * 在新日界之前改，会把刚过去的这几个小时划到「昨天」。
     */
    public static final int DEFAULT_HOUR = 0;

    private DayBoundary() {
    }

    public static int hour(Context c) {
        if (c == null) return DEFAULT_HOUR;
        SharedPreferences p = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        int value = saneHour(p.getInt(KEY_HOUR, DEFAULT_HOUR));
        // 只迁移旧默认值；用户改过的其它时刻和带分钟的自定义值保持不变。
        if (!p.getBoolean(KEY_UNIFIED_0, false)) {
            int minute = p.getInt("reset_minute", 0);
            SharedPreferences.Editor edit = p.edit().putBoolean(KEY_UNIFIED_0, true);
            if (minute == 0 && (value == 8 || value == 11)) {
                edit.putInt(KEY_HOUR, DEFAULT_HOUR).putInt("reset_minute", 0)
                        .remove("pending_hour").remove("pending_minute").remove("pending_at");
                value = DEFAULT_HOUR;
            }
            edit.commit();
        }
        int pending=p.getInt("pending_hour",-1);long at=p.getLong("pending_at",Long.MAX_VALUE);
        if(pending>=0&&pending<=23&&System.currentTimeMillis()>=at){
            if(p.edit().putInt(KEY_HOUR,pending).putInt("reset_minute",p.getInt("pending_minute",0)).remove("pending_hour").remove("pending_minute").remove("pending_at").commit())value=pending;
        }
        return saneHour(value);
    }

    private static int saneHour(int value) {
        return value < 0 || value > 23 ? DEFAULT_HOUR : value;
    }

    public static boolean saveHour(Context c, int hour) {
        return saveTime(c,hour,0);
    }

    public static int minute(Context c) {
        hour(c);
        return Math.max(0,Math.min(59,c.getSharedPreferences(SP,0).getInt("reset_minute",0)));
    }

    public static boolean saveTime(Context c, int hour, int minute) {
        if (hour < 0 || hour > 23) throw new IllegalArgumentException(T.s("重置时刻只能是 0–23 时"));
        if (minute < 0 || minute > 59) throw new IllegalArgumentException(T.s("分钟只能是 0–59"));
        int active=hour(c);SharedPreferences p=c.getSharedPreferences(SP,Context.MODE_PRIVATE);
        SharedPreferences.Editor edit=p.edit();
        if(hour==active&&minute==minute(c))return edit.remove("pending_hour").remove("pending_minute").remove("pending_at").commit();
        long now=System.currentTimeMillis(),at=p.getLong("pending_at",0);
        at=at>now?at:nextResetMs(now,active,minute(c));
        return edit.putInt("pending_hour",hour).putInt("pending_minute",minute).putLong("pending_at",at).commit();
    }

    static long changeAt(long now,int active,long existing){return existing>now?existing:nextResetMs(now,active);}

    public static int selectedHour(Context c){int active=hour(c);return c.getSharedPreferences(SP,0).getInt("pending_hour",active);}

    public static int selectedMinute(Context c){int active=minute(c);return c.getSharedPreferences(SP,0).getInt("pending_minute",active);}

    public static String summary(Context c){
        String active=label(c);
        SharedPreferences prefs=c.getSharedPreferences(SP,0);
        if(!prefs.contains("pending_hour"))return active;
        String when=new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(prefs.getLong("pending_at",0)));
        return active+T.s("；")+label(selectedHour(c),selectedMinute(c))+T.s("，")+when+T.s(" 生效");
    }

    /** 此刻所属额度日的起点。hour=0 时与旧的「本地零点」逐毫秒一致。 */
    public static long startMs(long now, int hour) {
        return startMs(now,hour,0);
    }

    public static long startMs(long now, int hour, int minute) {
        int reset = hour < 0 || hour > 23 ? DEFAULT_HOUR : hour;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int current = c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE);
        c.set(Calendar.HOUR_OF_DAY, reset);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (current < reset*60+minute) c.add(Calendar.DAY_OF_MONTH, -1);
        return c.getTimeInMillis();
    }

    public static long startMs(Context c, long now) {
        return startMs(now, hour(c), minute(c));
    }

    /** 账本翻页判据：用的是额度日起点的日期，不是墙上时间的日期。 */
    public static String key(long now, int hour) {
        return key(now,hour,0);
    }

    public static String key(long now, int hour, int minute) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(startMs(now, hour,minute)));
    }

    public static String key(Context c, long now) {
        return key(now, hour(c), minute(c));
    }

    /** 下一次翻页时刻，用来给页面写「x 小时后重置」。 */
    public static long nextResetMs(long now, int hour) {
        return nextResetMs(now,hour,0);
    }

    public static long nextResetMs(long now, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startMs(now, hour,minute));
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** 给界面用的一句话：默认整点写「每天早上 N 点」，其它整点仍按「每日 4:00」。 */
    public static String label(int hour) {
        int reset = hour < 0 || hour > 23 ? DEFAULT_HOUR : hour;
        return reset == 0 ? T.s("每日零点") : String.format(Locale.CHINA, T.s("每日 %d:00"), reset);
    }

    public static String label(Context c) {
        return label(hour(c),minute(c));
    }

    public static String label(int hour,int minute) {
        return minute==0?label(hour):String.format(Locale.CHINA,T.s("每日 %02d:%02d"),hour,minute);
    }
}
