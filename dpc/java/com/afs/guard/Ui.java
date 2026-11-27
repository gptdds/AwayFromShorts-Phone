package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared colors and components for the Swiss layout grid.
 * Every skin uses 20dp page margins, 16dp panel insets and semantic text roles.
 * ThemeStore applies the chosen font and SwissTypography supplies size, leading and weight.
 */
public final class Ui {

    public static final String SP = "afs_ui";
    public static final String K_THEME = "theme";     // dark | light | auto

    /* ---------- 深色（默认） ---------- */
    public static final int D_BG = 0xFF090B0F;
    public static final int D_SURFACE = 0xFF171A20;
    public static final int D_SURFACE2 = 0xFF20242C;
    public static final int D_LINE = 0xFF2B3039;
    public static final int D_TEXT = 0xFFF7F9FC;
    public static final int D_TEXT2 = 0xFFAEB6C2;
    public static final int D_TEXT3 = 0xFF737D8B;

    /* ---------- 浅色 ---------- */
    public static final int L_BG = 0xFFF4F7FA;
    public static final int L_SURFACE = 0xFFFFFFFF;
    public static final int L_SURFACE2 = 0xFFEEF2F6;
    public static final int L_LINE = 0xFFE1E6EC;
    public static final int L_TEXT = 0xFF111418;
    public static final int L_TEXT2 = 0xFF5C6570;
    public static final int L_TEXT3 = 0xFF9099A5;

    /* ---------- 强调色（两套主题共用） ---------- */
    public static final int BLUE = 0xFF246BFD;      // Figma 视觉系统主色
    public static final int BLUE_DIM = 0xFF1F4FB0;
    public static final int TEAL = 0xFF12A594;
    public static final int GREEN = 0xFF2FA36B;
    public static final int RUST = 0xFFB4573F;
    public static final int PURPLE = 0xFF7A57D1;
    public static final int RED = 0xFFFF5A6E;
    public static final int AMBER = 0xFFD4971E;

    /* 图标格底色（圆形/圆角方块里的那层） */
    public static final int TILE_BLUE = 0xFF2A5FBF;
    public static final int TILE_TEAL = 0xFF14706A;
    public static final int TILE_GREEN = 0xFF22694A;
    public static final int TILE_RUST = 0xFF9C4B37;
    public static final int TILE_PURPLE = 0xFF5F45A8;

    private final Context ctx;
    public final boolean dark;
    public final AppearancePalette palette;
    public final boolean amap;
    /** 当前界面风格；颜色和形状按它换算，页面结构仍由各页面自己决定。 */
    private final String mood;

    public Ui(Context c) {
        this.ctx = c;
        this.mood = StyleMood.of(com.afs.guard.style.StyleManager.selected(c));
        this.dark = isDark(c);
        this.palette = ThemeStore.palette(c,dark);
        AppearanceConfig config=ThemeStore.load(c);
        this.amap = config.amap();
    }

    /** Bridge from the palette to the pluggable style API; business code reads Ui, not tokens. */
    public com.afs.guard.style.DesignTokens tokens() {
        return new com.afs.guard.style.DesignTokens(bg(),surface(),surface2(),line(),text(),text2(),text3(),
                accent(),onAccent(),palette.selection,dark);
    }

    public com.afs.guard.style.MotionTokens motionTokens() {
        boolean reduced = !MotionPolicy.enabled(ctx);
        if(reduced)return new com.afs.guard.style.MotionTokens(0,0,0);
        return new com.afs.guard.style.MotionTokens(90L,220L,280L);
    }



    /* ==================== 主题 ==================== */

    /** 当前是否深色。auto 跟随系统（夜里深、白天浅）。 */
    public static boolean isDark(Context c) {
        String style=com.afs.guard.style.StyleManager.selected(c);
        if(StyleMood.glass(style)||StyleMood.spatial(style))return false;
        return ThemeStore.dark(c);
    }

    public static void setTheme(Context c, String theme) {
        AppearanceConfig a=ThemeStore.load(c);a.mode=theme;ThemeStore.save(c,a);
    }

    /** 深/浅互切（首页右上角那个按钮用）。 */
    public static void toggle(Context c) {
        setTheme(c, isDark(c) ? "light" : "dark");
    }

    public static String themeName(Context c) {
        return ThemeStore.load(c).mode;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /* ==================== 颜色 ==================== */

    public int bg() {
        return StyleMood.background(mood, palette.bg, dark);
    }

    /** 当前界面风格的稳定 id，供需要按风格单独画形状的页面使用。 */
    public String style() { return mood; }

    public int surface() {
        return StyleMood.surface(mood, palette.surface, dark);
    }

    /** 比卡片再亮一档：卡片里再嵌一层（进度条底、内嵌行）时才用。 */
    public int surface2() {
        return StyleMood.surface2(mood, palette.surface2, dark);
    }

    public int line() {
        return StyleMood.line(mood, palette.line, dark);
    }

    public int text() {
        return StyleMood.text(mood, palette.text, dark);
    }

    public int text2() {
        return StyleMood.text2(mood, palette.text2, dark);
    }

    public int text3() {
        return StyleMood.blend(palette.text3, text2(), .55f);
    }

    /** 深色下用亮一点的蓝，浅色下用标准蓝，保证各自对比度。 */
    public int accent() {
        return StyleMood.accent(mood, palette.accent);
    }
    public int onAccent(){return "paper".equals(mood)?palette.onAccent:StyleMood.fluid(mood)?0xFF6D4128:0xFFFFFFFF;}

    public int green() {
        return dark ? 0xFF3DDC84 : 0xFF12A150;
    }

    public int red() {
        return dark ? 0xFFFF7B72 : 0xFFD93A3A;
    }

    /* ==================== 尺寸 ==================== */

    public int dp(float v) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()) + 0.5f);
    }

    /** Blend two opaque colours without pulling AndroidX into the native build. */
    int mix(int from, int to, float amount) {
        float keep = 1f - Math.max(0f, Math.min(1f, amount));
        int r = Math.round(((from >> 16) & 255) * keep + ((to >> 16) & 255) * (1f - keep));
        int g = Math.round(((from >> 8) & 255) * keep + ((to >> 8) & 255) * (1f - keep));
        int b = Math.round((from & 255) * keep + (to & 255) * (1f - keep));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /* ==================== 构件 ==================== */

    public GradientDrawable round(int color, float radiusDp) {
        boolean material = !"paper".equals(mood) && color == surface() && radiusDp >= 10;
        GradientDrawable g = material
                ? new ReferenceMaterial(mood,color,dp(StyleMood.cardRadius(mood,radiusDp)),
                        ctx.getResources().getDisplayMetrics().density)
                : new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        if (!material) g.setColor(color);
        float radius=radiusDp>=10&&radiusDp<90?StyleMood.cardRadius(mood,radiusDp):radiusDp;
        g.setCornerRadius(dp(radius));
        if(amap&&color==surface()&&radiusDp>=16)g.setStroke(Math.max(1,dp(.5f)),line());
        if("fluidgradient".equals(mood)&&color==surface()&&radiusDp>=16)
            g.setStroke(Math.max(1,dp(1.2f)),StyleMood.blend(accent(),green(),.35f));
        return g;
    }

    public GradientDrawable round(int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = round(color, radiusDp);
        if (strokeColor != 0) {
            g.setStroke(dp(strokeDp), strokeColor);
        }
        return g;
    }

    public void bg(View v, int color, float radiusDp) {
        v.setBackground(round(color, radiusDp));
    }

    /** 按钮圆角：遵循当前控件设置。 */
    public float button(float baseDp) {
        return baseDp;
    }

    /** 一张标准悬浮卡：22dp 圆角、18dp 内边距和极轻的轮廓与阴影。 */
    public LinearLayout card() {
        return card(12f, 16f);
    }

    public LinearLayout card(float radiusDp, float padDp) {
        LinearLayout box = new LinearLayout(ctx);
        box.setTag(R.id.visual_card, Boolean.TRUE);
        box.setOrientation(LinearLayout.VERTICAL);
        int edge = amap ? line() : mix(line(), surface(), dark ? .28f : .48f);
        float stroke = StyleMood.stroke(mood, amap ? .5f : .7f);
        if (StyleMood.flat(mood)) {
            box.setBackground(round(surface(), radiusDp,
                    StyleMood.blend(line(), accent(), dark ? .22f : .16f), Math.max(1f, stroke)));
        } else {
            box.setBackground(round(surface(), radiusDp, edge, stroke));
        }
        int p = dp(padDp);
        box.setPadding(p, p, p, p);
        box.setElevation(0);
        box.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        return box;
    }

    /**
     * Figma 的底色不是一块死色：在不打扰文字的前提下，把主色和暖色各掺入 2–4%。
     * 每个页面只创建一个小型渐变 drawable，不增加常驻动画或模糊开销。
     */
    public void page(View view){
        view.setTag(R.id.visual_page,Boolean.TRUE);
        view.setBackgroundColor(bg());
        StyleSurface.apply(view,this,mood);
    }

    /** 卡片之间统一留 10dp，看起来才像一组。 */
    public LinearLayout.LayoutParams gap(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    /** 大标题（Voyage 的「晚上好」那一档）。 */
    public TextView title(String s) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(28);
        tv.setTextColor(text());
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLineSpacing(dp(4), 1f);
        return SwissTypography.role(tv,SwissTypography.Role.TITLE);
    }

    /** 次级说明行（日期、副标题）。 */
    public TextView sub(String s) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(text2());
        return SwissTypography.role(tv,SwissTypography.Role.META);
    }

    /** 区块标题：彩色小方块 + 标题 + 右侧动作，一眼看出这是"一组"。 */
    public LinearLayout section(String glyph, int glyphBg, String name, String action,
                                View.OnClickListener onAction) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(new AmapStyle.Icon(ctx,name,text2()),new LinearLayout.LayoutParams(dp(24),dp(24)));

        TextView t = new TextView(ctx);
        t.setText(name);
        t.setTextSize(18);
        t.setTextColor(text());
        t.setTypeface(Typeface.DEFAULT_BOLD);
        SwissTypography.role(t,SwissTypography.Role.SECTION);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        row.addView(t, tlp);

        if (action != null) {
            TextView a = new TextView(ctx);
            a.setText(action);
            a.setTextSize(12);
            a.setTextColor(accent());
            a.setPadding(dp(8), dp(6), 0, dp(6));
            a.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (onAction != null) {
                a.setOnClickListener(onAction);
            }
            row.addView(a);
        }
        return row;
    }

    /** 圆形图标（Voyage 工具格里那种彩色圆）。 */
    public TextView circle(String glyph, int color, float sizeDp, int glyphSize) {
        TextView tv = new TextView(ctx);
        tv.setText(glyph);
        tv.setTextSize(glyphSize);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(round(color, sizeDp / 2f));
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return tv;
    }

    /**
     * 图标格：上层彩色圆、下层两行字。Voyage 的工具格就是这个结构，
     * 比"一整块大按钮"更好扫读 —— 圆形本身就是可点的暗示。
     */
    public LinearLayout tile(String glyph, int circleColor, String label, String hint,
                             View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        boolean spatial = StyleMood.spatial(mood);
        int tileColor = circleColor == TILE_TEAL || circleColor == TILE_GREEN
                ? 0xFFFFDA00 : circleColor == TILE_BLUE ? 0xFF08C8F4 : surface();
        int tileText = spatial && tileColor != surface() ? 0xFF241044 : text();
        int p = dp(6);
        box.setPadding(p, p, p, p);
        box.setMinimumHeight(dp(96));
        box.setElevation(0);
        box.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        if (onClick != null) {
            box.setOnClickListener(onClick);
            TypedValue ripple=new TypedValue();
            if(ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,ripple,true))
                box.setForeground(ctx.getDrawable(ripple.resourceId));
            box.setClipToOutline(!spatial);
        }

        LinearLayout iconWrap = new LinearLayout(ctx);
        iconWrap.setGravity(Gravity.CENTER);
        boolean sculpted = !"paper".equals(mood);
        if(sculpted)iconWrap.setBackground(new ReferenceMaterial(mood,
                spatial?(circleColor==TILE_TEAL?0xFFFFDD43:circleColor==TILE_PURPLE?0xFF50DDED:circleColor==TILE_RUST?0xFFFFF3CD:circleColor==TILE_BLUE?0xFFFF9D72:0xFF6BE4BA):StyleMood.fluid(mood)?0xFFFFDFA0:surface(),dp(17),
                ctx.getResources().getDisplayMetrics().density));
        iconWrap.addView(new AmapStyle.Icon(ctx,label,text()),
                new LinearLayout.LayoutParams(dp(sculpted?36:24),dp(sculpted?36:24)));
        box.addView(iconWrap,new LinearLayout.LayoutParams(dp(sculpted?56:32),dp(sculpted?56:32)));

        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(text());
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("paper".equals(mood)?"sans-serif-medium":"sans-serif",
                "paper".equals(mood)?Typeface.NORMAL:Typeface.BOLD));
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        box.addView(t, tlp);

        if (hint != null && "paper".equals(mood)) {
            TextView h = new TextView(ctx);
            h.setText(hint);
            h.setTextSize(11);
            h.setTextColor(spatial && tileColor != surface() ? 0xFF44235F : text2());
            h.setGravity(Gravity.START);
            h.setMaxLines(2);
            // 英文比中文长，超出宽度时静默裁掉会留下「Cards · rewards ·」这种断尾。
            // 显示省略号至少说明「还有内容」，不至于看起来像 bug。
            h.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(h);
        }
        return box;
    }

    /** 胶囊：标签/状态用，不抢按钮的位置。 */
    public TextView chip(String s, int fg, int fill) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(11);
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(round(soft(fg,.94f), 6f));
        tv.setMinHeight(dp(28));
        tv.setPadding(dp(12), dp(5), dp(12), dp(5));
        return tv;
    }

    /** 把颜色朝页面底色拉近，做出参考稿里那种很淡的色块底。 */
    public int soft(int color, float amount) {
        int base = surface();
        float keep = 1 - Math.max(0, Math.min(1, amount));
        int r = Math.round(((color >> 16) & 255) * keep + ((base >> 16) & 255) * amount);
        int g = Math.round(((color >> 8) & 255) * keep + ((base >> 8) & 255) * amount);
        int b = Math.round((color & 255) * keep + (base & 255) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 右上角的设置按钮：圆角底 + 齿轮。
     *
     * 齿轮是画出来的（{@link GearIcon}），不用字体里的「⚙」—— 那个字符在 ColorOS 上
     * 会走 emoji 字体，颜色和大小都不受控。
     */
    public View settingsButton(int fg, int fill) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(round(fill, 16f, mix(line(),fill,.42f), .7f));
        box.setElevation(0);
        box.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        box.addView(new GearIcon(ctx, fg), new LinearLayout.LayoutParams(dp(19), dp(19)));
        box.setContentDescription(T.s("设置"));
        return box;
    }

    /** 圆角图标按钮（右上角主题切换那种）。 */
    public TextView iconButton(String glyph, int fg, int fill) {
        TextView tv = new TextView(ctx);
        ThemeStore.excludeFont(tv);
        tv.setText(glyph);
        tv.setTextSize(15);
        tv.setTextColor(fg);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(round(fill, 14f, mix(line(),fill,.42f), .7f));
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        return tv;
    }

    /** 水平分隔线：只在同一张卡里拆行时才用，卡片之间留白足够，不用线。 */
    public View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(line());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f)));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }
}
