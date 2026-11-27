package com.afs.style.api;

/** Opt-in color metadata. Semantic card hues survive a change of rendering material. */
public interface NativeMaterialSource {
    int materialColor();
    boolean semanticMaterialColor();
}
