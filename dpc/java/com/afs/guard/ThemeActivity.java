package com.afs.guard;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Complete mobile appearance settings, including a live preview and searchable system fonts. */
public final class ThemeActivity extends ThemedActivity {
    private Ui ui;
    private AppearanceConfig config;
    private ScrollView scroll;
    private LinearLayout body,root;
    private ThemePreviewView preview;
    private ThemeColorPicker picker;
    private Integer editing;
    private ListView fontList;
    private EditText search;
    private final List<String> installed=new ArrayList<>();
    private final List<FontRow> fontRows=new ArrayList<>();
    private FontAdapter fontAdapter;
    private String query="";
    private boolean fontsReady;
    private LinearLayout skinOptions;
    private java.util.List<com.afs.guard.style.RendererFactory> skinStyles;
    private final java.util.List<LinearLayout> skinOptionViews=new ArrayList<>();
    private TextView qualityValue;
    private final Runnable refreshVisualOptions=()->{
        refreshStyleSelection();
        refreshQualityLabel();
    };
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener visualChanged=(prefs,key)->{
        if("style_id".equals(key)||"effect_quality".equals(key))refreshVisualOptions.run();
    };
    @Override protected void onPostResume(){
        super.onPostResume();
        getSharedPreferences(Ui.SP,0).registerOnSharedPreferenceChangeListener(visualChanged);
        com.afs.guard.style.EffectQualityManager.addListener(refreshVisualOptions);
        refreshVisualOptions.run();
    }
    @Override protected void onPause(){
        getSharedPreferences(Ui.SP,0).unregisterOnSharedPreferenceChangeListener(visualChanged);
        com.afs.guard.style.EffectQualityManager.removeListener(refreshVisualOptions);
        super.onPause();
    }

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);ui=new Ui(this);config=ThemeStore.load(this);FloatingNav.fullscreen(this);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(ui.bg());
        root.setPadding(0,ui.dp(24),0,0);setContentView(root);
        if("fonts".equals(getIntent().getStringExtra("page"))){buildFonts(saved);return;}
        header(T.s("主题"),null);
        scroll=new ScrollView(this);scroll.setClipToPadding(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        // 换风格后本页要跟着换成新风格：整棵内容树铺风格底，再挂返回键重建，滚动位置不动。
        StyleSurface.apply(scroll,ui,com.afs.guard.style.StyleManager.selected(this));
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(0,ui.dp(12),0,ui.dp(32));scroll.addView(body);
        buildThemePage(saved);
    }

    /** 主题页的全部内容；重建（改配置）时也复用这一份，避免整页 Activity recreate 抽一下。 */
    private void buildThemePage(Bundle saved){
        skins();
        LinearLayout holder=card();holder.setGravity(Gravity.CENTER);holder.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(24));
        preview=new ThemePreviewView(this,config,null,true);if(saved!=null)preview.selected(saved.getInt("preview",0));
        holder.addView(preview,new LinearLayout.LayoutParams(ui.dp(168),ui.dp(300)));
        choices("☼",T.s("主题模式"),new String[]{T.s("自动"),T.s("浅色"),T.s("深色")},new String[]{"auto","light","dark"},config.mode,"mode");
        choices("▣",T.s("纯黑模式"),new String[]{T.s("标准"),T.s("纯黑")},new String[]{"false","true"},String.valueOf(config.pureBlack),"black");
        colors();
        choices("▱",T.s("底栏"),new String[]{T.s("悬浮"),T.s("固定")},new String[]{"true","false"},String.valueOf(config.floating),"dock");
        section("A",T.s("字体"),null,null);
        LinearLayout fontCard=card();LinearLayout fontRow=row();fontRow.setPadding(ui.dp(16),ui.dp(16),ui.dp(16),ui.dp(16));
        TextView aa=text("Aa",24,ui.text());ThemeStore.excludeFont(aa);aa.setTypeface(ThemeStore.font(this,config.font,0));fontRow.addView(aa);
        TextView family=text(fontName(config.font),15,ui.text2());LinearLayout.LayoutParams familyLp=new LinearLayout.LayoutParams(0,-2,1);familyLp.leftMargin=ui.dp(12);fontRow.addView(family,familyLp);fontRow.addView(text("›",25,ui.text2()));
        fontRow.setContentDescription(T.s("字体，")+fontName(config.font));fontRow.setOnClickListener(v->startActivity(new Intent(this,ThemeActivity.class).putExtra("page","fonts")));fontCard.addView(fontRow);
        scaling();
        if(saved!=null){
            int y=saved.getInt("scroll",0);boolean bottom=saved.getBoolean("bottom",false);
            restoreScrollOnPreDraw(y,bottom);
            if(saved.containsKey("picker")){Integer old=saved.containsKey("editing")?saved.getInt("editing"):null;int draft=saved.getInt("picker");scroll.post(()->openColor(old,draft));}
        }
    }

    /** 在当前 Activity 里重建主题页内容（不销毁窗口）：改颜色/玻璃/底栏这类设置不再闪一下。 */
    private void rebuildInPlace(){
        if(scroll==null||body==null)return;
        int y=scroll.getScrollY();
        boolean bottom=body.getHeight()-scroll.getHeight()-y<ui.dp(64);
        int selected=preview==null?0:preview.selected();
        config=ThemeStore.load(this);ui=new Ui(this);
        root.removeAllViews();
        root.setBackgroundColor(ui.bg());
        header(T.s("主题"),null);
        scroll=new ScrollView(this);scroll.setClipToPadding(false);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(0,ui.dp(12),0,ui.dp(32));scroll.addView(body);
        buildThemePage(null);
        if(preview!=null)preview.selected(selected);
        restoreScrollOnPreDraw(y,bottom);
        ThemeStore.typography(root);
    }

    /** 滚动位置在首帧之前恢复，避免重建后先闪一帧顶部再跳回去。 */
    private void restoreScrollOnPreDraw(final int y,final boolean bottom){
        if(scroll==null)return;
        scroll.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            public boolean onPreDraw(){
                android.view.ViewTreeObserver observer=scroll.getViewTreeObserver();
                if(observer.isAlive())observer.removeOnPreDrawListener(this);
                scroll.scrollTo(0,bottom?body.getHeight():y);
                return true;
            }
        });
    }

    private void recreateToApply(AppearanceConfig next){
        ThemeStore.save(this,next);
        overridePendingTransition(0,0);
        recreate();
    }

    /** 换风格：先记下已按新配置生效，再把本页整体换成新风格的配色与形状。 */
    private void markAppliedAndRecreate(){
        markAppearanceApplied();
        overridePendingTransition(0,0);
        recreate();
    }

    private TextView text(String value,float size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private void header(String title,Runnable action){
        LinearLayout bar=row();bar.setPadding(ui.dp(20),0,ui.dp(20),ui.dp(16));
        View back=new ThemeGlyphView(this,T.s("返回"),ui.text());back.setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10));back.setBackground(ui.round(ui.surface(),99));back.setContentDescription(T.s("返回"));back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);back.setOnClickListener(v->finish());bar.addView(back,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
        TextView heading=SwissTypography.role(text(title,28,ui.text()),SwissTypography.Role.TITLE);heading.setTypeface(Typeface.DEFAULT_BOLD);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=ui.dp(12);bar.addView(heading,lp);
        if(action!=null){View button=new ThemeGlyphView(this,T.s("搜索"),ui.text());button.setContentDescription(T.s("搜索字体"));button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);button.setOnClickListener(v->action.run());bar.addView(button,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));}root.addView(bar);
    }
    private void section(String glyph,String title,String action,Runnable clicked){
        LinearLayout r=row();r.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(12));
        View icon=new ThemeGlyphView(this,title,ui.text2());r.addView(icon,new LinearLayout.LayoutParams(ui.dp(32),ui.dp(24)));
        TextView t=text(title,17,ui.text2());t.setTypeface(Typeface.DEFAULT_BOLD);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null){TextView a=text(action,19,ui.text2());a.setContentDescription(T.s("重置")+title);a.setPadding(ui.dp(10),ui.dp(4),ui.dp(4),ui.dp(4));a.setOnClickListener(v->clicked.run());r.addView(a);}body.addView(r);
    }
    private LinearLayout card(){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setBackground(ui.round(ui.surface(),22));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(ui.dp(20),0,ui.dp(20),0);body.addView(c,lp);return c;
    }
    private void skins(){
        section("",T.s("界面风格"),null,null);LinearLayout options=new LinearLayout(this);options.setOrientation(LinearLayout.VERTICAL);options.setPadding(ui.dp(20),0,ui.dp(20),ui.dp(16));body.addView(options);
        java.util.List<com.afs.guard.style.RendererFactory> styles=com.afs.guard.style.StyleRegistry.all();
        skinOptions=options;skinStyles=styles;skinOptionViews.clear();
        String selected=com.afs.guard.style.StyleManager.selected(this);
        LinearLayout optionRow=null;
        for(int i=0;i<styles.size();i++){
            if(i%2==0){optionRow=row();LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,-2);if(i>0)rowLp.topMargin=ui.dp(8);options.addView(optionRow,rowLp);}
            final com.afs.guard.style.RendererFactory style=styles.get(i);
            final String key=style.id();boolean chosen=key.equals(selected);
            LinearLayout option=new LinearLayout(this);option.setOrientation(LinearLayout.VERTICAL);option.setGravity(Gravity.CENTER);option.setPadding(ui.dp(10),ui.dp(18),ui.dp(10),ui.dp(18));
            option.setBackground(ui.round(ui.surface(),18,chosen?ui.accent():ui.line(),chosen?1.8f:.5f));
            TextView name=text(style.displayName(this)+(chosen?" ✓":""),13,chosen?ui.accent():ui.text());name.setTypeface(Typeface.DEFAULT_BOLD);name.setGravity(Gravity.CENTER);name.setSingleLine(false);option.addView(name,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);if(i%2==0)lp.rightMargin=ui.dp(8);optionRow.addView(option,lp);skinOptionViews.add(option);
            option.setSelected(chosen);option.setContentDescription(T.s("界面风格：")+style.displayName(this)+(chosen?T.s("，已选"):""));option.setOnClickListener(v->{
                if(key.equals(com.afs.guard.style.StyleManager.selected(ThemeActivity.this)))return;
                com.afs.guard.style.StyleManager.dispatch(ThemeActivity.this,new com.afs.guard.style.StyleUiAction(
                        com.afs.guard.style.StyleUiAction.Kind.SELECT_STYLE,key));
                // 风格换的是整页颜色与形状，不只是选项描边：整页重建一次，别让本页停在旧风格上。
                markAppliedAndRecreate();
            });
        }
        quality();
        Intent previewIntent=new Intent().setClassName(getPackageName(),"com.afs.guard.SharedHomeActivity");
        try{
            getPackageManager().getActivityInfo(previewIntent.getComponent(),0);
            TextView open=text(T.s("查看风格效果"),14,ui.accent());
            open.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(16));
            open.setMinimumHeight(ui.dp(48));
            open.setOnClickListener(v->startActivity(previewIntent));
            body.addView(open,new LinearLayout.LayoutParams(-1,-2));
        }catch(android.content.pm.PackageManager.NameNotFoundException absent){}
    }
    private void refreshStyleSelection(){
        if(skinOptions==null||skinStyles==null)return;
        String key=com.afs.guard.style.StyleManager.selected(this);
        if(com.afs.guard.style.StyleRegistry.find(key)==null)key="paper";
        for(int n=0;n<skinOptionViews.size()&&n<skinStyles.size();n++){
            LinearLayout item=skinOptionViews.get(n);
            com.afs.guard.style.RendererFactory descriptor=skinStyles.get(n);
            boolean on=descriptor.id().equals(key);
            item.setSelected(on);item.setBackground(ui.round(ui.surface(),18,on?ui.accent():ui.line(),on?1.8f:.5f));
            TextView label=(TextView)item.getChildAt(0);
            label.setText(descriptor.displayName(this)+(on?" ✓":""));label.setTextColor(on?ui.accent():ui.text());
            item.setContentDescription(T.s("界面风格：")+descriptor.displayName(this)+(on?T.s("，已选"):""));
        }
    }
    private void refreshQualityLabel(){
        if(qualityValue==null)return;
        String[] labels={T.s("跟随系统"),T.s("高"),T.s("中"),T.s("低"),T.s("减少动效")};
        String[] values={"auto","high","medium","low","reduced"};
        String selected=com.afs.guard.style.EffectQualityManager.preference(this);int at=0;
        for(int i=0;i<values.length;i++)if(values[i].equals(selected))at=i;
        qualityValue.setText(labels[at]+" · "+com.afs.guard.style.EffectQualityManager.describe(this)+"  ⌄");
        qualityValue.setContentDescription(T.s("特效档位，")+labels[at]+T.s("，当前")+com.afs.guard.style.EffectQualityManager.describe(this));
    }
    private void quality(){
        String[] labels={T.s("跟随系统"),T.s("高"),T.s("中"),T.s("低"),T.s("减少动效")};
        String[] values={"auto","high","medium","low","reduced"};
        String selected=com.afs.guard.style.EffectQualityManager.preference(this);
        int at=0;for(int i=0;i<values.length;i++)if(values[i].equals(selected))at=i;
        LinearLayout line=row();line.setMinimumHeight(ui.dp(52));line.setPadding(ui.dp(20),0,ui.dp(20),ui.dp(8));body.addView(line);
        TextView title=text(T.s("特效档位"),15,ui.text2());title.setTypeface(Typeface.DEFAULT_BOLD);line.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView value=text(labels[at]+" · "+com.afs.guard.style.EffectQualityManager.describe(this)+"  ⌄",13,ui.text2());
        qualityValue=value;
        value.setContentDescription(T.s("特效档位，")+labels[at]+T.s("，当前")+com.afs.guard.style.EffectQualityManager.describe(this));line.addView(value);
        line.setOnClickListener(v->{
            int current=0;String pref=com.afs.guard.style.EffectQualityManager.preference(this);
            for(int i=0;i<values.length;i++)if(values[i].equals(pref))current=i;
            ThemeStore.dialog(this).setTitle(T.s("特效档位")).setSingleChoiceItems(labels,current,(dialog,which)->{
            dialog.dismiss();com.afs.guard.style.StyleManager.dispatch(ThemeActivity.this,new com.afs.guard.style.StyleUiAction(
                    com.afs.guard.style.StyleUiAction.Kind.SELECT_QUALITY,values[which]));
            value.setText(labels[which]+" · "+com.afs.guard.style.EffectQualityManager.describe(this)+"  ⌄");
        }).setNegativeButton(T.s("取消"),null).show();});
    }
    private void separator(LinearLayout parent){View line=new View(this);line.setBackgroundColor(ui.line());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,ui.dp(.5f)));lp.leftMargin=ui.dp(70);lp.rightMargin=ui.dp(14);parent.addView(line,lp);}
    private void choices(String glyph,String title,String[] labels,String[] values,String selected,String key){
        section(glyph,title,null,null);LinearLayout box=card();
        for(int i=0;i<labels.length;i++){
            if(i>0)separator(box);final String value=values[i];boolean chosen=value.equals(selected);
            LinearLayout r=row();r.setMinimumHeight(ui.dp(56));r.setPadding(ui.dp(16),ui.dp(10),ui.dp(16),ui.dp(10));
            AppearanceConfig thumb=config.copy();Boolean dark=null;
            if("mode".equals(key))dark="dark".equals(value);
            if("black".equals(key)){dark=true;thumb.pureBlack=Boolean.parseBoolean(value);}
            if("dock".equals(key))thumb.floating=Boolean.parseBoolean(value);
            if("animation".equals(key))thumb.animation=value;
            if("glass".equals(key)){thumb.floating=true;thumb.glass=value;}
            ThemePreviewView pictogram=new ThemePreviewView(this,thumb,dark,false);
            pictogram.thumbnail();
            if("mode".equals(key)&&"auto".equals(value))pictogram.split(true);
            r.addView(pictogram,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(34)));
            TextView label=text(labels[i],17,ui.text());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=ui.dp(12);r.addView(label,lp);
            if(chosen){TextView check=text("✓",24,ui.accent());check.setContentDescription(T.s("已选"));r.addView(check);}
            r.setSelected(chosen);r.setContentDescription(title+T.s("：")+labels[i]+(chosen?T.s("，已选"):""));
            r.setOnClickListener(v->{AppearanceConfig next=ThemeStore.load(this);switch(key){case "mode":next.mode=value;break;case "black":next.pureBlack=Boolean.parseBoolean(value);break;case "dock":next.floating=Boolean.parseBoolean(value);break;case "animation":next.animation=value;break;case "glass":next.glass=value;break;}if("mode".equals(key))recreateToApply(next);else save(next);});box.addView(r);
        }
    }
    private void save(AppearanceConfig next){ThemeStore.save(this,next);rebuildInPlace();markAppearanceApplied();}
    private boolean defaultColors(){if(config.selectedSeed()!=config.defaultSeed()||!"vivid".equals(config.style)||config.colors.size()!=AppearanceConfig.PRESETS.length)return false;for(int i=0;i<AppearanceConfig.PRESETS.length;i++)if(config.colors.get(i)!=AppearanceConfig.PRESETS[i])return false;return true;}
    private void colors(){
        section("◌",T.s("主题色彩"),defaultColors()?null:"↺",()->ThemeStore.dialog(this).setTitle(T.s("重置主题色彩？")).setMessage(T.s("恢复默认色板、默认主题色和鲜明风格。")).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("重置"),(d,w)->{AppearanceConfig next=ThemeStore.load(this);next.resetColors();save(next);}).show());
        LinearLayout box=card();box.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));GridLayout grid=new GridLayout(this);
        int available=getResources().getDisplayMetrics().widthPixels-ui.dp(60),columns=Math.max(1,available/ui.dp(50));grid.setColumnCount(columns);
        List<Integer> values=new ArrayList<>();values.add(0);if(config.amap()&&config.selectedSeed()==AppearanceConfig.AMAP_COLOR&&!config.colors.contains(AppearanceConfig.AMAP_COLOR))values.add(AppearanceConfig.AMAP_COLOR);values.addAll(config.colors);
        for(int i=0;i<=values.size();i++){
            boolean add=i==values.size();int color=add?0:values.get(i);Swatch swatch=new Swatch(color,add);
            GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=available/columns;lp.height=ui.dp(50);grid.addView(swatch,lp);
            if(add)swatch.setOnClickListener(v->openColor(null,config.selectedSeed()==0?ThemeStore.systemSeed(this):config.selectedSeed()));
            else {
                swatch.setOnClickListener(v->{AppearanceConfig next=ThemeStore.load(this);next.selectColor(color);save(next);});
                if(color!=0)swatch.setOnLongClickListener(v->{PopupMenu menu=new PopupMenu(this,v);menu.getMenu().add(T.s("编辑")).setOnMenuItemClickListener(m->{openColor(color,color);return true;});menu.getMenu().add(T.s("删除")).setOnMenuItemClickListener(m->{ThemeStore.dialog(this).setTitle(T.s("删除这个颜色？")).setMessage("#"+AppearanceConfig.hex(color)).setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("删除"),(d,w)->{AppearanceConfig next=ThemeStore.load(this);next.removeColor(color);save(next);}).show();return true;});menu.show();return true;});
            }
        }
        box.addView(grid);
        LinearLayout style=row();style.setMinimumHeight(ui.dp(52));style.setPadding(ui.dp(6),0,ui.dp(6),0);style.addView(text(T.s("配色风格"),16,ui.text()),new LinearLayout.LayoutParams(0,-2,1));
        String[] names={T.s("鲜明"),T.s("氛围"),T.s("柔和"),T.s("单色")},keys={"vivid","tinted","soft","monochrome"};
        int selected=Arrays.asList(keys).indexOf(config.style);style.addView(text(names[Math.max(0,selected)]+"  ⌄",15,ui.text2()));
        style.setContentDescription(T.s("配色风格，")+names[Math.max(0,selected)]);style.setOnClickListener(v->ThemeStore.dialog(this).setTitle(T.s("配色风格")).setSingleChoiceItems(names,selected,(dialog,which)->{dialog.dismiss();AppearanceConfig next=ThemeStore.load(this);next.style=keys[which];save(next);}).setNegativeButton(T.s("取消"),null).show());box.addView(style);
    }
    private void openColor(Integer old,int initial){editing=old;picker=ThemeColorPicker.show(this,initial,color->{AppearanceConfig next=ThemeStore.load(this);if(old==null)next.addColor(color);else next.editColor(old,color);save(next);});}
    private void scaling(){
        section("Tᴛ",T.s("文本缩放"),config.customScale&&config.scale!=1?"↺":null,()->{AppearanceConfig next=ThemeStore.load(this);next.scale=1;recreateToApply(next);});
        LinearLayout segmented=row();segmented.setPadding(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3));segmented.setBackground(ui.round(ui.surface(),99));
        LinearLayout.LayoutParams groupLp=new LinearLayout.LayoutParams(-1,ui.dp(44));groupLp.setMargins(ui.dp(20),0,ui.dp(20),ui.dp(8));body.addView(segmented,groupLp);
            for(int i=0;i<2;i++){final boolean custom=i==1;TextView item=text(custom?T.s("自定义"):T.s("跟随系统"),15,custom==config.customScale?ui.onAccent():ui.text2());item.setGravity(Gravity.CENTER);if(custom==config.customScale)item.setBackground(ui.round(ui.accent(),99));segmented.addView(item,new LinearLayout.LayoutParams(0,-1,1));item.setOnClickListener(v->{AppearanceConfig next=ThemeStore.load(this);next.customScale=custom;recreateToApply(next);});}
        LinearLayout box=card();box.setPadding(ui.dp(16),ui.dp(16),ui.dp(16),ui.dp(12));
        float current=config.customScale?config.scale:Math.max(.8f,Math.min(1.4f,ThemeStore.systemScale(this)));
        LinearLayout line=row();TextView aa=SwissTypography.role(text("Aa",24,ui.text()),SwissTypography.Role.KEEP);line.addView(aa,new LinearLayout.LayoutParams(0,-2,1));TextView percent=text(Math.round(current*100)+"%",14,ui.text2());percent.setPadding(ui.dp(10),ui.dp(4),ui.dp(10),ui.dp(4));percent.setBackground(ui.round(ui.surface2(),99));line.addView(percent);box.addView(line);
        TextView sample=SwissTypography.role(text(T.s("应用内的文字将以这个大小显示"),15,ui.text2()),SwissTypography.Role.KEEP);box.addView(sample,ui.gap(16));
        LinearLayout sliderRow=row();sliderRow.addView(text("A",12,ui.text2()));SeekBar slider=new SeekBar(this);slider.setMax(12);slider.setProgress(Math.round((current-.8f)/.05f));slider.setEnabled(config.customScale);slider.setContentDescription(T.s("文字大小，")+Math.round(current*100)+"%");slider.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accent()));slider.setThumbTintList(android.content.res.ColorStateList.valueOf(ui.accent()));sliderRow.addView(slider,new LinearLayout.LayoutParams(0,ui.dp(44),1));sliderRow.addView(text("A",23,ui.text2()));box.addView(sliderRow);
        final float contextScale=getResources().getConfiguration().fontScale;
        ThemeColorPicker.styleSlider(slider,ui);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            private boolean tracking;
            private void persist(SeekBar s){AppearanceConfig next=ThemeStore.load(ThemeActivity.this);next.scale=.8f+s.getProgress()*.05f;recreateToApply(next);}
            public void onStartTrackingTouch(SeekBar s){tracking=true;}
            public void onProgressChanged(SeekBar s,int value,boolean user){float scale=.8f+value*.05f;percent.setText(Math.round(scale*100)+"%");aa.setTextSize(24*scale/contextScale);sample.setTextSize(15*scale/contextScale);s.setContentDescription(T.s("文字大小，")+Math.round(scale*100)+"%");if(user&&!tracking)persist(s);}
            public void onStopTrackingTouch(SeekBar s){tracking=false;persist(s);}
        });
    }
    private final class Swatch extends View {
        private final int color;private final boolean add;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Swatch(int color,boolean add){super(ThemeActivity.this);this.color=color;this.add=add;setContentDescription(add?T.s("添加颜色"):color==0?T.s("主题色跟随系统"):"#"+AppearanceConfig.hex(color));setSelected(!add&&config.selectedSeed()==color);setFocusable(true);}
        @Override protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f;int fill=add?ui.surface2():color==0?ThemeStore.systemSeed(ThemeActivity.this):color;
            paint.setStyle(Paint.Style.FILL);paint.setColor(fill);c.drawCircle(x,y,ui.dp(16),paint);
            if(!add&&config.selectedSeed()==color){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(ui.dp(2.5f));c.drawCircle(x,y,ui.dp(20.5f),paint);paint.setStyle(Paint.Style.FILL);paint.setColor(hct.Hct.fromInt(fill).getTone()<60?0xFFFFFFFF:0xFF111111);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(ui.dp(17));c.drawText("✓",x,y+ui.dp(6),paint);}
            if(add){paint.setColor(ui.text2());paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.DEFAULT);paint.setTextSize(ui.dp(25));c.drawText("+",x,y+ui.dp(8),paint);}
            else if(color==0){paint.setColor(ui.surface());c.drawCircle(x+ui.dp(13),y+ui.dp(13),ui.dp(7),paint);paint.setColor(ui.text2());paint.setTextSize(ui.dp(10));paint.setTextAlign(Paint.Align.CENTER);c.drawText("◐",x+ui.dp(13),y+ui.dp(16),paint);}
        }
    }
    private static String fontName(String font){return font.isEmpty()?T.s("默认"):"asset_xiaowei".equals(font)?T.s("小薇宋体"):"asset_mashan".equals(font)?T.s("马善政楷体"):font;}
    private void buildFonts(Bundle saved){
        header(T.s("字体"),()->{search.setVisibility(View.VISIBLE);search.post(()->{search.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search,InputMethodManager.SHOW_IMPLICIT);});});
        search=new EditText(this);search.setHint(T.s("搜索字体"));search.setSingleLine();
        search.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_FILTER|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH||(event!=null&&event.getKeyCode()==android.view.KeyEvent.KEYCODE_ENTER)){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);return true;}return false;});
        search.setTextColor(ui.text());search.setHintTextColor(ui.text3());search.setBackground(ui.round(ui.surface(),16));search.setPadding(ui.dp(16),ui.dp(10),ui.dp(16),ui.dp(10));search.setContentDescription(T.s("搜索字体"));
        LinearLayout.LayoutParams searchLp=new LinearLayout.LayoutParams(-1,-2);searchLp.setMargins(ui.dp(20),0,ui.dp(20),ui.dp(12));root.addView(search,searchLp);search.setVisibility(View.GONE);
        fontList=new ListView(this);fontList.setDivider(null);fontList.setPadding(ui.dp(20),0,ui.dp(20),ui.dp(24));fontList.setClipToPadding(false);root.addView(fontList,new LinearLayout.LayoutParams(-1,0,1));fontAdapter=new FontAdapter();fontList.setAdapter(fontAdapter);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();filterFonts();}public void afterTextChanged(Editable e){}});
        if(saved!=null&&!saved.getString("query","").isEmpty()){search.setVisibility(View.VISIBLE);search.setText(saved.getString("query"));}
        filterFonts();new Thread(()->{List<String> names=ThemeStore.installedFonts();runOnUiThread(()->{if(isFinishing()||isDestroyed())return;installed.addAll(names);fontsReady=true;filterFonts();});},"afs-font-catalog").start();
        fontList.setOnItemClickListener((parent,view,index,id)->{FontRow item=fontRows.get(index);if(item.header)return;AppearanceConfig next=ThemeStore.load(this);next.font=item.family;ThemeStore.save(this,next);finish();});
    }
    private void filterFonts(){
        fontRows.clear();String needle=query.trim().toLowerCase(Locale.ROOT);
        if(needle.isEmpty()||T.s("默认 default").contains(needle))fontRows.add(new FontRow(T.s("默认"),"",false));
        addFontGroup(T.s("内置字体"),Arrays.asList("asset_xiaowei","asset_mashan"),needle);
        addFontGroup(T.s("已安装字体"),installed,needle);
        if(!fontsReady)fontRows.add(new FontRow(T.s("正在读取字体…"),"",true));
        else if(fontRows.isEmpty())fontRows.add(new FontRow(T.s("未找到字体"),"",true));
        fontAdapter.notifyDataSetChanged();
    }
    private void addFontGroup(String heading,List<String> families,String needle){List<FontRow> group=new ArrayList<>();for(String family:families){String name=fontName(family);if(name.toLowerCase(Locale.ROOT).contains(needle)||family.toLowerCase(Locale.ROOT).contains(needle))group.add(new FontRow(name,family,false));}if(!group.isEmpty()){fontRows.add(new FontRow(heading,"",true));fontRows.addAll(group);}}
    private static final class FontRow {final String name,family;final boolean header;FontRow(String n,String f,boolean h){name=n;family=f;header=h;}}
    private final class FontAdapter extends BaseAdapter {
        public int getCount(){return fontRows.size();}public Object getItem(int p){return fontRows.get(p);}public long getItemId(int p){return p;}public boolean isEnabled(int p){return !fontRows.get(p).header;}
        public View getView(int index,View convert,ViewGroup parent){FontRow item=fontRows.get(index);if(item.header){TextView heading=text(item.name,13,ui.text2());heading.setPadding(ui.dp(14),ui.dp(20),ui.dp(14),ui.dp(10));return heading;}
            LinearLayout r=row();r.setPadding(ui.dp(16),ui.dp(13),ui.dp(16),ui.dp(13));r.setBackground(ui.round(ui.surface(),14));LinearLayout words=new LinearLayout(ThemeActivity.this);words.setOrientation(LinearLayout.VERTICAL);TextView name=text(item.name,17,ui.text());name.setTypeface(ThemeStore.font(ThemeActivity.this,item.family,0));ThemeStore.excludeFont(name);words.addView(name);TextView sample=text(T.s("Aa · 目标打卡 123"),12,ui.text2());sample.setTypeface(ThemeStore.font(ThemeActivity.this,item.family,0));ThemeStore.excludeFont(sample);words.addView(sample);r.addView(words,new LinearLayout.LayoutParams(0,-2,1));if(config.font.equals(item.family))r.addView(text("✓",24,ui.accent()));return r;}
    }
    @Override protected void onSaveInstanceState(Bundle out){if(scroll!=null){out.putInt("scroll",scroll.getScrollY());out.putBoolean("bottom",body.getHeight()-scroll.getHeight()-scroll.getScrollY()<ui.dp(64));}if(preview!=null)out.putInt("preview",preview.selected());out.putString("query",query);if(picker!=null&&picker.isShowing()){out.putInt("picker",picker.color());if(editing!=null)out.putInt("editing",editing);}super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){if(picker!=null&&picker.isShowing())picker.dismiss();super.onDestroy();}
}
