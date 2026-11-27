package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Field;
import java.util.*;

/** Real installed data and native view checks. Changes only the current UI tab/expansion. */
public final class ScreenStatsDeviceTest extends Instrumentation {
    private int checks;private StatsActivity page;
    private void check(boolean v,String label){if(!v)throw new AssertionError(label);checks++;}
    private Object field(String name)throws Exception{Field f=StatsActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(page);}
    private int rankRows(View v){int n=v instanceof TextView&&((TextView)v).getText().toString().matches("\\d+\\. .+")?1:0;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)n+=rankRows(((ViewGroup)v).getChildAt(i));return n;}
    private TextView find(View root,String text){if(root instanceof TextView&&((TextView)root).getText().toString().equals(text))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),text);if(t!=null)return t;}return null;}
    private void click(String text){runOnMainSync(()->{TextView view=find(page.getWindow().getDecorView(),text);if(view==null)throw new AssertionError("missing control: "+text);view.performClick();});}
    private int count(){int[] n={0};runOnMainSync(()->n[0]=rankRows(page.getWindow().getDecorView()));return n[0];}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        page=(StatsActivity)startActivitySync(new Intent(getTargetContext(),StatsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long until=android.os.SystemClock.elapsedRealtime()+45000;boolean[] busy={true};
        do{runOnMainSync(()->{try{busy[0]=(Boolean)field("loading");}catch(Exception e){throw new RuntimeException(e);}});if(!busy[0])break;Thread.sleep(100);}while(android.os.SystemClock.elapsedRealtime()<until);
        check(!busy[0],"native load completes");
        ScreenUsageData data=(ScreenUsageData)field("data");String date=(String)field("date");
        check(ScreenUsageData.total(data.period("day",date))>0,"natural today has real usage");
        check(page.getPackageName().equals("com.afs.guard"),"page runs inside the host");
        for(String[] period:new String[][]{{"day","日统计"},{"month","月统计"},{"year","年统计"},{"total","总统计"}}){
            click(T.s(period[1]));String key=period[0].equals("day")?date:period[0].equals("month")?date.substring(0,7):date.substring(0,4);
            int size=data.period(period[0],key).size();check(count()==Math.min(15,size),period[0]+" has at most fifteen initial rows");
            if(size>15){click(T.f("展开其余 %d 个应用",size-15));check(count()==size,"expand shows every app");click(T.s("收起"));check(count()==15,"collapse restores fifteen rows");}
        }
        ScreenUsageData archived=ScreenUsageStore.load(getTargetContext());check(archived.period("total","").equals(data.period("total","")),"persisted archive matches displayed totals");
        result.putString("stream","Screen stats: "+checks+" checks passed\n");finish(-1,result);
    }catch(Throwable failure){result.putString("stream","Screen stats FAILED: "+failure+"\n");finish(0,result);}}
}
