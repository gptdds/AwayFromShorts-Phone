package com.afs.guard.style;

/** Visual vocabulary shared by every renderer; business code never reads these directly. */
public final class DesignTokens {
    public final int bg,surface,surface2,line,text,text2,text3,accent,onAccent,selection;
    public final boolean dark;
    public DesignTokens(int bg,int surface,int surface2,int line,int text,int text2,int text3,
                        int accent,int onAccent,int selection,boolean dark){
        this.bg=bg;this.surface=surface;this.surface2=surface2;this.line=line;this.text=text;
        this.text2=text2;this.text3=text3;this.accent=accent;this.onAccent=onAccent;
        this.selection=selection;this.dark=dark;
    }
}
