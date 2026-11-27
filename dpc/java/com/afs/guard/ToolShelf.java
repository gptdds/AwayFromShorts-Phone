package com.afs.guard;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.JSONArray;

/** Same-window folder keeps native drag events inside the owning window. */
final class ToolShelf {
    private final Activity activity;
    private final Ui ui;
    private final LinearLayout grid;
    private final FrameLayout host;
    private ToolShelfModel model;
    private String snapshot;
    private FrameLayout folder;
    private Drag active;
    private static final class Drag {String id; View source; boolean fromBox,committed;}
    ToolShelf(Activity a,Ui u,LinearLayout g,FrameLayout h){activity=a;ui=u;grid=g;host=h;refresh();}
    private String read(){return activity.getSharedPreferences("afs_tool_shelf",0).getString("home_order",null);}
    void refresh(){
        String value=read(),boxValue=activity.getSharedPreferences("afs_tool_shelf",0).getString("box_order",null);boolean enabled=visible("usage-stats");String key=value+":"+boxValue+":"+enabled;
        if(key.equals(snapshot))return;
        ArrayList<String> ids=null;
        if(value!=null)try{JSONArray json=new JSONArray(value);ids=new ArrayList<>();for(int i=0;i<json.length();i++)ids.add(json.getString(i));}catch(Exception ignored){}
        ArrayList<String> boxIds=null;
        if(boxValue!=null)try{JSONArray json=new JSONArray(boxValue);boxIds=new ArrayList<>();for(int i=0;i<json.length();i++)boxIds.add(json.getString(i));}catch(Exception ignored){}
        model=new ToolShelfModel(ids,boxIds);snapshot=key;render();
    }
    static String title(String id){
        switch(id){case "cards":return T.s("卡管理");case "history":return T.s("打卡历史");case "memo":return T.s("备忘录");case "toolbox":return T.s("工具箱");case "todo":return T.s("待办卡");case "usage-stats":return T.s("使用时间统计");case "pomodoro":return T.s("番茄钟");case "mindful":return T.s("正念空间");default:return T.s("未来倒计时");}
    }
    static void launch(Activity a,String id){
        Class<?> target;
        switch(id){case "cards":target=CardManagementActivity.class;break;case "history":target=HistoryActivity.class;break;case "memo":target=MemoActivity.class;break;case "todo":target=TodoActivity.class;break;case "usage-stats":target=StatsActivity.class;break;default:target=ToolboxActivity.class;}
        Intent intent=new Intent(a,target);
        if(Arrays.asList("pomodoro","mindful","future").contains(id))intent.putExtra("tool",id);
        a.startActivity(intent);
    }
    private boolean visible(String id){return !"usage-stats".equals(id)||com.afs.guard.plugins.BuiltInPluginStore.enabled(activity,"usage-stats");}
    private View tile(String id,boolean box){
        int index=ToolShelfModel.ALL.indexOf(id);
        int[] colors={Ui.TILE_TEAL,Ui.TILE_PURPLE,Ui.TILE_RUST,Ui.TILE_BLUE,Ui.TILE_GREEN};
        LinearLayout tile=ui.tile("",colors[index%colors.length],title(id),null,v->{if("toolbox".equals(id))open();else launch(activity,id);});
        tile.setContentDescription(title(id));
        // 工具箱本身也要能在工具区里长按拖动换位置；只是不能把它放进自己的箱子里。
        tile.setOnLongClickListener(v->{
            Drag drag=new Drag();drag.id=id;drag.source=v;drag.fromBox=box;active=drag;
            ClipData clip=ClipData.newPlainText("afs-tool",id);
            boolean started=android.os.Build.VERSION.SDK_INT>=24?v.startDragAndDrop(clip,new View.DragShadowBuilder(v),drag,0):v.startDrag(clip,new View.DragShadowBuilder(v),drag,0);
            if(started){v.setAlpha(.4f);}
            else active=null;
            return started;
        });
        tile.setOnDragListener((v,e)->drop(v,e,box||"toolbox".equals(id),"toolbox".equals(id)?null:id));
        return tile;
    }
    private boolean drop(View target,DragEvent e,boolean box,String before){
        if(!(e.getLocalState() instanceof Drag)||e.getLocalState()!=active)return false;
        Drag drag=active;
        switch(e.getAction()){
            case DragEvent.ACTION_DRAG_STARTED:return true;
            case DragEvent.ACTION_DRAG_ENTERED:target.setAlpha(.65f);return true;
            case DragEvent.ACTION_DRAG_EXITED:target.setAlpha(1);return true;
            case DragEvent.ACTION_DROP:
                if(drag.committed)return true;
                boolean changed=box?model.toBox(drag.id,before):model.toHome(drag.id,before);
                if(changed){drag.committed=true;activity.getSharedPreferences("afs_tool_shelf",0).edit().putString("home_order",new JSONArray(model.home()).toString()).putString("box_order",new JSONArray(model.box()).toString()).apply();}
                return changed;
            case DragEvent.ACTION_DRAG_ENDED:
                target.setAlpha(1);drag.source.setAlpha(1);
                // Run after Android has delivered ENDED to every registered target.
                grid.post(()->finishDrag(drag));return true;
            default:return true;
        }
    }
    private void finishDrag(Drag drag){
        if(active!=drag)return;active=null;
        if(drag.committed){boolean reopen=folder!=null&&model.box().contains(drag.id);close();snapshot=null;refresh();if(reopen)open();}
        else if(folder!=null)folder.setVisibility(View.VISIBLE);
    }
    private void render(){
        grid.removeAllViews();ArrayList<String> shown=new ArrayList<>();for(String id:model.home())if(visible(id))shown.add(id);
        rows(grid,shown,false);grid.setMinimumHeight(ui.dp(108));grid.setOnDragListener((v,e)->drop(v,e,false,null));
    }
    private void rows(LinearLayout parent,List<String> ids,boolean box){
        for(int start=0;start<ids.size();start+=3){
            LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);
            for(int col=0;col<3;col++){
                View cell=start+col<ids.size()?tile(ids.get(start+col),box):new View(activity);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ui.dp(108),1);if(col<2)lp.rightMargin=ui.dp(12);row.addView(cell,lp);
            }
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=ui.dp(12);parent.addView(row,lp);
        }
    }
    void open(){
        if(folder!=null)return;
        folder=new FrameLayout(activity);folder.setBackgroundColor(0x66000000);folder.setOnClickListener(v->close());
        folder.setOnDragListener((v,e)->drop(v,e,false,null));
        TextView destination=text(T.s("工具区"),18,ui.text());destination.setGravity(Gravity.CENTER);destination.setBackground(ui.round(ui.surface(),28));
        FrameLayout.LayoutParams destLp=new FrameLayout.LayoutParams(-1,ui.dp(96),Gravity.TOP);destLp.setMargins(ui.dp(20),ui.dp(24),ui.dp(20),0);folder.addView(destination,destLp);
        destination.setOnDragListener((v,e)->drop(v,e,false,null));
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(ui.dp(20),ui.dp(20),ui.dp(20),ui.dp(20));panel.setBackground(ui.round(ui.surface(),28));panel.setOnClickListener(v->{});panel.setOnDragListener((v,e)->drop(v,e,true,null));
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=text(title("toolbox"),20,ui.text());heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView done=text("×",28,ui.text());done.setContentDescription(T.s("关闭"));done.setPadding(ui.dp(16),0,ui.dp(8),ui.dp(12));done.setOnClickListener(v->close());heading.addView(done);panel.addView(heading);
        ScrollView scroller=new ScrollView(activity);LinearLayout entries=new LinearLayout(activity);entries.setOrientation(LinearLayout.VERTICAL);scroller.addView(entries);panel.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        ArrayList<String> shown=new ArrayList<>();for(String id:model.box())if(visible(id))shown.add(id);rows(entries,shown,true);
        if(shown.isEmpty()){TextView empty=text(T.s("工具箱为空"),14,ui.text2());empty.setPadding(0,ui.dp(20),0,ui.dp(20));entries.addView(empty);}
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,Math.min(ui.dp(430),(int)(host.getHeight()*.65f)),Gravity.BOTTOM);lp.setMargins(ui.dp(12),ui.dp(24),ui.dp(12),ui.dp(24));folder.addView(panel,lp);host.addView(folder,new FrameLayout.LayoutParams(-1,-1));
    }
    boolean close(){if(folder==null)return false;host.removeView(folder);folder=null;return true;}
    private TextView text(String value,int size,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
}
