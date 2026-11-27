package com.afs.guard.style;

import android.content.Context;

/** Composition root only. Business stores and screens never import concrete renderers. */
public final class BundledStyles {
    private BundledStyles(){}
    public static void register(){
        StyleRegistry.register(new RendererFactory(){
            public String id(){return "paper";}
            public String displayName(Context c){return com.afs.guard.T.s("简约风格");}
            public StyleRenderer create(){return new PaperStyle();}
        });
        StyleRegistry.register(new RendererFactory(){
            public String id(){return "liquidglass";}
            public String displayName(Context c){return com.afs.guard.T.s("液态玻璃");}
            public StyleRenderer create(){return new LiquidGlassStyle();}
        });
        StyleRegistry.register(new RendererFactory(){
            public String id(){return "spatial3d";}
            public String displayName(Context c){return com.afs.guard.T.s("空间立体");}
            public StyleRenderer create(){return new SpatialStyle();}
        });
        StyleRegistry.register(new RendererFactory(){
            public String id(){return "fluidgradient";}
            public String displayName(Context c){return com.afs.guard.T.s("流体渐变");}
            public StyleRenderer create(){return new FluidGradientStyle();}
        });
        StyleRegistry.registerCapability("liquidglass",com.afs.style.api.NavigationPainter.class,
                ()->new com.afs.style.liquidglass.GlassNavigationPainter());
    }
}
