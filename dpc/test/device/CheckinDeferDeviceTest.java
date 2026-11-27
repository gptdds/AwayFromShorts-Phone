package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Disposable emulator only; exercises actual card handler, dialog, persistence and alarms. */
public final class CheckinDeferDeviceTest extends Instrumentation {
    Activity activity;int count;
    void check(boolean ok,String message){if(!ok)throw new AssertionError(message);count++;}
    Object get(String name)throws Exception{Field f=CheckinActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void call(String name,Class<?>[] types,Object... values)throws Exception{Method m=CheckinActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,values);}
    void ui(Runnable task){Throwable[] failure={null};runOnMainSync(()->{try{task.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);}
    EditText input(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=input(((ViewGroup)v).getChildAt(i));if(e!=null)return e;}return null;}
    View custom(View v){if(T.s("自定义时间").contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=custom(((ViewGroup)v).getChildAt(i));if(found!=null)return found;}return null;}
    void select(String id)throws Exception{
        List<?> entries=(List<?>)get("cards");int selected=-1;
        for(int i=0;i<entries.size();i++){Field f=entries.get(i).getClass().getDeclaredField("todo");f.setAccessible(true);TodoStore.Todo t=(TodoStore.Todo)f.get(entries.get(i));if(t!=null&&id.equals(t.id))selected=i;}
        check(selected>=0,"due todo in real card deck");Field f=CheckinActivity.class.getDeclaredField("selected");f.setAccessible(true);f.setInt(activity,selected);
        call("render",new Class[]{boolean.class},false);
    }
    @Override public void onCreate(Bundle args){start();}
    @Override public void onStart(){Bundle result=new Bundle();Context c=getTargetContext();String id=null;
        try{
            check(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk"),"disposable emulator only");
            TodoStore.Todo added=TodoStore.add(c,"QA custom delay","temporary integration check",0);id=added.id;final String key=id;
            activity=startActivitySync(new Intent(c,CheckinActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(1200);
            ui(()->{try{select(key);call("onUp",new Class[]{int.class,float.class,float.class},1,0f,1500f);}catch(Exception e){throw new RuntimeException(e);}});
            // The downward release must open the real dialog before any data changes.
            ui(()->{try{AlertDialog d=(AlertDialog)get("deferDialog");check(d!=null&&d.isShowing(),"down gesture opens delay dialog");d.getButton(-2).performClick();check(TodoStore.find(TodoStore.all(c),key).remindAt==0,"cancel does not reschedule");}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
            ui(()->{try{Object entry=((List<?>)get("cards")).get((Integer)get("selected"));call("deferTodo",new Class[]{entry.getClass()},entry);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
            ui(()->{try{AlertDialog d=(AlertDialog)get("deferDialog");View custom=custom(d.getWindow().getDecorView());check(custom!=null,"custom time shortcut exists");custom.performClick();Dialog picker=(Dialog)get("deferTimePicker");check(picker instanceof DatePickerDialog&&picker.isShowing(),"shortcut opens native calendar");picker.cancel();check(TodoStore.find(TodoStore.all(c),key).remindAt==0,"cancel calendar leaves todo unchanged");}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
            ui(()->{try{AlertDialog d=(AlertDialog)get("deferDialog");EditText e=input(d.getWindow().getDecorView());e.setText("0");d.getButton(-1).performClick();check(d.isShowing()&&e.getError()!=null,"invalid delay keeps dialog open");e.setText("7");d.getButton(-1).performClick();}catch(Exception e){throw new RuntimeException(e);}});
            SystemClock.sleep(900);
            TodoStore.Todo saved=TodoStore.find(TodoStore.all(c),key);check(saved.pending(),"pending status retained");check(saved.remindAt>System.currentTimeMillis()+400000L,"seven minute custom delay saved");check(!TodoStore.isDue(saved,System.currentTimeMillis()),"deferred card excluded until due");check(c.getSharedPreferences("afs_todo",0).getInt("defer_minutes",0)==7,"last duration remembered");
            ui(()->{try{TodoStore.update(c,key,added.title,added.detail,0);call("refreshAsync",new Class[]{});select(key);call("onUp",new Class[]{int.class,float.class,float.class},1,0f,1500f);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
            ui(()->{try{AlertDialog d=(AlertDialog)get("deferDialog");custom(d.getWindow().getDecorView()).performClick();DatePickerDialog date=(DatePickerDialog)get("deferTimePicker");Calendar tomorrow=Calendar.getInstance();tomorrow.add(Calendar.DAY_OF_YEAR,1);date.updateDate(tomorrow.get(Calendar.YEAR),tomorrow.get(Calendar.MONTH),tomorrow.get(Calendar.DAY_OF_MONTH));date.getButton(-1).performClick();}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
            ui(()->{try{Dialog time=(Dialog)get("deferTimePicker");check(time instanceof TimePickerDialog&&time.isShowing(),"date selection opens native clock");((TimePickerDialog)time).getButton(-1).performClick();}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
            ui(()->{try{AlertDialog d=(AlertDialog)get("deferDialog");check(!input(d.getWindow().getDecorView()).isEnabled(),"selected clock time overrides relative duration");check(TodoStore.find(TodoStore.all(c),key).remindAt==0,"picker selection waits for final confirmation");d.getButton(-1).performClick();}catch(Exception e){throw new RuntimeException(e);}});SystemClock.sleep(900);
            TodoStore.Todo clockSaved=TodoStore.find(TodoStore.all(c),key);check(clockSaved.pending()&&clockSaved.remindAt>System.currentTimeMillis()+20*3600000L,"chosen next-day clock persisted");check(clockSaved.remindAt%60000L==0,"chosen clock stored at exact minute");
            result.putString("stream","CHECKIN DEFER: "+count+" checks passed");finish(-1,result);
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));finish(0,result);}
        finally{if(id!=null){TodoReminder.cancel(c,id);TodoStore.remove(c,id);}}
    }
}
