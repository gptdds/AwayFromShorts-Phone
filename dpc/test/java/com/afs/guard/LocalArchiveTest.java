package com.afs.guard;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;
public final class LocalArchiveTest {
    private static int checks;
    private static void check(boolean yes){checks++;if(!yes)throw new AssertionError();}
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Map<String,byte[]> data=new TreeMap<>();data.put("preferences.json","{}".getBytes("UTF-8"));data.put("files/study.json","{\"goals\":[],\"recs\":[]}".getBytes("UTF-8"));
        ByteArrayOutputStream out=new ByteArrayOutputStream();LocalArchive.write(data,out);
        Map<String,byte[]> decoded=LocalArchive.decode(new ByteArrayInputStream(out.toByteArray()));check(Arrays.equals(data.get("files/study.json"),decoded.get("files/study.json")));
        for(String bad:new String[]{"../outside","files/../study.json","files/token.local","files/afs_sync.xml","files/memory-media/../secret"})check(!LocalArchive.allowed(bad));
        ByteArrayOutputStream changed=new ByteArrayOutputStream();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));ZipOutputStream z=new ZipOutputStream(changed)){ZipEntry e;while((e=in.getNextEntry())!=null){byte[] bytes=LocalArchive.read(in,LocalArchive.ENTRY);z.putNextEntry(new ZipEntry(e.getName()));z.write(e.getName().equals("files/study.json")?"{}".getBytes("UTF-8"):bytes);z.closeEntry();}}
        boolean rejected=false;try{LocalArchive.decode(new ByteArrayInputStream(changed.toByteArray()));}catch(Exception expected){rejected=true;}check(rejected);
        for(String p:new String[]{"webdav","github","git"}){JSONObject cfg=new JSONObject().put("target",p.equals("github")?"owner/private-repo":"https://example.com/repository").put("branch","main").put("path","backups/afs");CloudProfiles.validate(p,cfg);checks++;}
        for(String url:new String[]{"file:///data/local/secret","https://user:password@example.com/dav","http://example.com/dav"}){rejected=false;try{CloudProfiles.validate("webdav",new JSONObject().put("target",url));}catch(Exception e){rejected=true;}check(rejected);}
        check(!LocalBackup.key("afs_guard","first_seen"));check(!LocalBackup.key("afs_guard","armed"));check(!LocalBackup.key("afs_sync","token"));check(LocalBackup.key("afs_guard","memos_v1"));
        check(LocalBackup.key("afs_todo","todos_v1"),"to-dos included");
        check(LocalBackup.key("afs_journal","d:2026-11-10"),"dated journal included");
        check(LocalBackup.key("afs_rest","every"),"reminder settings included");
        check(LocalBackup.key("afs_alert","freeze_warn_on"),"freeze warning setting included");
        check(LocalBackup.key("afs_alert","freeze_warn_minutes"),"freeze warning lead time included");
        check(!LocalBackup.key("afs_alert","pending_at"),"transient alert state excluded");
        check(!LocalBackup.key("afs_alert","warned_day"),"transient alert dedupe excluded");
        check(LocalBackup.key("afs_learning_link","memory:deck"),"learning linkage included");
        check(LocalBackup.key("afs_taboo","log"),"taboo history included");
        check(!LocalBackup.key("afs_taboo","hold_until"),"transient taboo hold stays out");
        check(!LocalBackup.key("afs_checkin_penalty","armed_date"),"transient check-in penalty stays out");
        check(!LocalBackup.key("afs_focus","running"),"running timer state stays out");
        check(!LocalBackup.key("afs_unlock","secret"),"unlock secret excluded");
        check(!LocalBackup.key("afs_cloud_profiles","github"),"cloud credentials excluded");
        check(LocalBackup.key("afs_guard","schedule_timezone"),"pinned schedule timezone included");
        check(LocalBackup.key("afs_guard","applied"),"last applied rule set included");
        check(LocalBackup.key("afs_taboo","declined"),"declined taboo state included");
        check(LocalBackup.key("afs_taboo","schedule_penalties"),"taboo penalty schedule included");
        check(!LocalBackup.key("afs_taboo","hold_until"),"live penalty deadline stays out");
        check(LocalBackup.key("afs_netblock","fallback_mode"),"network fallback preference included");
        check(LocalBackup.key("afs_page","rules"),"page rules included");
        check(LocalBackup.key("afs_page","builtin_seed"),"page rule migration marker included");
        check(LocalBackup.key("afs_lan_quota","host"),"LAN quota host included");
        check(LocalBackup.key("afs_lan_quota","group"),"LAN quota group included");
        check(!LocalBackup.key("afs_lan_quota","token"),"LAN quota token excluded");
        check(LocalBackup.key("afs_achievement","seen"),"achievement baseline included");
        check(LocalBackup.key("afs_memo_ai","endpoint"),"AI endpoint included");
        check(LocalBackup.key("afs_memo_ai","model"),"AI model included");
        check(!LocalBackup.key("afs_memo_ai","key"),"AI encrypted key excluded");
        check(!LocalBackup.key("afs_memo_ai","iv"),"AI encryption IV excluded");
        check(LocalBackup.key("afs_setup","guide_v1_seen"),"setup guide state included");
        System.out.println("Local archive: "+checks+" checks passed");
    }
}
