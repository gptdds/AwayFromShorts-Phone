package com.afs.guard;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.Bundle;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import com.afs.host.usage.HostUsageStats;
import com.afs.guard.plugins.BuiltInPluginStore;
import com.afs.guard.data.AfsRepositories;
import com.afs.guard.data.DataRecords;

/** Bundled statistics page. Phone data and classification stay in the host. */
public class StatsActivity extends ThemedActivity {
    private Ui ui;private LinearLayout body,pc;private ScrollView scroll;
    private ScreenUsageData data=new ScreenUsageData();
    private String period="day",date=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());
    private boolean destroyed,loading,expanded;private String error="";
    private DataRecords.DailyStatRecord phase3Today;
    private List<DataRecords.DailyStatRecord> phase3Week=Collections.emptyList();
    private boolean phase3Loaded;private String phase3Error="";
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override protected void onCreate(Bundle state){super.onCreate(state);ui=new Ui(this);
        if(state!=null){period=state.getString("period","day");date=state.getString("date",date);}
        render();
    }
    @Override protected void onResume(){super.onResume();if(ui!=null)refresh();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("period",period);out.putString("date",date);}
    @Override protected void onDestroy(){destroyed=true;worker.shutdownNow();super.onDestroy();}
    static boolean hasUsageAccess(Context c){return HostUsageStats.hasUsageAccess(c);}
    private void refresh(){
        if(loading)return;loading=true;error="";render();
        worker.execute(()->{try{
            try{
                loadPhase3();
                runOnUiThread(()->{if(!destroyed)render();});
            }catch(Exception phase3Failure){
                phase3Loaded=true;phase3Error=phase3Failure.getClass().getSimpleName();
                runOnUiThread(()->{if(!destroyed)render();});
            }
            ScreenUsageData cached=ScreenUsageStore.load(this);
            runOnUiThread(()->{if(!destroyed){data=cached;render();}});
            ScreenUsageData result=ScreenUsageStore.collect(this);
            runOnUiThread(()->{if(!destroyed){loading=false;data=result;render();}});
            PcStats.ensureFresh(this);
            runOnUiThread(()->{if(!destroyed&&pc!=null)renderPc();});
        }catch(Exception e){runOnUiThread(()->{if(!destroyed){loading=false;error=T.s("统计读取失败，请刷新重试");render();}});}});
    }
    private void loadPhase3()throws Exception{
        TimeZone zone=GuardClock.pin(this);
        java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US);
        format.setTimeZone(zone);
        Calendar end=Calendar.getInstance(zone);
        String today=format.format(end.getTime());
        Calendar start=(Calendar)end.clone();start.add(Calendar.DAY_OF_YEAR,-6);
        AfsRepositories repositories=AfsRepositories.get(this);
        phase3Today=repositories.statistics().forDay(today,zone.getID());
        phase3Week=repositories.statistics().range(format.format(start.getTime()),today,zone.getID());
        phase3Loaded=true;phase3Error="";
    }
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private TextView action(String title,Runnable run){TextView t=text(title,14,ui.accent());t.setGravity(Gravity.CENTER);t.setMinHeight(ui.dp(48));t.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8));t.setBackground(ui.round(ui.surface2(),8));t.setOnClickListener(v->run.run());return t;}
    private void note(LinearLayout parent,String value){TextView t=ui.sub(value);t.setPadding(0,ui.dp(8),0,ui.dp(12));parent.addView(t);}
    private LinearLayout card(String title){LinearLayout panel=ui.card();panel.addView(text(title,17,ui.text()));body.addView(panel,ui.gap(16));return panel;}
    private String key(){return "month".equals(period)?date.substring(0,7):"year".equals(period)?date.substring(0,4):date;}
    private void render(){
        int y=scroll==null?0:scroll.getScrollY();scroll=new ScrollView(this);ui.page(scroll);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(32));scroll.addView(body);setContentView(scroll);
        body.addView(action(T.s("返回"),this::finish));body.addView(ui.title(T.s("屏幕使用统计")));
        renderPhase3();
        if(!BuiltInPluginStore.enabled(this,"usage-stats")){note(body,T.s("插件已停用"));return;}
        note(body,T.s("手机应用使用时长 · 娱乐占比 · 电脑端"));
        if(!hasUsageAccess(this)){
            note(body,T.s("需要使用情况访问权限才能读取手机应用时长"));
            body.addView(action(T.s("前往系统授权"),()->{try{startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));}catch(RuntimeException e){Toast.makeText(this,T.s("无法打开系统设置"),0).show();}}));
        }
        LinearLayout tabs=new LinearLayout(this);
        String[] keys={"day","month","year","total"},labels={T.s("日统计"),T.s("月统计"),T.s("年统计"),T.s("总统计")};
        for(int i=0;i<keys.length;i++){final String p=keys[i];TextView tab=action(labels[i],()->{period=p;expanded=false;render();});if(period.equals(p)){tab.setBackground(ui.round(ui.accent(),8));tab.setTextColor(ui.onAccent());}tabs.addView(tab,new LinearLayout.LayoutParams(0,-2,1));}
        body.addView(tabs,ui.gap(12));
        if(!"total".equals(period))body.addView(action(key(),this::pickPeriod),ui.gap(12));
        if(!error.isEmpty())note(body,error);
        Map<String,Long> values=data.period(period,key());Set<String> fun=ScreenUsageStore.entertainment(this,data.period("total","").keySet());
        LinearLayout summary=card(T.s("手机应用使用时长"));
        TextView figure=text(loading&&values.isEmpty()?"—":duration(ScreenUsageData.total(values)),32,ui.text());SwissTypography.role(figure,SwissTypography.Role.NUMBER);summary.addView(figure);
        note(summary,T.f("娱乐占比 %d%%",ScreenUsageData.percent(values,fun)));
        if("total".equals(period))note(summary,data.yearKeys().isEmpty()?T.s("暂无可读取的历史"):T.f("累计覆盖年份：%s",android.text.TextUtils.join("、",data.yearKeys())));
        note(summary,T.s("历史取决于系统留存；已有数据会持续归档。日、月、年统计按自然日历计算"));
        String first=data.firstDay("total".equals(period)?"":key());if(!"day".equals(period)&&!first.isEmpty())note(summary,T.f("逐日明细：%s 起",first));
        summary.addView(action(T.s("自定义娱乐应用"),this::editEntertainment));
        LinearLayout ranking=card(T.s("应用使用排行"));
        ArrayList<Map.Entry<String,Long>> rows=new ArrayList<>(values.entrySet());rows.sort((a,b)->Long.compare(b.getValue(),a.getValue()));
        if(rows.isEmpty())note(ranking,loading?T.s("正在读取统计…"):T.s("此时段暂无可读取记录"));
        int visible=expanded?rows.size():Math.min(15,rows.size());
        for(int i=0;i<visible;i++){Map.Entry<String,Long> item=rows.get(i);row(ranking,(i+1)+". "+label(item.getKey()),duration(item.getValue()));}
        if(rows.size()>15)ranking.addView(action(expanded?T.s("收起"):T.f("展开其余 %d 个应用",rows.size()-15),()->{expanded=!expanded;render();}));
        LinearLayout trend=card(T.s("近 7 天娱乐占比"));note(trend,T.s("按你选择的娱乐应用计算，占全部手机应用前台时长的比例"));
        for(int i=6;i>=0;i--){Calendar day=Calendar.getInstance();day.add(Calendar.DAY_OF_YEAR,-i);String k=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(day.getTime());Map<String,Long> daily=data.period("day",k);row(trend,k.substring(5),daily.isEmpty()?"—":ScreenUsageData.percent(daily,fun)+"%");}
        LinearLayout game=card(T.s("游戏时长"));long gameMs=0;for(String pkg:values.keySet())try{if(ScreenUsageStore.isGame(getPackageManager().getApplicationInfo(pkg,0)))gameMs+=values.get(pkg);}catch(PackageManager.NameNotFoundException ignored){}note(game,duration(gameMs));
        pc=card(T.s("电脑端"));renderPc();
        body.addView(action(T.s("刷新"),()->{refresh();}),ui.gap(12));
        scroll.post(()->{if(!destroyed)scroll.scrollTo(0,y);});
    }
    private void renderPhase3(){
        LinearLayout phase=card(T.s("专注与守护"));
        if(!phase3Loaded){note(phase,T.s("正在读取本地数据库…"));return;}
        if(!phase3Error.isEmpty()){note(phase,T.s("本地数据库统计不可用：")+phase3Error);return;}
        DataRecords.DailyStatRecord today=phase3Today;
        long focus=today==null?0L:today.focusDuration;
        int blocked=today==null?0:today.blockedAttempts;
        int success=today==null?0:today.successfulEnforcements;
        int failed=today==null?0:today.failedEnforcements;
        TextView figure=text(duration(focus),32,ui.text());SwissTypography.role(figure,SwissTypography.Role.NUMBER);phase.addView(figure);
        note(phase,T.f("今日专注 %s · 拦截 %d 次",duration(focus),blocked));
        row(phase,T.s("已验证冻结切换"),String.valueOf(success));
        row(phase,T.s("执行失败"),String.valueOf(failed));
        row(phase,T.s("守护成功率"),success+failed==0?T.s("暂无记录"):(success*100/(success+failed))+"%");
        note(phase,T.s("近 7 天专注与拦截"));
        for(DataRecords.DailyStatRecord record:phase3Week){
            row(phase,record.localDate,duration(record.focusDuration)+" · "+record.blockedAttempts+" 次拦截");
        }
        if(phase3Week.isEmpty())note(phase,T.s("近 7 天暂无本地事件记录"));
        note(phase,T.s("只显示真实专注会话和已验证的系统执行结果；不以计划时长冒充使用时长。"));
    }
    private void renderPc(){
        pc.removeAllViews();pc.addView(text(T.s("电脑端"),17,ui.text()));
        LinearLayout tabs=new LinearLayout(this);String[] modes={"day","month","total"};String[] names={T.s("日统计"),T.s("月统计"),T.s("总统计")};
        for(int i=0;i<modes.length;i++){final String mode=modes[i];tabs.addView(action(names[i],()->showPc(mode)),new LinearLayout.LayoutParams(0,-2,1));}pc.addView(tabs);showPc("day");
    }
    private void showPc(String mode){
        while(pc.getChildCount()>2)pc.removeViewAt(2);
        List<PcStats.Entry> rows="day".equals(mode)?PcStats.dayApps(this,date):"month".equals(mode)?PcStats.monthApps(this,date):PcStats.totalApps(this);
        note(pc,date+" · "+duration(PcStats.totalMinutes(rows)*60000L));
        if(rows.isEmpty())note(pc,T.s("暂无电脑端统计"));
        for(int i=0;i<Math.min(rows.size(),10);i++){PcStats.Entry e=rows.get(i);row(pc,PcStats.label(e.name),duration(e.min*60000L));}
    }
    private void row(LinearLayout parent,String title,String value){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,ui.dp(12),0,ui.dp(12));TextView name=text(title,15,ui.text());name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);r.addView(name,new LinearLayout.LayoutParams(0,-2,1));TextView amount=text(value,13,ui.text2());amount.setPadding(ui.dp(12),0,0,0);amount.setFontFeatureSettings("'tnum' 1");r.addView(amount);parent.addView(r);}
    private String label(String pkg){return data.labels.containsKey(pkg)?data.labels.get(pkg):pkg;}
    private static String duration(long ms){long min=ms/60000;return ms<=0?T.s("0 分钟"):min<1?T.s("小于 1 分钟"):T.f("%d 小时 %d 分钟",min/60,min%60);}
    private void pickPeriod(){
        if("year".equals(period)){
            Set<String> years=data.yearKeys();years.add(date.substring(0,4));String[] options=years.toArray(new String[0]);
            ThemeStore.dialog(this).setTitle(T.s("选择年份")).setItems(options,(d,w)->{date=options[w]+date.substring(4);render();}).show();return;
        }
        Calendar selected=Calendar.getInstance();try{selected.setTime(new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(date));}catch(Exception ignored){}
        DatePickerDialog picker=new DatePickerDialog(this,(view,y,m,d)->{date=String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d);render();},selected.get(Calendar.YEAR),selected.get(Calendar.MONTH),selected.get(Calendar.DAY_OF_MONTH));picker.getDatePicker().setMaxDate(System.currentTimeMillis());picker.show();
    }
    private void editEntertainment(){
        Set<String> chosen=ScreenUsageStore.entertainment(this,data.period("total","").keySet());
        Map<String,String> apps=new HashMap<>(data.labels);
        for(ApplicationInfo app:getPackageManager().getInstalledApplications(0))if(getPackageManager().getLaunchIntentForPackage(app.packageName)!=null)apps.put(app.packageName,getPackageManager().getApplicationLabel(app).toString());
        for(String p:chosen)if(!apps.containsKey(p))apps.put(p,label(p));
        ArrayList<String> all=new ArrayList<>(apps.keySet());all.sort((a,b)->apps.get(a).compareToIgnoreCase(apps.get(b)));ArrayList<String> filtered=new ArrayList<>();
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(ui.dp(16),0,ui.dp(16),0);
        EditText search=new EditText(this);search.setSingleLine(true);search.setHint(T.s("搜索应用"));box.addView(search);
        ListView list=new ListView(this);list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);box.addView(list,new LinearLayout.LayoutParams(-1,ui.dp(320)));
        ArrayList<String> names=new ArrayList<>();ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_multiple_choice,names);list.setAdapter(adapter);
        Runnable update=()->{filtered.clear();names.clear();String q=search.getText().toString().toLowerCase(Locale.ROOT);for(String p:all)if((apps.get(p)+" "+p).toLowerCase(Locale.ROOT).contains(q)){filtered.add(p);names.add(apps.get(p));}adapter.notifyDataSetChanged();list.clearChoices();for(int i=0;i<filtered.size();i++)list.setItemChecked(i,chosen.contains(filtered.get(i)));};
        list.setOnItemClickListener((parent,v,position,id)->{String p=filtered.get(position);if(list.isItemChecked(position))chosen.add(p);else chosen.remove(p);});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){update.run();}public void afterTextChanged(Editable e){}});update.run();
        AlertDialog dialog=ThemeStore.dialog(this).setTitle(T.s("自定义娱乐应用")).setView(box).setNegativeButton(T.s("取消"),null).setNeutralButton(T.s("清空"),null).setPositiveButton(T.s("保存"),null).create();
        dialog.setOnShowListener(v->{dialog.getButton(-3).setOnClickListener(w->{chosen.clear();update.run();});dialog.getButton(-1).setOnClickListener(w->{if(ScreenUsageStore.saveEntertainment(this,chosen)){dialog.dismiss();render();}else Toast.makeText(this,T.s("保存失败，请重试"),0).show();});});dialog.show();
    }
}
