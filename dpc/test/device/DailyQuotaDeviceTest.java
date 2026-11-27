package com.afs.guard;
import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.lang.reflect.*;
import java.io.*;
import org.json.*;

/** Never run business fixtures on a user's phone. Only disposable APKs are suspended. */
public final class DailyQuotaDeviceTest extends Instrumentation {
    private int checks;private Activity screen;private boolean coreOnly;
    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private void shell(String command)throws Exception{try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))){byte[] b=new byte[1024];while(in.read(b)>=0){}}}
    private void foregroundApp(Context c,String pkg,long duration)throws Exception {
        getUiAutomation();c.startActivity(new Intent().setClassName(pkg,"com.afs.quota.fixture.QuotaFixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        boolean shown=false;long until=SystemClock.elapsedRealtime()+8000;while(!shown&&SystemClock.elapsedRealtime()<until){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null){shown=pkg.contentEquals(root.getPackageName());root.recycle();}if(!shown)Thread.sleep(50);}check(shown,"actual quota fixture enters foreground "+pkg+" reason="+Guard.blockReasonNow(c,pkg)+" usage="+DailyQuotaStore.snapshot(c).usage.toString());Thread.sleep(duration);
    }
    private void usagePermission(Context c,String mode,boolean expected)throws Exception {shell("appops set com.afs.guard GET_USAGE_STATS "+mode);long until=SystemClock.elapsedRealtime()+5000;while(AppUsageQuota.permission(c)!=expected&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);check(AppUsageQuota.permission(c)==expected,"actual usage permission observed: "+mode);}
    private void force(Context c)throws Exception{Method m=DailyQuotaStore.class.getDeclaredMethod("refresh",Context.class,boolean.class);m.setAccessible(true);m.invoke(null,c,true);}
    private JSONObject group(String id,int limit,String... apps)throws Exception{return new JSONObject().put("id",id).put("name",id.equals("shared")?"测试共享格子":id).put("minutes",limit).put("apps",new JSONArray(Arrays.asList(apps)));}
    private JSONObject config(JSONObject... groups)throws Exception{return new JSONObject().put("enabled",true).put("groups",new JSONArray(Arrays.asList(groups)));}
    private void fixture(SharedPreferences prefs,JSONObject config,JSONObject usage){check(prefs.edit().putBoolean("recount_full_day_v1",true).putString("config",config.toString()).putString("usage",usage.toString()).commit(),"fixture saved");}
    private TextView label(View v,String text){if(v instanceof TextView&&T.s(text).contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=label(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    @Override public void onCreate(Bundle args){super.onCreate(args);coreOnly=args!=null&&Boolean.parseBoolean(args.getString("core_only","false"));start();}
    @Override public void onStart(){Context c=getTargetContext();SharedPreferences p=c.getSharedPreferences(DailyQuotaStore.SP,0),r=c.getSharedPreferences("afs_guard",0);Map<String,?> backup=new HashMap<>(p.getAll()),rules=new HashMap<>(r.getAll());SharedPreferences visual=c.getSharedPreferences(Ui.SP,0);Map<String,?> visualBackup=new HashMap<>(visual.getAll());SharedPreferences adjust=c.getSharedPreferences(TimeWindow.SP,0);Map<String,?> adjustBackup=new HashMap<>(adjust.getAll());boolean permission=AppUsageQuota.permission(c);Bundle result=new Bundle();int code=-1;String a="com.afs.quota.one",b="com.afs.quota.two";
        try{
            check(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone"),"emulator required");check(Guard.isOwner(c),"actual device owner available");
            usagePermission(c,"allow",true);long now=System.currentTimeMillis();JSONObject ga=group("appA",1,a),gb=group("appB",1,b),cfg=config(ga,gb);fixture(p,cfg,DailyQuotaData.fresh(now));
            ActivityMonitor monitor=addMonitor(DailyQuotaActivity.class.getName(),null,false);runOnMainSync(()->c.startActivity(new Intent(c,DailyQuotaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));screen=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);check(screen!=null,"daily quota screen opens");Thread.sleep(400);check(label(screen.getWindow().getDecorView(),"使用限额")!=null,"unified page heading");
            if(DailyQuotaStore.editable(c)){DailyQuotaStore.save(c,cfg);check(true,"store accepts writes inside the nightly window");}else{DailyQuotaStore.save(c,cfg);check(true,"no-op quota save remains allowed outside the nightly window");try{DailyQuotaStore.save(c,config(ga));throw new AssertionError("group removal bypassed nightly lock");}catch(IllegalStateException expected){check(true,"store rejects group removal outside the nightly window");}}
            foregroundApp(c,a,1800);foregroundApp(c,b,1800);runOnMainSync(()->screen.startActivity(new Intent(screen,DailyQuotaActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));Thread.sleep(300);force(c);
            DailyQuotaData counted=DailyQuotaStore.snapshot(c);check(counted.used(ga)>=1000&&counted.used(gb)>=1000,"actual foreground events count each app");long usedA=counted.used(ga),usedB=counted.used(gb);check(usedA<15000&&usedB<15000,"usage begins after fixture enable time");
            JSONObject shared=group("shared",1,a,b);fixture(p,config(shared),counted.usage);check(DailyQuotaStore.snapshot(c).used(shared)==usedA+usedB,"merge preserves per-app foreground ledger");
            JSONObject ledger=MemoryData.copy(counted.usage);ledger.optJSONObject("targets").put(a,60000L).put(b,1000L);ledger.put("lastAt",System.currentTimeMillis());fixture(p,config(shared),ledger);Guard.apply(c);DevicePolicyManager d=Guard.dpm(c);ComponentName admin=Guard.admin(c);
            check(Guard.blockReasonNow(c,a)==5&&Guard.blockReasonNow(c,b)==5,"shared exhaustion returns same freeze cause");check(d.isPackageSuspended(admin,a)&&d.isPackageSuspended(admin,b),"both real fixture packages suspended");
            fixture(p,cfg,DailyQuotaStore.snapshot(c).usage);Guard.apply(c);check(d.isPackageSuspended(admin,a)&&!d.isPackageSuspended(admin,b),"split freezes only exhausted app and releases other app");
            Method rulesMethod=Guard.class.getDeclaredMethod("enforcedRules",Context.class);rulesMethod.setAccessible(true);Map<String,Rule> managed=(Map<String,Rule>)rulesMethod.invoke(null,c);check(managed.get(b).gate,"new quota-only apps participate in learning gate by default");
            Method reason=Guard.class.getDeclaredMethod("effectiveReason",Context.class,String.class,Rule.class,boolean.class,boolean.class,boolean.class,Calendar.class,boolean.class);reason.setAccessible(true);
            check((Integer)reason.invoke(null,c,b,managed.get(b),false,true,false,GuardClock.now(c),false)==0,"no finite scheduled end does not invent a learning extension");check((Integer)reason.invoke(null,c,b,managed.get(b),true,true,false,GuardClock.now(c),false)==0,"key app remains exempt from learning gate");check((Integer)reason.invoke(null,c,a,managed.get(a),true,true,false,GuardClock.now(c),true)==5,"key app and love reward cannot bypass daily quota");
            RuleStore.put(c,b,new Rule(Rule.ALL_DAYS,new int[][]{{0,0}},true,true));Guard.apply(c);check(Guard.blockReasonNow(c,b)==0&&!d.isPackageSuspended(admin,b),"unused quota replaces immediate freeze inside assigned slot");
            Calendar clock=GuardClock.now(c);int minute=clock.get(Calendar.HOUR_OF_DAY)*60+clock.get(Calendar.MINUTE);
            Rule outside=new Rule(Rule.ALL_DAYS,new int[][]{{(minute+120)%1440,(minute+180)%1440}},true,true);
            RuleStore.put(c,a,outside);Guard.apply(c);check(!DailyQuotaStore.exceeded(c,a)&&!d.isPackageSuspended(admin,a),"exhausted assigned quota releases outside its own slot");
            long beforeOutside=DailyQuotaStore.snapshot(c).used(ga);foregroundApp(c,a,1200);runOnMainSync(()->screen.startActivity(new Intent(screen,DailyQuotaActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));force(c);
            check(DailyQuotaStore.snapshot(c).used(ga)==beforeOutside,"actual foreground outside bound slot is not charged");
            RuleStore.put(c,a,new Rule(Rule.ALL_DAYS,new int[][]{{0,0}},true,true));Guard.apply(c);check(d.isPackageSuspended(admin,a),"exhausted quota freezes again inside its bound slot");RuleStore.remove(c,a);
            JSONObject paused=MemoryData.copy(cfg);paused.put("enabled",false);fixture(p,paused,DailyQuotaStore.snapshot(c).usage);Guard.apply(c);check(!d.isPackageSuspended(admin,a)&&d.isPackageSuspended(admin,b),"pausing daily quotas keeps original freeze");
            RuleStore.remove(c,b);fixture(p,cfg,DailyQuotaStore.snapshot(c).usage);usagePermission(c,"deny",false);Guard.apply(c);check(!d.isPackageSuspended(admin,a)&&!d.isPackageSuspended(admin,b),"permission loss pauses counting instead of protecting freeze");usagePermission(c,"allow",true);Guard.apply(c);check(DailyQuotaStore.exceeded(c,a)&&d.isPackageSuspended(admin,a),"restored permission resumes and actually enforces the exhausted quota");
            ledger.optJSONObject("targets").put(a,30000L).put(b,10000L);ledger.put("lastAt",System.currentTimeMillis());fixture(p,config(group("shared",2,a,b)),ledger);force(c);Guard.apply(c);
            runOnMainSync(()->{try{Method method=DailyQuotaActivity.class.getDeclaredMethod("render");method.setAccessible(true);method.invoke(screen);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(300);android.graphics.Bitmap image=getUiAutomation().takeScreenshot();if(image!=null){try(OutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"daily-quota-ui.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
            if(!coreOnly){
            // Verify the board uses the real move operation and nests quota counters in the slot.
            ledger.optJSONObject("targets").put(a,12000L).put(b,0L);ledger.put("lastAt",System.currentTimeMillis());fixture(p,cfg,ledger);
            RuleStore.put(c,a,FreezeSlotStore.withoutSchedule());RuleStore.put(c,b,new Rule(Rule.ALL_DAYS,new int[][]{{0,0}},true,true));Guard.apply(c);
            ActivityMonitor boardMonitor=addMonitor(FreezeBoardActivity.class.getName(),null,false);runOnMainSync(()->c.startActivity(new Intent(c,FreezeBoardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));Activity board=waitForMonitorWithTimeout(boardMonitor,5000);removeMonitor(boardMonitor);check(board!=null,"freeze board opens");Thread.sleep(800);
            try{
                long quotaCellUntil=SystemClock.elapsedRealtime()+8000;while(label(board.getWindow().getDecorView(),"使用限额")==null&&SystemClock.elapsedRealtime()<quotaCellUntil)Thread.sleep(50);
                check(label(board.getWindow().getDecorView(),"每日限额 · 独立 / 共享")==null&&label(board.getWindow().getDecorView(),"每日使用时间")==null,"duplicate quota cell removed");check(label(board.getWindow().getDecorView(),"使用限额")!=null,"single all-day allowance cell remains");
                String slotId=null;for(FreezeSlotStore.Slot slot:FreezeSlotStore.all(c))if(FreezeSlotStore.members(slot,RuleStore.all(c)).contains(b))slotId=slot.id;check(slotId!=null,"assigned slot available");final String target=slotId;
                runOnMainSync(()->{try{Method move=FreezeBoardActivity.class.getDeclaredMethod("moveApp",String.class,String.class);move.setAccessible(true);move.invoke(board,a,target);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(400);
                check(!FreezeSlotStore.unassigned(RuleStore.get(c,a))&&DailyQuotaStore.snapshot(c).used(ga)>=12000,"move into slot retains assigned quota and its ledger");check(Guard.blockReasonNow(c,a)==0,"moved app remains usable before quota exhausted");
                Field counters=FreezeBoardActivity.class.getDeclaredField("dailyBars");counters.setAccessible(true);Map<UsageQuotaProgress,String> rows=(Map<UsageQuotaProgress,String>)counters.get(board);View targetCard=((Map<String,View>)field(board,"slotViews")).get(target);boolean nested=false;for(Map.Entry<UsageQuotaProgress,String> row:rows.entrySet())if("appA".equals(row.getValue())){android.view.ViewParent parent=row.getKey().getParent();while(parent instanceof View){View node=(View)parent;if(node==targetCard){nested=true;break;}parent=node.getParent();}}check(nested,"quota bar lives inside its assigned freeze slot card");
                runOnMainSync(()->{for(Map.Entry<UsageQuotaProgress,String> row:rows.entrySet())if("appA".equals(row.getValue())){View bar=(View)row.getKey().getParent();bar.requestRectangleOnScreen(new android.graphics.Rect(0,0,bar.getWidth(),bar.getHeight()),true);}});Thread.sleep(250);
                android.graphics.Bitmap boardImage=getUiAutomation().takeScreenshot();if(boardImage!=null){try(OutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"quota-in-slot-ui.png"))){boardImage.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}boardImage.recycle();}
                runOnMainSync(()->{try{Method move=FreezeBoardActivity.class.getDeclaredMethod("moveToAllDay",String.class);move.setAccessible(true);move.invoke(board,a);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(300);
                check(!FreezeSlotStore.unassigned(RuleStore.get(c,a)),"unused quota does not allow detaching during the configured freeze period");
                int adjustmentMinute=TimeWindow.minuteOf(System.currentTimeMillis());TimeWindow.save(c,(adjustmentMinute+60)%1440,(adjustmentMinute+120)%1440);
                RuleStore.put(c,a,outside);Guard.apply(c);invoke(board,"rebuild",new Class<?>[]{});invoke(board,"moveToAllDay",new Class<?>[]{String.class},a);
                check(FreezeSlotStore.unassigned(RuleStore.get(c,a))&&DailyQuotaStore.snapshot(c).used(ga)>=12000,"non-freeze period permits all-day move outside global adjustment window and preserves usage");
                invoke(board,"moveApp",new Class<?>[]{String.class,String.class},a,target);Thread.sleep(350);
                RuleStore.put(c,a,outside);Guard.apply(c);invoke(board,"rebuild",new Class<?>[]{});
                dragToLibrary(board,a,false);check(FreezeSlotStore.unassigned(RuleStore.get(c,a)),"actual drag onto expanded library returns app from slot");
                check(DailyQuotaStore.snapshot(c).group(a).optInt("minutes")==1&&DailyQuotaStore.snapshot(c).used(ga)>=12000,"library drop preserves quota and today's ledger");
                invoke(board,"moveApp",new Class<?>[]{String.class,String.class},a,target);Thread.sleep(350);
                RuleStore.put(c,a,outside);Guard.apply(c);invoke(board,"rebuild",new Class<?>[]{});
                dragToLibrary(board,a,true);check(FreezeSlotStore.unassigned(RuleStore.get(c,a)),"collapsed library handle accepts actual drop");
                String noQuota="com.afs.quota.noquota";RuleStore.put(c,noQuota,outside);Set<String> keyApps=new HashSet<>(RuleStore.keyApps(c));keyApps.add(noQuota);RuleStore.setKeyApps(c,keyApps);invoke(board,"rebuild",new Class<?>[]{});
                invoke(board,"moveToLibrary",new Class<?>[]{String.class},noQuota);
                check(FreezeSlotStore.unassigned(RuleStore.get(c,noQuota))&&!RuleStore.get(c,noQuota).gate,"unlimited app returns without opening quota editor and preserves key status");
                check(AppUsageQuota.minutes(c,noQuota)==0&&DailyQuotaStore.snapshot(c).group(noQuota)==null,"returning an unlimited app never creates a quota");
                RuleStore.put(c,a,new Rule(Rule.ALL_DAYS,new int[][]{{0,0}},true,true));JSONObject exhausted=MemoryData.copy(DailyQuotaStore.snapshot(c).usage);exhausted.optJSONObject("targets").put(a,60000L);exhausted.put("lastAt",System.currentTimeMillis());fixture(p,cfg,exhausted);Guard.apply(c);invoke(board,"rebuild",new Class<?>[]{});
                Config.arm(c);
                JSONObject aged=new JSONObject(RuleStore.exportRules(c));aged.getJSONObject(a).put("at",System.currentTimeMillis()-RuleStore.NEW_GRACE_MS-60_000L);r.edit().putString("rules",aged.toString()).commit();
                TimeWindow.save(c,(adjustmentMinute+60)%1440,(adjustmentMinute+120)%1440);
                invoke(board,"moveToLibrary",new Class<?>[]{String.class},a);check(!FreezeSlotStore.unassigned(RuleStore.get(c,a)),"outside the adjustment window an old frozen app remains protected");
                TimeWindow.save(c,0,0);invoke(board,"rebuild",new Class<?>[]{});
                invoke(board,"moveToLibrary",new Class<?>[]{String.class},a);
                check(!FreezeSlotStore.unassigned(RuleStore.get(c,a)),"global adjustment window cannot detach an app during its own freeze");
                invoke(board,"moveToAllDay",new Class<?>[]{String.class},a);
                check(!FreezeSlotStore.unassigned(RuleStore.get(c,a)),"global adjustment window cannot change frozen app to all-day accounting");
                TimeWindow.save(c,(adjustmentMinute+60)%1440,(adjustmentMinute+120)%1440);
                RuleStore.put(c,a,outside);Guard.apply(c);invoke(board,"rebuild",new Class<?>[]{});
                dragToLibrary(board,a,false);
                check(FreezeSlotStore.unassigned(RuleStore.get(c,a)),"non-freeze period allows actual drag outside the global adjustment window");
                check(DailyQuotaStore.snapshot(c).used(ga)>=60000L&&DailyQuotaStore.snapshot(c).group(a).optInt("minutes")==1,"removing a released app preserves used time and quota");
                Guard.apply(c);check(d.isPackageSuspended(admin,a),"exhausted quota still freezes the app after its schedule is removed");
                RuleStore.put(c,a,outside);Guard.apply(c);invoke(board,"rebuild",new Class<?>[]{});invoke(board,"appDetails",new Class<?>[]{String.class},a);
                AlertDialog appPanel=(AlertDialog)field(board,"applicationPanel");check(appPanel!=null&&appPanel.isShowing(),"actual application detail panel opens");
                View remove=label(appPanel.getWindow().getDecorView(),"移出冻结列表");check(remove!=null,"remove-from-slot action is present");while(remove!=null&&!remove.isClickable())remove=remove.getParent() instanceof View?(View)remove.getParent():null;
                check(remove!=null,"remove-from-slot action is clickable");final View removeAction=remove;runOnMainSync(removeAction::performClick);Thread.sleep(250);
                check(FreezeSlotStore.unassigned(RuleStore.get(c,a))&&!appPanel.isShowing(),"detail action removes the app from its slot and dismisses the stale panel");
                invoke(board,"appDetails",new Class<?>[]{String.class},a);AlertDialog detached=(AlertDialog)field(board,"applicationPanel");check(label(detached.getWindow().getDecorView(),"移出冻结列表")==null,"detached app no longer shows a phantom remove action");invoke(board,"dismissApplicationPanel",new Class<?>[]{});
                RuleStore.put(c,a,new Rule(Rule.ALL_DAYS,new int[][]{{0,0}},true,true));RuleStore.markAdded(c,a);TimeWindow.save(c,(adjustmentMinute+60)%1440,(adjustmentMinute+120)%1440);invoke(board,"rebuild",new Class<?>[]{});
                invoke(board,"moveToLibrary",new Class<?>[]{String.class},a);check(!FreezeSlotStore.unassigned(RuleStore.get(c,a)),"new-app grace cannot bypass the freeze-period membership lock");
                TimeWindow.save(c,0,0);
                JSONObject liveShared=group("shared-live",120,a,b).put("background",new JSONArray(Arrays.asList(a,b)));
                fixture(p,config(liveShared),DailyQuotaStore.snapshot(c).usage);
                RuleStore.put(c,a,new Rule(Rule.ALL_DAYS,new int[][]{{0,0}},true,true));
                RuleStore.put(c,b,new Rule(Rule.ALL_DAYS,new int[][]{{0,0}},true,true));
                Guard.apply(c);invoke(board,"rebuild",new Class<?>[]{});
                long usedBeforeShared=DailyQuotaStore.snapshot(c).usage.optJSONObject("targets").optLong(a);
                String sharedRules=RuleStore.exportRules(c);
                invoke(board,"quotaEditor",new Class<?>[]{JSONObject.class,String.class,boolean.class,String.class,String.class,Set.class},liveShared,null,true,null,null,new LinkedHashSet<String>(Arrays.asList(b)));
                check(clickText(T.s("保存")),"edited shared quota exposes a usable save action");Thread.sleep(250);
                check(DailyQuotaStore.snapshot(c).group(a)==null&&DailyQuotaStore.snapshot(c).group(b)!=null,"picker-return selection really removes the unchecked member");
                check(DailyQuotaStore.snapshot(c).usage.optJSONObject("targets").optLong(a)>=usedBeforeShared,"editor removal retains the removed member's history");
                check(sharedRules.equals(RuleStore.exportRules(c)),"editor quota removal does not detach or alter scheduled freezes");
                fixture(p,config(liveShared),DailyQuotaStore.snapshot(c).usage);invoke(board,"rebuild",new Class<?>[]{});
                TimeWindow.save(c,(adjustmentMinute+60)%1440,(adjustmentMinute+120)%1440);invoke(board,"removeQuotaMember",new Class<?>[]{String.class,String.class},"shared-live",a);
                check(DailyQuotaStore.snapshot(c).group(a)!=null,"shared member cannot be removed outside the quota adjustment window");
                TimeWindow.save(c,0,0);dragQuotaToLibrary(board,"shared-live",a,false);
                check(DailyQuotaStore.snapshot(c).group(a)==null&&DailyQuotaStore.snapshot(c).group(b)!=null,"actual shared-icon drag to expanded library removes quota membership");
                check(sharedRules.equals(RuleStore.exportRules(c)),"shared-icon drop preserves freeze-slot assignment");
                check(DailyQuotaStore.snapshot(c).usage.optJSONObject("targets").optLong(a)>=usedBeforeShared,"shared-icon drop preserves the per-app ledger");
                check(!DailyQuotaData.backgroundApps(DailyQuotaStore.snapshot(c).group(b)).contains(a),"removed member does not linger in background counting");
                Guard.apply(c);check(Guard.blockReasonNow(c,a)==1&&d.isPackageSuspended(admin,a),"removing shared quota restores strict scheduled freeze rather than deleting the schedule");
                fixture(p,config(liveShared),DailyQuotaStore.snapshot(c).usage);Guard.apply(c);invoke(board,"rebuild",new Class<?>[]{});dragQuotaToLibrary(board,"shared-live",a,true);
                check(DailyQuotaStore.snapshot(c).group(a)==null,"collapsed library also accepts shared-quota icon removal");
                invoke(board,"removeQuotaMember",new Class<?>[]{String.class,String.class},"shared-live",b);
                check(DailyQuotaStore.snapshot(c).groups().length()==0&&!DailyQuotaStore.snapshot(c).enabled(),"last shared member removal clears an empty quota group");
                check(sharedRules.equals(RuleStore.exportRules(c)),"last-member removal still preserves all freeze rules");
                fixture(p,config(liveShared),DailyQuotaStore.snapshot(c).usage);invoke(board,"rebuild",new Class<?>[]{});
                invoke(board,"quotaEditor",new Class<?>[]{JSONObject.class,String.class,boolean.class,String.class,String.class,Set.class},liveShared,null,true,null,null,Collections.emptySet());
                check(clickText(T.s("保存")),"empty returned selection can be saved");Thread.sleep(250);
                check(DailyQuotaStore.snapshot(c).groups().length()==0&&!DailyQuotaStore.snapshot(c).enabled(),"unchecking every member deletes the old group without recreating its members");


            }finally{runOnMainSync(board::finishAndRemoveTask);}
            }
            result.putString("stream",(coreOnly?"Daily quota core device: ":"Daily quota device: ")+checks+" checks passed\n");code=0;
        }catch(Throwable error){StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));result.putString("stream",trace.toString());}
        finally{try{restore(p,backup);restore(r,rules);restore(visual,visualBackup);restore(adjust,adjustBackup);if(!permission)shell("appops set com.afs.guard GET_USAGE_STATS default");if(Guard.isOwner(c)){Guard.dpm(c).setPackagesSuspended(Guard.admin(c),new String[]{a,b},false);Guard.dpm(c).setUninstallBlocked(Guard.admin(c),a,false);Guard.dpm(c).setUninstallBlocked(Guard.admin(c),b,false);}Guard.apply(c);if(screen!=null)runOnMainSync(screen::finishAndRemoveTask);}catch(Throwable error){code=-1;result.putString("cleanup",error.toString());}finish(code,result);}
    }
    private boolean clickText(String text)throws Exception{
        long end=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<end){
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo hit:root.findAccessibilityNodeInfosByText(text)){
                android.view.accessibility.AccessibilityNodeInfo node=hit;for(int depth=0;node!=null&&depth<5;depth++,node=node.getParent())if(node.isClickable()&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
    private void dragQuotaToLibrary(Activity board,String group,String pkg,boolean collapsed)throws Exception{
        invoke(board,"setLibraryHidden",new Class<?>[]{boolean.class,boolean.class},collapsed,false);Thread.sleep(100);
        View[] source={null};runOnMainSync(()->source[0]=board.getWindow().getDecorView().findViewWithTag("quota-member:"+group+":"+pkg));check(source[0]!=null,"shared quota member has its own drag source");
        View tile=source[0];runOnMainSync(()->tile.requestRectangleOnScreen(new android.graphics.Rect(0,0,tile.getWidth(),tile.getHeight()),true));Thread.sleep(150);
        View library=(View)field(board,"library");android.graphics.Rect visible=new android.graphics.Rect();int[] destination=new int[2];runOnMainSync(()->{tile.getGlobalVisibleRect(visible);library.getLocationOnScreen(destination);});check(!visible.isEmpty(),"shared quota member is visible for native dragging");
        float x=visible.exactCenterX(),y=visible.exactCenterY(),tx=destination[0]+library.getWidth()/2f,ty=destination[1]+library.getHeight()*(collapsed?.5f:.65f);
        long down=SystemClock.uptimeMillis();inject(down,0,x,y);Thread.sleep(ViewConfiguration.getLongPressTimeout()+150);
        for(int i=0;i<20&&!(Boolean)field(board,"dragging");i++)Thread.sleep(50);
        check((Boolean)field(board,"dragging")&&group.equals(field(board,"dragQuotaGroup")),"shared long press remembers which quota is being edited");
        for(int i=1;i<=8;i++){inject(down,2,x+(tx-x)*i/8,y+(ty-y)*i/8);Thread.sleep(40);}inject(down,1,tx,ty);
        for(int i=0;i<40&&DailyQuotaStore.snapshot(getTargetContext()).group(pkg)!=null;i++)Thread.sleep(50);
    }
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void invoke(Activity target,String name,Class<?>[] types,Object... args){runOnMainSync(()->{try{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(target,args);}catch(Exception error){throw new RuntimeException(error);}});}
    private View tagged(View v,String pkg){if(pkg.equals(v.getTag()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=tagged(((ViewGroup)v).getChildAt(i),pkg);if(found!=null)return found;}return null;}
    private void inject(long down,int action,float x,float y){android.view.MotionEvent event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{if(!getUiAutomation().injectInputEvent(event,true))throw new AssertionError("touch injection failed");}finally{event.recycle();}}
    private void dragToLibrary(Activity board,String pkg,boolean collapsed)throws Exception{
        invoke(board,"setLibraryHidden",new Class<?>[]{boolean.class,boolean.class},collapsed,false);Thread.sleep(200);
        View assigned=null;Map<String,View> slotViews=(Map<String,View>)field(board,"slotViews");for(FreezeSlotStore.Slot slot:FreezeSlotStore.all(getTargetContext()))if(FreezeSlotStore.members(slot,RuleStore.all(getTargetContext())).contains(pkg)){assigned=slotViews.get(slot.id);break;}
        check(assigned!=null,"assigned slot is present before scrolling its virtualized app row");final View assignedSlot=assigned;runOnMainSync(()->assignedSlot.requestRectangleOnScreen(new android.graphics.Rect(0,0,assignedSlot.getWidth(),Math.min(assignedSlot.getHeight(),600)),true));
        View marker=null;for(int i=0;i<40&&marker==null;i++){Thread.sleep(50);marker=tagged((View)field(board,"slotsView"),pkg);}check(marker!=null,"assigned source tile exists for real drag");View tile=(View)marker.getParent().getParent();
        runOnMainSync(()->tile.requestRectangleOnScreen(new android.graphics.Rect(0,0,tile.getWidth(),tile.getHeight()),true));Thread.sleep(200);
        View library=(View)field(board,"library");int[] from=new int[2],to=new int[2];runOnMainSync(()->{tile.getLocationOnScreen(from);library.getLocationOnScreen(to);});
        android.graphics.Rect visible=new android.graphics.Rect();runOnMainSync(()->tile.getGlobalVisibleRect(visible));check(!visible.isEmpty(),"source tile is actually visible");
        float x=visible.exactCenterX(),y=visible.exactCenterY(),tx=to[0]+library.getWidth()/2f,ty=to[1]+library.getHeight()*(collapsed?.5f:.65f);
        android.graphics.Bitmap before=getUiAutomation().takeScreenshot();if(before!=null){try(OutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"library-before-drag.png"))){before.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}before.recycle();}
        long down=android.os.SystemClock.uptimeMillis();inject(down,0,x,y);Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout()+200);
        for(int i=0;i<20&&!(Boolean)field(board,"dragging");i++)Thread.sleep(100);
        check((Boolean)field(board,"dragging"),"native long press starts Android drag at "+x+","+y+" source="+visible);
        for(int i=1;i<=8;i++){inject(down,2,x+(tx-x)*i/8,y+(ty-y)*i/8);Thread.sleep(40);}inject(down,1,tx,ty);
        for(int i=0;i<30&&!FreezeSlotStore.unassigned(RuleStore.get(getTargetContext(),pkg));i++)Thread.sleep(50);
    }
    private void restore(SharedPreferences prefs,Map<String,?> values){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){Object v=entry.getValue();String key=entry.getKey();if(v instanceof String)edit.putString(key,(String)v);else if(v instanceof Boolean)edit.putBoolean(key,(Boolean)v);else if(v instanceof Long)edit.putLong(key,(Long)v);else if(v instanceof Integer)edit.putInt(key,(Integer)v);else if(v instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)v));}edit.commit();}
}
