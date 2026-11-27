package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Read-only business-data audit: edits only unsaved dialogs and never saves fixture config. */
public final class BackgroundQuotaAuditTest extends Instrumentation {
    int checks;Activity board;
    void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    AccessibilityNodeInfo find(AccessibilityNodeInfo node,String value,boolean prefix){
        if(node==null)return null;CharSequence text=node.getText();
        if(text!=null&&(prefix?text.toString().startsWith(value):text.toString().equals(value)))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),value,prefix);if(found!=null)return found;}return null;
    }
    AccessibilityNodeInfo waitText(String value,boolean prefix)throws Exception{
        long end=SystemClock.elapsedRealtime()+8000;AccessibilityNodeInfo found;
        do{found=find(getUiAutomation().getRootInActiveWindow(),T.s(value),prefix);if(found!=null)return found;Thread.sleep(100);}while(SystemClock.elapsedRealtime()<end);
        throw new AssertionError("visible text missing: "+value);
    }
    void click(String value,boolean prefix)throws Exception{AccessibilityNodeInfo node=waitText(value,prefix);while(node!=null&&!node.isClickable())node=node.getParent();check(node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK),"click "+value);Thread.sleep(180);}
    void editor(JSONObject g)throws Exception{runOnMainSync(()->{try{Method m=FreezeBoardActivity.class.getDeclaredMethod("quotaEditor",JSONObject.class,String.class,boolean.class);m.setAccessible(true);m.invoke(board,g,null,true);}catch(Exception e){throw new RuntimeException(e);}});}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Context c=getTargetContext();String config=c.getSharedPreferences("afs_daily_quota",0).getString("config","");Bundle result=new Bundle();int status=0;
        try{
            getUiAutomation();
            board=startActivitySync(new Intent(c,FreezeBoardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));check(board!=null,"board opens");Thread.sleep(800);
            JSONObject draft=new JSONObject().put("id","background-audit-draft").put("name","Audit unsaved").put("minutes",120).put("apps",new JSONArray(Arrays.asList("com.android.chrome","dev.anilbeesetti.nextplayer")));
            editor(draft);click("挂后台也计时：未开启",true);click("Chrome",false);click("确定",false);
            check(waitText("挂后台也计时：1",true)!=null,"unsaved background toggle selected");
            click("选择应用",false);waitText("选择共享成员",false);
            getUiAutomation().performGlobalAction(1);Thread.sleep(600);
            check(waitText("挂后台也计时：1",true)!=null,"background draft survives canceled app picker");
            click("选择应用",false);waitText("选择共享成员",false);click("保存",true);Thread.sleep(600);
            check(waitText("挂后台也计时：1",true)!=null,"background draft survives accepted app picker");
            click("取消",false);
            check(config.equals(c.getSharedPreferences("afs_daily_quota",0).getString("config","")),"real quota config unchanged");
            status=-1;result.putString("stream","Background quota read-only UI: "+checks+" checks passed\n");
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));}
        finally{if(board!=null)runOnMainSync(()->board.finish());finish(status,result);}
    }
}
