package com.afs.guard;

import hct.Hct;

/** Neutral paper/ink surfaces with HCT accents; saved theme colors remain independent. */
public final class AppearancePalette {
    public int bg,surface,surface2,line,text,text2,text3,accent,onAccent,selection,glassTint;
    public AppearancePalette(AppearanceConfig c,boolean dark,int systemSeed){
        int seed=c.selectedSeed()==0?systemSeed:c.selectedSeed();Hct hct=Hct.fromInt(seed);
        boolean mono="monochrome".equals(c.style),soft="soft".equals(c.style);
        double hue=hct.getHue(),chroma=hct.getChroma();
        double primaryChroma=mono?0:soft?36:chroma;
        // Paper and ink surfaces; the selected theme color belongs to actions and content.
        bg=dark?(c.pureBlack?0xFF000000:0xFF141415):0xFFFAFAF9;
        surface=dark?0xFF1C1C1E:0xFFFFFFFF;
        surface2=dark?0xFF272729:0xFFF1F1F0;
        line=dark?0xFF343436:0xFFE3E3E1;
        text=dark?0xFFEDEDED:0xFF202120;
        text2=dark?0xFFB1B1B5:0xFF555653;
        text3=dark?0xFF95959B:0xFF70716D;
        selection=surface2;glassTint=surface;
        accent=mono?(dark?text:0xFF202120):Hct.from(hue,Math.min(primaryChroma,64),dark?80:40).toInt();
        onAccent=dark?0xFF141415:0xFFFFFFFF;
    }

}
