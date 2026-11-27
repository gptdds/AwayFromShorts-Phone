package com.afs.guard;

/**
 * 界面风格的整套视觉语言：底色、卡面、描边、圆角、文字与主色。
 *
 * 四种风格不是同一张脸换一点饱和度，而是四套可以一眼分清的成品：
 * 简约——米白纸面、发丝线、近方角；
 * 液态玻璃——冷蓝霜面、大圆角、半透明磨砂描述与玻璃球主色；
 * 空间立体——暖灰水泥底、硬边宽描边、实心块主色；
 * 流体渐变——彩色渐变底、无描边的柔彩卡与渐变主色。
 * 这里只决定颜色和形状，页面结构、数据和交互仍由各页面自己负责。
 */
final class StyleMood {
    private StyleMood() { }

    static String of(String id) {
        if (id == null || id.isEmpty()) return "paper";
        return id;
    }

    static boolean is(String id, String style) {
        return style.equals(of(id));
    }

    static boolean fluid(String id) { return is(id, "fluidgradient"); }
    static boolean glass(String id) { return is(id, "liquidglass"); }
    static boolean spatial(String id) { return is(id, "spatial3d"); }

    static int background(String id, int base, boolean dark) {
        switch (of(id)) {
            case "liquidglass": return 0xFFE0F0F4;
            case "spatial3d": return 0xFFF8EEDB;
            case "fluidgradient": return 0xFFE60058;
            default: return dark ? blend(base, 0xFF1A1917, .72f) : 0xFFF7F4EE;
        }
    }

    static int surface(String id, int base, boolean dark) {
        switch (of(id)) {
            case "liquidglass": return 0xA8F4FCFF;
            case "spatial3d": return 0xFFFFF4D9;
            case "fluidgradient": return 0xBFAD164B;
            default: return dark ? 0xFF201F1D : 0xFFFFFDF9;
        }
    }

    /** 卡片里再嵌一层的底色（进度条槽、内嵌行）。 */
    static int surface2(String id, int fallback, boolean dark) {
        fallback |= 0xFF000000;
        switch (of(id)) {
            case "liquidglass": return 0x66FFFFFF;
            case "spatial3d": return 0xFFF0DEB9;
            case "fluidgradient": return 0x40FFFFFF;
            default: return fallback;
        }
    }

    static int line(String id, int base, boolean dark) {
        switch (of(id)) {
            case "liquidglass": return 0xE6FFFFFF;
            case "spatial3d": return 0xFFD6B780;
            case "fluidgradient": return 0x80FFFFFF;
            default: return dark ? blend(base, 0xFF000000, .18f) : 0xFFDED7CB;
        }
    }

    static float stroke(String id, float base) {
        switch (of(id)) {
            case "liquidglass": return .7f;
            case "spatial3d": return 2f;
            case "fluidgradient": return 1f;
            default: return Math.min(base, .8f);
        }
    }

    static float cardRadius(String id, float base) {
        switch (of(id)) {
            case "liquidglass": return 28f;
            case "spatial3d": return 24f;
            case "fluidgradient": return 34f;
            default: return 10f;
        }
    }

    /** 空间立体走硬边实心块，其余走柔和面。 */
    static boolean flat(String id) {
        return false;
    }

    static int accent(String id, int base) {
        switch (of(id)) {
            case "liquidglass": return 0xFF15718F;
            case "spatial3d": return 0xFF94651E;
            case "fluidgradient": return 0xFFFFED48;
            default: return 0xFF1F6FB2;
        }
    }

    static int text(String id, int base, boolean dark) {
        switch (of(id)) {
            case "liquidglass": return 0xFF234557;
            case "spatial3d": return 0xFF483D2B;
            case "fluidgradient": return 0xFFFFFFFF;
            default: return dark ? 0xFFF2EFE9 : 0xFF201E1B;
        }
    }

    static int text2(String id, int base, boolean dark) {
        switch (of(id)) {
            case "liquidglass": return 0xFF587484;
            case "spatial3d": return 0xFF7A6B54;
            case "fluidgradient": return 0xFFFFE4EC;
            default: return dark ? 0xFFB8B2A7 : 0xFF5D584F;
        }
    }

    static int blend(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int alpha = Math.round(((from >>> 24) * (1f - t)) + ((to >>> 24) * t));
        int r = Math.round(((from >> 16) & 255) * (1f - t) + ((to >> 16) & 255) * t);
        int g = Math.round(((from >> 8) & 255) * (1f - t) + ((to >> 8) & 255) * t);
        int b = Math.round((from & 255) * (1f - t) + (to & 255) * t);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }
}
