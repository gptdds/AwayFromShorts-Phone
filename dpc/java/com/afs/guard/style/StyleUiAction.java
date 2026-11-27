package com.afs.guard.style;

/** The same action protocol is consumed by every style's settings presentation. */
public final class StyleUiAction {
    public enum Kind { SELECT_STYLE, SELECT_QUALITY }
    public final Kind kind;
    public final String value;
    public StyleUiAction(Kind kind,String value){
        if(kind==null)throw new IllegalArgumentException("Missing action kind");
        this.kind=kind;this.value=value;
    }
}
