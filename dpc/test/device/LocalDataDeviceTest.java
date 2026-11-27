package com.afs.guard;
import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;
public final class LocalDataDeviceTest extends Instrumentation {
    private int checks;private Class<?> backup,cloud;
    private void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
    private Object call(Class<?> type,String method,Class<?>[] types,Object...args)throws Exception{Method m=type.getDeclaredMethod(method,types);m.setAccessible(true);try{return m.invoke(null,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
    private void write(File f,byte[] b)throws Exception{try(OutputStream out=new FileOutputStream(f)){out.write(b);}}
    private byte[] read(File f)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}return out.toByteArray();}
    private byte[] export(Context c)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();call(backup,"export",new Class<?>[]{Context.class,OutputStream.class},c,out);return out.toByteArray();}
    public void onCreate(Bundle b){super.onCreate(b);start();}
    public void onStart(){Bundle result=new Bundle();try{
        Context base=getTargetContext();String prefix="data_qa_"+System.nanoTime()+"_";Context c=new ContextWrapper(base){public Context getApplicationContext(){return this;}public String getPackageName(){return "com.afs.guard.fixture";}public File getFilesDir(){File f=new File(base.getCacheDir(),prefix);f.mkdirs();return f;}public SharedPreferences getSharedPreferences(String n,int mode){return base.getSharedPreferences(prefix+n,mode);}};
        backup=Class.forName("com.afs.guard.LocalBackup",true,base.getClassLoader());cloud=Class.forName("com.afs.guard.CloudProfiles",true,base.getClassLoader());
        File study=new File(c.getFilesDir(),"study.json");byte[] original="{\"goals\":[],\"recs\":[],\"marker\":\"original\"}".getBytes("UTF-8");write(study,original);
        File media=new File(c.getFilesDir(),"memory-media");media.mkdir();File image=new File(media,"fixture.png");byte[] picture=new byte[]{1,2,3,4};write(image,picture);
        File attachments=new File(c.getFilesDir(),"memo-attachments/12345678-1234-1234-1234-123456789abc");attachments.mkdirs();File attachment=new File(attachments,"data");write(attachment,new byte[]{8,7,6});write(new File(attachments,"meta.json"),"{}".getBytes("UTF-8"));
        SharedPreferences guard=c.getSharedPreferences("afs_guard",0);guard.edit().putString("memos_v1","[]").putString("rules","{}").putLong("first_seen",123).commit();
        SharedPreferences todo=c.getSharedPreferences("afs_todo",0);todo.edit().putString("todos_v1","[{\"id\":\"fixture\"}]").commit();
        SharedPreferences journal=c.getSharedPreferences("afs_journal",0);journal.edit().putBoolean("enabled",true).putString("d:2026-11-10","completed journal entry").commit();
        SharedPreferences learning=c.getSharedPreferences("afs_learning_link",0);learning.edit().putBoolean("quiz_enabled",true).putInt("quiz_target",12).commit();
        SharedPreferences ui=c.getSharedPreferences("afs_ui",0);ui.edit().putString("theme","night").putString("appearance_v1","pastel").putString("excluded_ui_cache","keep").commit();
        SharedPreferences taboo=c.getSharedPreferences("afs_taboo",0);taboo.edit().putString("items","[\"fixture taboo\"]").putString("excluded_taboo_cache","keep").commit();
        SharedPreferences lan=c.getSharedPreferences("afs_lan_quota",0);lan.edit().putString("host","192.168.1.2").putInt("port",8738).putString("group","shared").putString("token","lan-fixture-secret").commit();
        SharedPreferences ai=c.getSharedPreferences("afs_memo_ai",0);ai.edit().putString("endpoint","https://example.com/api").putString("model","fixture-model").putString("key","ai-fixture-secret").commit();
        SharedPreferences setup=c.getSharedPreferences("afs_setup",0);setup.edit().putBoolean("guide_v1_seen",true).commit();
        JSONObject profile=new JSONObject().put("target","https://example.com/dav").put("path","backups");call(cloud,"save",new Class<?>[]{Context.class,String.class,JSONObject.class,String.class},c,"webdav",profile,"cloud-fixture-secret");
        String stored=c.getSharedPreferences("afs_cloud_profiles",0).getString("webdav","");check(!stored.contains("cloud-fixture-secret")&&stored.contains("credential"),"credentials encrypted at rest");
        byte[] archive=export(c);String summary=(String)call(backup,"inspect",new Class<?>[]{InputStream.class},new ByteArrayInputStream(archive));check(summary.contains("KB"),"backup preview");
        write(image,new byte[]{9});write(attachment,new byte[]{0});
        write(study,"{\"goals\":[],\"recs\":[]}".getBytes("UTF-8"));guard.edit().putLong("first_seen",999).commit();
        todo.edit().putString("todos_v1","changed").commit();journal.edit().putBoolean("enabled",false).putString("d:2026-11-10","changed").commit();
        learning.edit().putBoolean("quiz_enabled",false).putInt("quiz_target",99).commit();ui.edit().putString("theme","light").putString("appearance_v1","changed").commit();
        taboo.edit().putString("items","changed").commit();
        lan.edit().putString("host","changed").putString("token","lan-changed-secret").commit();
        ai.edit().putString("endpoint","changed").putString("key","ai-changed-secret").commit();
        setup.edit().putBoolean("guide_v1_seen",false).commit();
        call(backup,"queue",new Class<?>[]{Context.class,InputStream.class},c,new ByteArrayInputStream(archive));call(backup,"startup",new Class<?>[]{Context.class},c);
        check(Arrays.equals(original,read(study)),"study data round trip");check(Arrays.equals(picture,read(image)),"memory image round trip");check(Arrays.equals(new byte[]{8,7,6},read(attachment)),"memo attachment round trip");check(guard.getLong("first_seen",0)==999,"initial grace timestamp not restored");check(stored.equals(c.getSharedPreferences("afs_cloud_profiles",0).getString("webdav","")),"cloud credential not replaced");check(c.getSharedPreferences("afs_data_status",0).getBoolean("pause_legacy_sync",false),"restore pauses legacy network sync");
        check(todo.getString("todos_v1","").equals("[{\"id\":\"fixture\"}]"),"todo cards round trip");
        check(journal.getBoolean("enabled",false)&&journal.getString("d:2026-11-10","").equals("completed journal entry"),"journal round trip");
        check(learning.getBoolean("quiz_enabled",false)&&learning.getInt("quiz_target",0)==12,"learning links round trip");
        check(ui.getString("theme","").equals("night")&&ui.getString("appearance_v1","").equals("pastel"),"ui preferences round trip");
        check(ui.getString("excluded_ui_cache","").equals("keep"),"excluded ui key preserved");
        check(taboo.getString("items","").equals("[\"fixture taboo\"]"),"taboo list round trip");
        check(taboo.getString("excluded_taboo_cache","").equals("keep"),"excluded taboo key preserved");
        check(lan.getString("host","").equals("192.168.1.2")&&lan.getString("group","").equals("shared"),"LAN quota configuration round trip");
        check(lan.getString("token","").equals("lan-changed-secret"),"LAN quota token preserved locally");
        check(ai.getString("endpoint","").equals("https://example.com/api")&&ai.getString("model","").equals("fixture-model"),"AI settings round trip");
        check(ai.getString("key","").equals("ai-changed-secret"),"AI encrypted key preserved locally");
        check(setup.getBoolean("guide_v1_seen",false),"setup guide state round trip");
        byte[] rollback=export(c);write(new File(c.getFilesDir(),"local-restore-rollback.zip"),rollback);write(new File(c.getFilesDir(),"local-restore-applying"),new byte[]{1});write(study,"{\"goals\":[],\"recs\":[]}".getBytes("UTF-8"));call(backup,"startup",new Class<?>[]{Context.class},c);check(Arrays.equals(original,read(study)),"interrupted restore rolled back");
        write(new File(c.getFilesDir(),"local-restore-rollback.zip"),new byte[]{1,2,3});call(backup,"startup",new Class<?>[]{Context.class},c);check(Arrays.equals(original,read(study)),"incomplete journal before mutation safely discarded");
        check(!new File(c.getFilesDir(),"local-restore-rollback.zip").exists(),"journal cleaned after recovery");
        result.putString("stream","Local data device: "+checks+" checks passed\n");finish(-1,result);
    }catch(Throwable e){result.putString("stream","FAILED "+android.util.Log.getStackTraceString(e));finish(1,result);}}
}
