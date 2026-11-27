package com.afs.guard;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;
import com.afs.guard.data.AfsDatabase;
import com.afs.guard.data.AfsRepositories;
import java.io.*;
import java.util.*;
import org.json.*;
final class LocalBackup {
    /**
     * Portable user data only. Credentials, device-owner state and sync caches stay
     * out of this archive so a backup cannot transfer access or bypass first-run rules.
     */
    private static final String[] PREFS={
        "afs_guard","afs_study_span","afs_memo_library","afs_daily_quota",
        "afs_todo","afs_journal","afs_rest","afs_alert","afs_learning_link","afs_daily_completion",
        "afs_taboo","afs_time_window","afs_day_boundary","afs_amt",
        "afs_tool_shelf","afs_focus","afs_future","afs_mind","afs_ui","afs_lang","afs_bg","afs_keygate",
        "afs_netblock","afs_net_window","afs_page","afs_keyword","afs_lock","afs_usage_quota",
        "afs_checkin","afs_lan_quota","afs_achievement","afs_memo_ai","afs_setup",
        "afs_page_scroll","afs_builtin_plugins","afs_screen_stats"
    };
    private static boolean oneOf(String value,String... allowed){return Arrays.asList(allowed).contains(value);}
    static boolean key(String p,String k){
        if(k==null||k.isEmpty())return false;
        if(p.equals("afs_screen_stats"))return oneOf(k,"archive","fun_custom","fun_packages");
        if(p.equals("afs_daily_quota"))return oneOf(k,"config","usage","config_before_migration","usage_before_migration");
        if(p.equals("afs_memo_library"))return k.equals("state");
        if(p.equals("afs_study_span"))return oneOf(k,"start","end","imported");
        if(p.equals("afs_guard"))return oneOf(k,"rules","freeze_slots","freeze_weekday_disabled_mask","freeze_weekday_pause_scopes","memos_v1","memos_trash_v1","toolbox_items","schedule_timezone","applied","last_rule");
        if(p.equals("afs_todo"))return oneOf(k,"todos_v1","defer_minutes");
        if(p.equals("afs_journal"))return k.equals("enabled")||k.matches("d:\\d{4}-\\d{2}-\\d{2}");
        if(p.equals("afs_rest"))return oneOf(k,"on","every","from","to","confirm","next_card");
        // 只备份冻结前提醒的用户设置；已弹过的去重键和待补弹内容属于运行状态。
        if(p.equals("afs_alert"))return oneOf(k,"freeze_warn_on","freeze_warn_minutes");
        if(p.equals("afs_learning_link"))return k.startsWith("memory:")||k.startsWith("weekly_done:")||oneOf(k,"quiz_enabled","quiz_target","bind_source_memory","bind_source_quiz","bind_target_gate","bind_target_quota","bind_target_network","bind_target_reward");
        if(p.equals("afs_daily_completion"))return k.equals("days");
        if(p.equals("afs_taboo"))return oneOf(k,"items","log","declined","declined_log","schedule_penalties","penalty_schedule_v1","legacy_hold_until");
        if(p.equals("afs_time_window"))return oneOf(k,"start","end","default_22_23");
        if(p.equals("afs_day_boundary"))return oneOf(k,"reset_hour","reset_minute","unified_0am","pending_hour","pending_minute","pending_at");
        if(p.equals("afs_amt"))return k.startsWith("amt_");
        if(p.equals("afs_focus"))return oneOf(k,"focus","brk");
        if(p.equals("afs_tool_shelf"))return oneOf(k,"home_order","box_order");
        if(p.equals("afs_future"))return k.equals("events");
        if(p.equals("afs_mind"))return oneOf(k,"day","minutes");
        if(p.equals("afs_ui"))return oneOf(k,"theme","appearance_v1","appearance_revision","font_family","font_color","freeze_library_hidden","reduce_motion","startup_animation","blur_level","card_opacity","style_id","effect_quality");
        if(p.equals("afs_lang"))return k.equals("mode");
        if(p.equals("afs_bg"))return k.equals("src");
        if(p.equals("afs_keygate"))return oneOf(k,"on","minutes","pkgs");
        if(p.equals("afs_netblock"))return oneOf(k,"on","pkgs","fallback_pkgs","fallback_mode","proxy_mode");
        if(p.equals("afs_net_window"))return oneOf(k,"start","end");
        if(p.equals("afs_page"))return oneOf(k,"on","rules","silent","builtin_seed","diag","hits","last_at","last_pkg","last_rule");
        if(p.equals("afs_keyword"))return oneOf(k,"on","words","targets","hits","last_at","last_word","last_pkg");
        if(p.equals("afs_lock"))return oneOf(k,"periodic","from","to","period","lock","app_on","app_limit","app_lock","app_targets","keys","screen_accum");
        if(p.equals("afs_usage_quota"))return k.equals("limits");
        if(p.equals("afs_checkin"))return k.equals("queue");
        if(p.equals("afs_lan_quota"))return oneOf(k,"enabled","host","port","group","groupName","minutes","source");
        if(p.equals("afs_achievement"))return k.equals("seen");
        if(p.equals("afs_memo_ai"))return oneOf(k,"endpoint","model");
        if(p.equals("afs_setup"))return k.equals("guide_v1_seen");
        if(p.equals("afs_page_scroll"))return k.startsWith("page:");
        if(p.equals("afs_builtin_plugins"))return k.startsWith("enabled.");
        return false;
    }
    static boolean mayRestore(Context c){return !Guard.isOwner(c)||Config.inGrace(c);}
    private static File pending(Context c){return new File(c.getFilesDir(),"local-restore-pending.zip");}
    private static File journal(Context c){return new File(c.getFilesDir(),"local-restore-rollback.zip");}
    private static JSONObject preferences(Context c)throws Exception{
        JSONObject all=new JSONObject();for(String name:PREFS){JSONObject values=new JSONObject();for(Map.Entry<String,?> e:c.getSharedPreferences(name,0).getAll().entrySet())if(key(name,e.getKey())){Object v=e.getValue();String t=v instanceof Boolean?"boolean":v instanceof Integer?"int":v instanceof Long?"long":v instanceof Float?"float":v instanceof Set?"set":"string";if(v instanceof Set)v=new JSONArray((Set<?>)v);values.put(e.getKey(),new JSONObject().put("type",t).put("value",v));}all.put(name,values);}return all;
    }
    private static void collect(File root,File f,Map<String,byte[]> map)throws Exception{
        if(!new File(f.getParentFile().getCanonicalFile(),f.getName()).getPath().equals(f.getCanonicalPath()))throw new IOException(T.s("不支持符号链接"));
        if(f.isDirectory()){File[] list=f.listFiles();if(list!=null)for(File child:list)collect(root,child,map);return;}
        String path="files/"+f.getAbsolutePath().substring(root.getAbsolutePath().length()+1).replace(File.separatorChar,'/');if(!LocalArchive.allowed(path))return;
        long used=0;for(byte[] b:map.values())used+=b.length;try(InputStream in=new AtomicFile(f).openRead()){map.put(path,LocalArchive.read(in,(int)Math.min(LocalArchive.ENTRY,LocalArchive.LIMIT-used)));}
        long size=0;for(byte[] b:map.values())size+=b.length;if(size>LocalArchive.LIMIT)throw new IOException(T.s("完整备份超过当前设备的安全大小上限，请使用各模块的备份工具"));
    }
    static Map<String,byte[]> snapshot(Context c)throws Exception{Map<String,byte[]> map=new TreeMap<>();map.put("preferences.json",preferences(c).toString().getBytes("UTF-8"));File root=c.getFilesDir();for(String name:new String[]{"study.json","memory-v1.json","quiz-v1.json","cards-v1.json","backdrop.jpg"}){File f=new File(root,name);if(f.exists()||new File(f+".bak").exists())collect(root,f,map);}for(String name:new String[]{"memory-media","memo-attachments"}){File f=new File(root,name);if(f.isDirectory())collect(root,f,map);}File database=c.getDatabasePath(AfsDatabase.NAME);if(database.isFile())map.put("database/"+AfsDatabase.NAME,AfsDatabase.get(c).snapshotBytes(c,LocalArchive.ENTRY));return map;}
    static void export(Context c,OutputStream out)throws Exception{Map<String,byte[]> map=snapshot(c);validate(map);LocalArchive.write(map,out);}
    private static void validate(Map<String,byte[]> data)throws Exception{
        JSONObject p=new JSONObject(new String(data.get("preferences.json"),"UTF-8"));Iterator<String> names=p.keys();while(names.hasNext()){String n=names.next();if(!Arrays.asList(PREFS).contains(n))throw new IOException(T.s("不允许的偏好设置"));JSONObject v=p.getJSONObject(n);Iterator<String> keys=v.keys();while(keys.hasNext()){String k=keys.next();if(!key(n,k))throw new IOException(T.s("备份包含受保护设置"));JSONObject x=v.getJSONObject(k);String t=x.getString("type");if(!Arrays.asList("string","boolean","int","long","float","set").contains(t))throw new IOException(T.s("未知设置类型"));Object value=x.get("value");if(t.equals("string")&&!(value instanceof String)||t.equals("boolean")&&!(value instanceof Boolean)||Arrays.asList("int","long","float").contains(t)&&!(value instanceof Number)||t.equals("set")&&!(value instanceof JSONArray))throw new IOException(T.s("设置类型不符"));}}
        JSONObject statsPrefs=p.optJSONObject("afs_screen_stats");
        if(statsPrefs!=null&&statsPrefs.has("archive"))new ScreenUsageData(statsPrefs.getJSONObject("archive").getString("value"));
        for(Map.Entry<String,byte[]> e:data.entrySet()){String n=e.getKey();if(n.equals("database/"+AfsDatabase.NAME)){validateDatabase(e.getValue());continue;}if(n.endsWith(".json")&&!n.equals("preferences.json")){String s=new String(e.getValue(),"UTF-8");if(n.equals("files/memory-v1.json"))new MemoryData(s);else if(n.equals("files/quiz-v1.json"))new QuizData(s);else if(n.equals("files/cards-v1.json"))new CardData(s);else if(n.equals("files/study.json")){JSONObject study=new JSONObject(s);study.getJSONArray("goals");study.getJSONArray("recs");}else new JSONObject(s);}}
    }
    private static void validateDatabase(byte[] bytes)throws Exception{
        if(bytes.length<16||!"SQLite format 3\u0000".equals(new String(bytes,0,16,"US-ASCII")))throw new IOException(T.s("数据库备份格式无效"));
        File temp=File.createTempFile("afs-backup-check-",".db");
        try{try(OutputStream out=new FileOutputStream(temp)){out.write(bytes);}SQLiteDatabase db=SQLiteDatabase.openDatabase(temp.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);try{Cursor check=db.rawQuery("PRAGMA quick_check",null);try{if(!check.moveToFirst()||!"ok".equalsIgnoreCase(check.getString(0)))throw new IOException(T.s("数据库备份完整性校验失败"));}finally{check.close();}}finally{db.close();}}finally{if(!temp.delete())temp.deleteOnExit();}
    }
    static String inspect(InputStream in)throws Exception{Map<String,byte[]> data=LocalArchive.decode(in);validate(data);long bytes=0;for(byte[] b:data.values())bytes+=b.length;return T.s("文件数：")+(data.size()-1)+T.s("\n数据大小：")+(bytes/1024)+T.s(" KB\n包含本机学习数据、附件与所选设置；不包含云端凭据。");}
    private static void atomic(File file,byte[] bytes)throws Exception{AtomicFile a=new AtomicFile(file);FileOutputStream out=null;try{File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException(T.s("无法创建目录"));out=a.startWrite();out.write(bytes);a.finishWrite(out);}catch(Exception e){if(out!=null)a.failWrite(out);throw e;}}
    static void queue(Context c,InputStream in)throws Exception{if(!mayRestore(c))throw new IOException(T.s("完整恢复仅限未启用设备管理或首次配置宽限期，以防用旧备份绕过冻结"));byte[] bytes=LocalArchive.read(in,LocalArchive.LIMIT+4*1024*1024);Map<String,byte[]> data=LocalArchive.decode(new ByteArrayInputStream(bytes));validate(data);atomic(pending(c),bytes);}
    private static void paths(File root,File f,Set<String> names)throws Exception{
        if(!new File(f.getParentFile().getCanonicalFile(),f.getName()).getPath().equals(f.getCanonicalPath()))throw new IOException(T.s("不支持符号链接"));
        if(f.isDirectory()){File[] list=f.listFiles();if(list!=null)for(File child:list)paths(root,child,names);return;}
        String p="files/"+f.getAbsolutePath().substring(root.getAbsolutePath().length()+1).replace(File.separatorChar,'/');
        if(LocalArchive.allowed(p))names.add(p);else if(p.endsWith(".bak")&&LocalArchive.allowed(p.substring(0,p.length()-4)))names.add(p.substring(0,p.length()-4));
    }
    static void apply(Context c,Map<String,byte[]> data)throws Exception{
        validate(data);Set<String> names=new HashSet<>();File root=c.getFilesDir();File[] children=root.listFiles();if(children!=null)for(File f:children)if(LocalArchive.allowed("files/"+f.getName())||f.getName().endsWith(".bak")||f.getName().equals("memory-media")||f.getName().equals("memo-attachments"))paths(root,f,names);names.addAll(data.keySet());
        AfsRepositories.closeForRestore();
        File database=c.getDatabasePath(AfsDatabase.NAME);
        File databaseWal=new File(database.getPath()+"-wal"),databaseShm=new File(database.getPath()+"-shm");
        byte[] databaseBytes=data.get("database/"+AfsDatabase.NAME);
        if(databaseBytes!=null){atomic(database,databaseBytes);if(databaseWal.exists()&&!databaseWal.delete())throw new IOException(T.s("无法替换旧数据库日志"));if(databaseShm.exists()&&!databaseShm.delete())throw new IOException(T.s("无法替换旧数据库共享内存"));}
        else if(database.exists()){if(!database.delete())throw new IOException(T.s("无法替换旧数据库"));if(databaseWal.exists()&&!databaseWal.delete())throw new IOException(T.s("无法清理旧数据库日志"));if(databaseShm.exists()&&!databaseShm.delete())throw new IOException(T.s("无法清理旧数据库共享内存"));}
        for(String name:names)if(name.startsWith("files/")){File f=new File(c.getFilesDir(),name.substring(6));if(!f.getCanonicalPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator))throw new IOException(T.s("非法恢复路径"));if(data.containsKey(name))atomic(f,data.get(name));else{AtomicFile a=new AtomicFile(f);a.delete();if(f.exists())throw new IOException(T.s("无法替换旧文件"));}}
        JSONObject prefs=new JSONObject(new String(data.get("preferences.json"),"UTF-8"));
        for(String name:PREFS){
            JSONObject values=prefs.optJSONObject(name);
            // A v1 archive predates some current preference groups. Keep those newer
            // local groups instead of treating "not present in the old archive" as empty.
            if(values==null)continue;
            SharedPreferences current=c.getSharedPreferences(name,0);SharedPreferences.Editor edit=current.edit();
            for(String k:current.getAll().keySet())if(key(name,k))edit.remove(k);
            Iterator<String> keys=values.keys();
            while(keys.hasNext()){
                String k=keys.next();JSONObject x=values.getJSONObject(k);
                switch(x.getString("type")){case "string":edit.putString(k,x.getString("value"));break;case "boolean":edit.putBoolean(k,x.getBoolean("value"));break;case "int":edit.putInt(k,x.getInt("value"));break;case "long":edit.putLong(k,x.getLong("value"));break;case "float":edit.putFloat(k,(float)x.getDouble("value"));break;case "set":Set<String> set=new HashSet<>();JSONArray a=x.getJSONArray("value");for(int i=0;i<a.length();i++)set.add(a.getString(i));edit.putStringSet(k,set);break;}
            }
            if(!edit.commit())throw new IOException(T.s("设置写入失败"));
        }
    }
    private static File marker(Context c){return new File(c.getFilesDir(),"local-restore-applying");}
    /** Runs before stores/services are initialized in a fresh process. */
    static void startup(Context c){try{
        if(journal(c).exists()){
            if(marker(c).exists()){
                try(InputStream in=new FileInputStream(journal(c))){apply(c,LocalArchive.decode(in));}
                if(!journal(c).delete())throw new IOException(T.s("无法结束回滚"));marker(c).delete();pending(c).delete();
                c.getSharedPreferences("afs_data_status",0).edit().putString("result","上次恢复中断，已回滚原数据").commit();return;
            }
            if(!journal(c).delete())throw new IOException(T.s("无法清理未开始的恢复"));
        }else if(marker(c).exists()){marker(c).delete();pending(c).delete();}
        if(!pending(c).exists())return;
        if(!mayRestore(c)){pending(c).delete();throw new IOException(T.s("当前冻结状态不允许完整恢复"));}
        if(!c.getSharedPreferences("afs_data_status",0).edit().putBoolean("pause_legacy_sync",true).commit())throw new IOException(T.s("无法暂停旧版同步，恢复未开始"));
        ByteArrayOutputStream before=new ByteArrayOutputStream();export(c,before);atomic(journal(c),before.toByteArray());before=null;
        atomic(marker(c),new byte[]{1});
        try(InputStream in=new FileInputStream(pending(c))){apply(c,LocalArchive.decode(in));}
        if(!c.getSharedPreferences("afs_sync",0).edit().putString("cache_epoch",java.util.UUID.randomUUID().toString()).commit())throw new IOException(T.s("无法切换同步缓存"));
        if(!pending(c).delete()||!journal(c).delete())throw new IOException(T.s("无法提交恢复"));marker(c).delete();
        c.getSharedPreferences("afs_data_status",0).edit().putBoolean("pause_legacy_sync",true).putString("result","本地备份已完整恢复；旧版 Gist 同步已暂停，请重新验证后启用").commit();
        try{MemoReminder.reschedule(c);}catch(RuntimeException reminderFailure){android.util.Log.w("AFSBackup","Restored notes; reminders need permission",reminderFailure);}
        try{TodoReminder.reconcile(c);}catch(RuntimeException reminderFailure){android.util.Log.w("AFSBackup","Restored to-dos; reminders need permission",reminderFailure);}
    }catch(Exception e){try{if(journal(c).exists()&&marker(c).exists()){try(InputStream in=new FileInputStream(journal(c))){apply(c,LocalArchive.decode(in));}}journal(c).delete();marker(c).delete();pending(c).delete();}catch(Exception failure){throw new IllegalStateException(T.s("恢复回滚未完成，原始回滚文件已保留"),failure);}c.getSharedPreferences("afs_data_status",0).edit().putString("result","恢复未完成，已保留原数据："+e.getMessage()).commit();}}
}
