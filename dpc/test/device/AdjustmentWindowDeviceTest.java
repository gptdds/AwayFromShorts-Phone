package com.afs.guard;
import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import java.io.File;
import java.lang.reflect.*;
/** 非调整时段不能调整调整时段：锁住 TimeWindow.save 本身。 */
public final class AdjustmentWindowDeviceTest extends Instrumentation {
    private int checks;
    private void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
    private Object call(Class<?> type,String method,Class<?>[] types,Object...args)throws Exception{
        Method m=type.getDeclaredMethod(method,types);m.setAccessible(true);
        try{return m.invoke(null,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}
    }
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Bundle result=new Bundle();try{
        Context base=getTargetContext();
        String prefix="aw_qa_"+System.nanoTime()+"_";
        Context c=new ContextWrapper(base){
            public Context getApplicationContext(){return this;}
            public String getPackageName(){return "com.afs.guard.fixture";}
            public File getFilesDir(){File f=new File(base.getCacheDir(),prefix);f.mkdirs();return f;}
            public SharedPreferences getSharedPreferences(String n,int mode){return base.getSharedPreferences(prefix+n,mode);}
        };
        Class<?> tw=Class.forName("com.afs.guard.TimeWindow",true,base.getClassLoader());
        // 先明确关闭首次宽限：armed=true，first_seen 很久以前
        SharedPreferences guard0=c.getSharedPreferences("afs_guard",0);
        guard0.edit().putLong("first_seen",0L).putBoolean("armed",true).commit();
        int start=(Integer)call(tw,"start",new Class<?>[]{Context.class},c);
        int end=(Integer)call(tw,"end",new Class<?>[]{Context.class},c);
        check(start==0&&end==60,"default adjustment window 00:00-01:00");
        int nowMinute=java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)*60
                +java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE);
        c.getSharedPreferences("afs_time_window",0).edit()
                .putInt("start",(nowMinute+10)%1440)
                .putInt("end",(nowMinute+20)%1440)
                .putBoolean("cleared",false)
                .putBoolean("default_22_23",true).commit();
        // 非调整时段：save 必须被拒绝
        boolean rejectedOutside=false;
        try{call(tw,"save",new Class<?>[]{Context.class,int.class,int.class},c,21*60,22*60);}
        catch(RuntimeException e){rejectedOutside=true;}
        check(rejectedOutside,"TimeWindow.save rejected outside the adjustment window");
        check(((Integer)call(tw,"start",new Class<?>[]{Context.class},c))==(nowMinute+10)%1440,"window start unchanged after rejected save");
        check(((Integer)call(tw,"end",new Class<?>[]{Context.class},c))==(nowMinute+20)%1440,"window end unchanged after rejected save");
        // 首次宽限期内：save 允许
        SharedPreferences guard=c.getSharedPreferences("afs_guard",0);
        guard.edit().putLong("first_seen",System.currentTimeMillis()).putBoolean("armed",false).commit();
        call(tw,"save",new Class<?>[]{Context.class,int.class,int.class},c,21*60,22*60);
        check(((Integer)call(tw,"start",new Class<?>[]{Context.class},c))==21*60,"window start saved during first-run grace");
        check(((Integer)call(tw,"end",new Class<?>[]{Context.class},c))==22*60,"window end saved during first-run grace");
        // 宽限期结束后再次拒绝
        guard.edit().putBoolean("armed",true).commit();
        boolean rejectedAfterGrace=false;
        try{call(tw,"save",new Class<?>[]{Context.class,int.class,int.class},c,22*60,23*60);}
        catch(RuntimeException e){rejectedAfterGrace=true;}
        check(rejectedAfterGrace,"TimeWindow.save rejected after grace expires");
        check(((Integer)call(tw,"start",new Class<?>[]{Context.class},c))==21*60,"window start unchanged after grace expired");
        result.putString("stream","Adjustment window device: "+checks+" checks passed\n");finish(-1,result);
    }catch(Throwable e){
        result.putString("stream","FAILED "+android.util.Log.getStackTraceString(e));finish(1,result);
    }}
}
