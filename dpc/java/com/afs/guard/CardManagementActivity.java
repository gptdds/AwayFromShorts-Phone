package com.afs.guard;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Single settings entry under Guard; learning and check-in stay on their own pages. */
public class CardManagementActivity extends ThemedActivity {
    public static final String EXTRA_MANAGE="card_management";
    private Ui ui;private LinearLayout body;private String category="",renderedCategory;
    private Bundle categoryScroll=new Bundle();private boolean resumedOnce;
    private boolean resumed;
    private TextView linkageState,linkageElapsed,rewardRemaining;
    private final Handler linkageHandler=new Handler(Looper.getMainLooper());
    private final Runnable linkageTick=new Runnable(){
        public void run(){
            if(!resumed)return;
            refreshRewardSummary();
            refreshLinkageSummary();
            linkageHandler.postDelayed(this,1000L);
        }
    };
    @Override protected void onCreate(Bundle saved){if(Ui.isDark(this))setTheme(getResources().getIdentifier("NavThemeDark","style",getPackageName()));super.onCreate(saved);if(saved!=null&&saved.getBundle("category_scroll")!=null)categoryScroll=saved.getBundle("category_scroll");ui=new Ui(this);category=saved==null?getIntent().getStringExtra("category"):saved.getString("category","");if(category==null)category="";render();}
    @Override protected void onSaveInstanceState(Bundle out){if(renderedCategory!=null)categoryScroll.putBundle(renderedCategory,PageScrollState.capture(this));out.putBundle("category_scroll",categoryScroll);out.putString("category",category);super.onSaveInstanceState(out);}
    @Override protected void onResume(){resumed=true;super.onResume();if(ui!=null&&ui.dark!=Ui.isDark(this)){recreate();return;}if(!resumedOnce){resumedOnce=true;startLinkageRefresh();return;}if(ui!=null)render();}
    @Override protected void onPause(){resumed=false;stopLinkageRefresh();super.onPause();}
    @Override protected void onDestroy(){stopLinkageRefresh();super.onDestroy();}
    @Override public void onBackPressed(){if(category.isEmpty())super.onBackPressed();else{category="";render();}}
    private void base(String title){
        ScrollView scroll=new ScrollView(this);ui.page(scroll);scroll.setFillViewport(true);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(32));scroll.addView(body);
        TextView back=ui.sub(category.isEmpty()?T.s("‹  守卫"):T.s("‹  卡管理"));back.setTextColor(ui.accent());back.setPadding(0,ui.dp(8),0,ui.dp(20));back.setOnClickListener(v->onBackPressed());body.addView(back);body.addView(ui.title(title));
        // 卡管理是守卫下的设置中转页，不是五个主标签之一：不套底栏，
        // 否则这里亮着「守卫」却整页都是别的卡片的入口，逻辑上是错的。
        setContentView(scroll);FloatingNav.fullscreen(this);
    }
    private void hint(LinearLayout box,String text){TextView t=ui.sub(text);t.setPadding(0,ui.dp(8),0,ui.dp(10));box.addView(t);}
    private LinearLayout card(){LinearLayout c=ui.card();body.addView(c,ui.gap(12));return c;}
    private TextView button(LinearLayout box,String label,boolean primary,Runnable action){
        TextView t=ui.sub(label);SwissTypography.role(t,SwissTypography.Role.BUTTON);t.setTextColor(primary?ui.onAccent():ui.accent());t.setGravity(Gravity.CENTER);t.setPadding(ui.dp(12),ui.dp(14),ui.dp(12),ui.dp(14));t.setBackground(ui.round(primary?ui.accent():ui.surface2(),ui.button(14)));
        box.addView(t,ui.gap(10));t.setOnClickListener(v->{try{action.run();}catch(RuntimeException e){toast(e.getMessage());}});return t;
    }
    private void toast(String value){Toast.makeText(this,value==null?T.s("请稍后重试"):value,Toast.LENGTH_SHORT).show();}
    private void render(){
        stopLinkageRefresh();
        linkageState=null;linkageElapsed=null;rewardRemaining=null;
        if(renderedCategory!=null)categoryScroll.putBundle(renderedCategory,PageScrollState.capture(this));
        try{if("love".equals(category))love();else if("summary".equals(category))summary();else if("checkin".equals(category))checkinSettings();else home();}
        catch(RuntimeException e){base(T.s("卡管理"));hint(body,e.getMessage());}
        renderedCategory=category;PageScrollState.restore(this,categoryScroll.getBundle(category));
        if(resumed)startLinkageRefresh();
    }
    private void home(){
        category="";base(T.s("卡管理"));hint(body,T.s("统一管理卡片、学习联动和奖励。"));
        long nowMs=GuardClock.now(this).getTimeInMillis();CardData data=CardStore.snapshot(this);
        int available=data.availableTickets(nowMs),waiting=data.waitingTickets(nowMs);
        LinearLayout balance=card();balance.addView(ui.title(available+T.s(" 张可用热爱券")));
        hint(balance,T.s("按卡积累 · 用一张解冻所有应用的时段冻结和使用限额 · 领取后需满 24 小时才能使用。")+(waiting>0?T.s(" 待解锁 ")+waiting+T.s(" 张。"):""));
        TextView homeUse=button(balance,T.s("使用一张热爱卡"),true,()->useTicket());homeUse.setTag("reward_use_home");
        rewardRemaining=dynamicHint(balance,"reward_remaining");
        refreshRewardSummary();
        LinearLayout linkage=card();linkage.addView(ui.title(T.s("学习联动罚时")));
        hint(linkage,T.s("20:00 检查未完成任务，只在当天结束的冻结后接续，持续至任务完成。跨午夜、次日才结束的冻结不参与学习联动。正在放行的应用不提前打断。完成任务解除联动，热爱奖励不抵扣。"));
        linkageState=dynamicHint(linkage,"learning_linkage_state");
        linkageElapsed=dynamicHint(linkage,"learning_linkage_elapsed");
        refreshLinkageSummary();
        row(T.s("打卡设置"),T.s("外观 · 字体 · 同步 · 上传"),()->{category="checkin";render();});
        row(T.s("热爱卡"),T.s("完成攒券，用券解冻时段冻结"),()->{category="love";render();});
        row(T.s("记忆卡"),T.s("抽取、学习计划、学科与卡片"),()->startActivity(new Intent(this,MemoryActivity.class).putExtra(EXTRA_MANAGE,true)));
        row(T.s("刷题卡"),T.s("每日目标、学习联动与题库"),()->startActivity(new Intent(this,QuizActivity.class).putExtra(EXTRA_MANAGE,true)));
        row(T.s("目标卡"),T.s("创建目标、默认完成量与卡面设置"),()->startActivity(new Intent(this,CheckinActivity.class).putExtra(CheckinActivity.EXTRA_OPEN,"manage").putExtra(EXTRA_MANAGE,true)));
        row(T.s("禁忌卡"),T.s("禁忌内容与惩罚时长"),()->startActivity(new Intent(this,CheckinActivity.class).putExtra(CheckinActivity.EXTRA_OPEN,"taboo").putExtra(EXTRA_MANAGE,true)));
        row(T.s("总结卡"),data.summary(System.currentTimeMillis()).activeLabels()+T.s(" · 周期末必须完成"),()->{category="summary";render();});
    }
    private TextView dynamicHint(LinearLayout box,String tag){
        TextView t=ui.sub("");t.setPadding(0,ui.dp(8),0,ui.dp(10));t.setTag(tag);box.addView(t);return t;
    }
    private void startLinkageRefresh(){
        linkageHandler.removeCallbacks(linkageTick);
        if(!resumed)return;
        refreshRewardSummary();
        refreshLinkageSummary();
        linkageHandler.postDelayed(linkageTick,1000L);
    }
    private void stopLinkageRefresh(){linkageHandler.removeCallbacks(linkageTick);}
    private void refreshRewardSummary(){
        if(rewardRemaining==null)return;
        long now=GuardClock.now(this).getTimeInMillis();
        long until=CardStore.snapshot(this).rewardUntil(now);
        boolean visible=until>now;
        setTextIfChanged(rewardRemaining,visible
                ?T.s("解冻中 · 剩余 ")+FocusPlan.human(until-now)+T.s(" · 到 ")
                    +new SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date(until))+T.s(" 自动结束")
                :"");
        setVisibilityIfChanged(rewardRemaining,visible?View.VISIBLE:View.GONE);
    }
    private void refreshLinkageSummary(){refreshLinkageSummary(GuardClock.now(this));}
    void refreshLinkageSummary(Calendar now){
        if(linkageState==null||linkageElapsed==null||!category.isEmpty())return;
        String reason=StudyGate.holdReason(this,now);
        long origin=StudyGate.learningElapsedSince(this,now);
        LinkageSummary summary=linkageSummary(reason,origin,now.getTimeInMillis());
        setTextIfChanged(linkageState,summary.reason);
        setTextIfChanged(linkageElapsed,summary.elapsed);
        setVisibilityIfChanged(linkageElapsed,summary.elapsed.isEmpty()?View.GONE:View.VISIBLE);
    }
    private static void setTextIfChanged(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private static void setVisibilityIfChanged(View view,int visibility){if(view.getVisibility()!=visibility)view.setVisibility(visibility);}
    static final class LinkageSummary{
        final String reason,elapsed;
        LinkageSummary(String reason,String elapsed){this.reason=reason;this.elapsed=elapsed;}
    }
    static LinkageSummary linkageSummary(String reason,long origin,long now){
        String state=reason==null||reason.trim().isEmpty()?T.s("当前无额外冻结"):reason.trim();
        String elapsed=origin>0L&&now>=origin
                ?T.s("延时冻结已持续 ")+(now==origin?"0 秒":FocusPlan.human(now-origin))+T.s(" · 完成目标后解冻")
                :"";
        return new LinkageSummary(state,elapsed);
    }
    private void checkinSettings(){base(T.s("打卡设置"));
        row(T.s("打卡外观"),T.s("背景图片与亚克力"),()->startActivity(new Intent(this,CheckinActivity.class).putExtra(CheckinActivity.EXTRA_OPEN,"appearance")));
        row(T.s("打卡字体"),T.s("字体选择与导入"),()->startActivity(new Intent(this,CheckinActivity.class).putExtra(CheckinActivity.EXTRA_OPEN,"font")));
        row(T.s("同步设置"),SyncSettings.state(this),()->SyncSettings.show(this));
        row(T.s("上传打卡数据"),T.s("手动上传最新本机台账"),()->{toast(T.s("正在上传"));new Thread(()->{boolean ok=Hub.pushNowSync(this);runOnUiThread(()->toast(ok?T.s("已上传 ")+SyncSettings.at(this)+" · rev "+Hub.ledgerRev(this):(SyncConfig.configured(this)?T.s("上传失败，检查网络后重试"):T.s("云端同步未启用或已暂停，请点「同步设置」"))));},"afs-card-upload").start();});
    }
    private void row(String title,String description,Runnable action){LinearLayout box=card();TextView t=ui.title(title+"  ›");SwissTypography.role(t,SwissTypography.Role.SECTION);box.addView(t);hint(box,description);String[] types={T.s("目标卡"),T.s("禁忌卡"),T.s("记忆卡"),T.s("刷题卡"),T.s("热爱卡"),T.s("总结卡")};for(int i=0;i<types.length;i++)if(types[i].equals(title))CardPalette.apply(box,i,false);box.setOnClickListener(v->{try{action.run();}catch(RuntimeException e){toast(e.getMessage());}});}
    private void love(){
        base(T.s("热爱卡"));Calendar now=GuardClock.now(this);CardData data=CardStore.snapshot(this);
        long nowMs=now.getTimeInMillis();int available=data.availableTickets(nowMs),waiting=data.waitingTickets(nowMs);
        LinearLayout wallet=card();wallet.addView(ui.title(available+T.s(" 张可用券")));
        rewardRemaining=dynamicHint(wallet,"reward_remaining");
        refreshRewardSummary();
        hint(wallet,T.s("每张卡各自攒券；领取满 24 小时后才能使用，用一张即可解冻所有应用的时段冻结和使用限额，持续该卡设定的分钟数。")+(waiting>0?T.s(" 当前待解锁 ")+waiting+T.s(" 张。"):""));
        TextView use=button(body,T.s("使用一张热爱卡"),true,()->useTicket());use.setTag("reward_use");
        button(body,T.s("＋ 创建热爱卡"),true,()->editLove(null));
        int count=0;for(int i=0;i<data.love().length();i++){JSONObject c=data.love().optJSONObject(i);if(c.optBoolean("archived"))continue;count++;
            final String id=c.optString("id");row(c.optString("title"),data.availableTickets(id,nowMs)+T.s(" 张可用 · ")+data.waitingTickets(id,nowMs)+T.s(" 张待解锁 · 每张 ")+c.optInt("minutes")+T.s(" 分钟 · 点击修改"),()->editLove(id));}
        if(count==0)hint(body,T.s("添加一件自己热爱的事，例如练琴、运动或绘画。"));
        button(body,T.s("券明细"),false,()->rewardHistory());button(body,T.s("热爱卡回收站"),false,()->loveTrash());
    }
    private void editLove(String id){
        JSONObject c=id==null?null:CardStore.snapshot(this).find(id);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(ui.dp(24),0,ui.dp(24),0);
        EditText title=new EditText(this);title.setHint(T.s("热爱名称"));title.setText(c==null?"":c.optString("title"));box.addView(title);
        EditText minutes=new EditText(this);minutes.setInputType(2);minutes.setHint(T.s("每张券解冻分钟数 1–60"));minutes.setText(""+(c==null?60:c.optInt("minutes",60)));box.addView(minutes);
        AlertDialog.Builder builder=ThemeStore.dialog(this).setTitle(id==null?T.s("创建热爱卡"):T.s("编辑热爱卡")).setView(box).setPositiveButton(T.s("保存"),null).setNegativeButton(T.s("取消"),null);
        if(id!=null)builder.setNeutralButton(T.s("移入回收站"),(d,w)->ThemeStore.dialog(this).setTitle(T.s("移入回收站？")).setMessage(T.s("已攒的券会保留。")).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("移入"),(a,b)->{CardStore.archive(this,id,true);render();}).show());
        final String cardId=id;
        AlertDialog dialog=builder.create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            int value;try{value=Integer.parseInt(minutes.getText().toString().trim());}catch(NumberFormatException e){toast(T.s("分钟数只能是 1–60"));return;}
            try{CardStore.saveLove(this,cardId,title.getText().toString(),value);dialog.dismiss();render();}catch(RuntimeException e){toast(e.getMessage());}
        }));dialog.show();
    }
    /** 用一张券：选一张有券的卡，扣一张并立刻让守卫重算时段与额度解冻；不检查学习目标。 */
    private void useTicket(){
        long nowMs=GuardClock.now(this).getTimeInMillis();CardData data=CardStore.snapshot(this);
        List<String> ids=new ArrayList<>(),labels=new ArrayList<>();int waiting=0;
        for(int i=0;i<data.love().length();i++){JSONObject c=data.love().optJSONObject(i);if(c==null||c.optBoolean("archived"))continue;
            String id=c.optString("id");int n=data.availableTickets(id,nowMs);waiting+=data.waitingTickets(id,nowMs);if(n<=0)continue;
            ids.add(c.optString("id"));labels.add(c.optString("title")+" · "+n+T.s(" 张 · ")+c.optInt("minutes")+T.s(" 分钟"));}
        if(ids.isEmpty()){toast(waiting>0?T.s("热爱券领取满 24 小时后才能使用"):T.s("还没有可用的热爱券"));return;}
        ThemeStore.dialog(this).setTitle(T.s("用一张热爱券"))
            .setItems(labels.toArray(new String[0]),(d,w)->{
                int value;
                try{value=CardStore.use(this,ids.get(w));}catch(RuntimeException e){toast(e.getMessage());return;}
                if(value<=0){toast(T.s("这张券已用完"));return;}
                Guard.applyAsync(this);
                toast(T.f("已解冻所有应用的时段与使用限额 %d 分钟",value));
                render();
            }).setNegativeButton(T.s("取消"),null).show();
    }
    private void loveTrash(){CardData data=CardStore.snapshot(this);List<String> ids=new ArrayList<>(),names=new ArrayList<>();for(int i=0;i<data.love().length();i++){JSONObject c=data.love().optJSONObject(i);if(c.optBoolean("archived")){ids.add(c.optString("id"));names.add(T.s("恢复「")+c.optString("title")+"」");}}
        if(ids.isEmpty()){toast(T.s("回收站为空"));return;}ThemeStore.dialog(this).setTitle(T.s("热爱卡回收站")).setItems(names.toArray(new String[0]),(d,w)->{CardStore.archive(this,ids.get(w),false);render();}).setNegativeButton(T.s("关闭"),null).show();}
    private void rewardHistory(){
        CardData data=CardStore.snapshot(this);JSONArray earned=data.earned();
        long nowMs=GuardClock.now(this).getTimeInMillis();
        StringBuilder text=new StringBuilder(T.s("可用券 ")+data.availableTickets(nowMs)+T.s(" 张 · 待解锁 ")+data.waitingTickets(nowMs)+T.s(" 张\n"));
        for(int i=earned.length()-1;i>=Math.max(0,earned.length()-30);i--){JSONObject e=earned.optJSONObject(i);
            String state;
            if(e.optBoolean("used"))state=T.s(" · 已使用");
            else if(data.ticketAvailable(e,nowMs))state=T.s(" · 可用");
            else state=T.s(" · 待解锁 · ")+new SimpleDateFormat("M/d HH:mm",T.locale()).format(new Date(data.availableAt(e)));
            text.append("\n").append(e.optString("day")).append(" · ").append(e.optString("title")).append(state);}
        JSONArray uses=data.db.optJSONArray("uses");
        for(int i=uses.length()-1;i>=Math.max(0,uses.length()-10);i--){JSONObject u=uses.optJSONObject(i);
            text.append("\n").append(u.optString("title")).append(" · ").append(u.optInt("minutes")).append(T.s(" 分钟解冻"));}
        ThemeStore.dialog(this).setTitle(T.s("热爱券记录")).setMessage(text.toString()).setPositiveButton(T.s("关闭"),null).show();
    }
    private void summary(){
        base(T.s("总结卡"));CycleSummary summary=CardStore.snapshot(this).summary(System.currentTimeMillis());hint(body,T.s("周期最后一天出现总结卡，20:00 后未完成会延长学习联动冻结。跨周期继续保留待办，完成后解除这项联动。"));
        hint(body,T.s("直接填写周期天数，例如21天、40天。自定义周期可与每周、每月总结同时启用；各周期分别完成，取消周期不会清除已经到期的总结。"));
        String[] labels={T.s("每天总结"),T.s("每周总结"),T.s("每月总结"),T.s("启用自定义天数")},keys={"daily","weekly","monthly","custom"};CheckBox[] choices=new CheckBox[keys.length];
        for(int i=0;i<keys.length;i++){choices[i]=new CheckBox(this);choices[i].setText(labels[i]);choices[i].setTextColor(ui.text());choices[i].setChecked(summary.enabled(keys[i]));}
        CycleSummary custom=summary.settings("custom");JSONObject candidate=custom.queued();final JSONObject queued=candidate!=null&&"custom".equals(candidate.optString("mode"))?candidate:null;boolean hasCustom="custom".equals(custom.mode());
        LinearLayout box=card();box.addView(ui.title(T.s("自定义天数")));hint(box,T.s("每隔你填写的天数完成一次总结。填写天数会自动勾选启用。"));box.addView(choices[3]);
        LinearLayout interval=new LinearLayout(this);interval.setGravity(Gravity.CENTER_VERTICAL);interval.addView(ui.sub(T.s("每")));
        EditText days=new EditText(this);days.setTag("summary_cycle_days");days.setContentDescription(T.s("总结周期天数"));days.setInputType(2);days.setSingleLine(true);days.setGravity(Gravity.CENTER);days.setHint(T.s("例如21或40"));days.setText(queued!=null?String.valueOf(queued.optInt("days")):hasCustom?String.valueOf(custom.days()):"");interval.addView(days,new LinearLayout.LayoutParams(0,-2,1));interval.addView(ui.sub(T.s("天总结一次")));box.addView(interval);hint(box,T.s("周期天数：1–365天，可输入21、40等整数。"));
        hint(box,T.s("周期开始日期"));EditText anchor=new EditText(this);anchor.setTag("summary_cycle_anchor");anchor.setContentDescription(T.s("总结周期开始日期"));anchor.setSingleLine(true);anchor.setHint("yyyy-MM-dd");anchor.setText(queued!=null?queued.optString("anchor"):hasCustom?custom.anchor():CycleSummary.day(System.currentTimeMillis()));box.addView(anchor);
        TextView datePicker=ui.sub(T.s("选择周期首日 ›"));datePicker.setTextColor(ui.accent());datePicker.setPadding(0,ui.dp(12),0,ui.dp(12));box.addView(datePicker);
        datePicker.setOnClickListener(v->{Calendar selectedDate=Calendar.getInstance();try{String[] parts=anchor.getText().toString().split("-");selectedDate.set(Integer.parseInt(parts[0]),Integer.parseInt(parts[1])-1,Integer.parseInt(parts[2]));}catch(RuntimeException ignored){}
            new android.app.DatePickerDialog(this,(picker,y,m,d)->anchor.setText(String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d)),selectedDate.get(Calendar.YEAR),selectedDate.get(Calendar.MONTH),selectedDate.get(Calendar.DAY_OF_MONTH)).show();});
        TextView preview=ui.sub("");preview.setTag("summary_cycle_preview");preview.setPadding(0,ui.dp(8),0,ui.dp(12));box.addView(preview);
        android.text.TextWatcher watch=new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(!days.getText().toString().trim().isEmpty())choices[3].setChecked(true);summaryPreview(days,anchor,preview);}public void afterTextChanged(android.text.Editable e){}};days.addTextChangedListener(watch);anchor.addTextChangedListener(watch);summaryPreview(days,anchor,preview);
        LinearLayout presets=card();presets.addView(ui.title(T.s("同时启用其他周期")));hint(presets,T.s("可选，可与自定义天数叠加。"));for(int i=0;i<3;i++)presets.addView(choices[i]);
        button(body,T.s("保存总结周期"),true,()->{Set<String> selected=new LinkedHashSet<>();for(int i=0;i<keys.length;i++)if(choices[i].isChecked())selected.add(keys[i]);int count=7;if(selected.contains("custom"))try{count=Integer.parseInt(days.getText().toString().trim());}catch(NumberFormatException e){throw new IllegalArgumentException(T.s("周期天数须为 1–365 的整数"));}CardStore.configureSummaries(this,selected,count,anchor.getText().toString().trim());syncGuard();toast(T.s("总结周期已保存"));render();});
        for(CycleSummary.Period pending:summary.allPending(System.currentTimeMillis()))hint(body,pending.cycleLabel+T.s("待完成：")+pending.start+T.s(" 至 ")+pending.through+T.s("。需单独完成这张总结卡。"));
        if(queued!=null)hint(body,T.s("自定义周期修改将在旧总结补完后生效：每 ")+queued.optInt("days")+T.s(" 天，从 ")+queued.optString("anchor")+T.s(" 起算"));
        button(body,T.s("查看总结"),false,()->startActivity(new Intent(this,HistoryActivity.class).putExtra(HistoryActivity.EXTRA_WEEKLY,true)));
    }
    private void summaryPreview(EditText days,EditText anchor,TextView preview){
        String value=days.getText().toString().trim();if(value.isEmpty()){preview.setText(T.s("例如填写21：每21天总结一次；填写40：每40天总结一次。"));return;}
        try{int count=Integer.parseInt(value);if(count<1||count>365)throw new IllegalArgumentException();String date=anchor.getText().toString().trim();SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.US);format.setLenient(false);if(!date.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();Calendar end=Calendar.getInstance();end.setTime(format.parse(date));end.add(Calendar.DAY_OF_MONTH,count-1);preview.setText(T.s("每")+count+T.s("天总结一次\n首个周期：")+date+T.s(" 至 ")+format.format(end.getTime())+T.s("\n此后每")+count+T.s("天重复，旧总结未完成会继续保留。"));}
        catch(Exception error){preview.setText(T.s("请输入1–365的整数天数和有效的开始日期。"));}
    }
    private void syncGuard(){final android.content.Context c=getApplicationContext();new Thread(()->{try{Guard.apply(c);}catch(RuntimeException ignored){ }},"afs-card-settings").start();}
    public static String minutes(long millis){return String.format(Locale.US,T.s("%.1f 分钟"),Math.max(0,millis)/60000.0);}
}
