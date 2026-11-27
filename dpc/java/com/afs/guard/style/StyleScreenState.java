package com.afs.guard.style;

/** Immutable settings state, independent of renderer instances and Android views. */
public final class StyleScreenState {
    public final String selectedId,qualityPreference;
    public StyleScreenState(String selectedId,String qualityPreference){
        this.selectedId=selectedId;this.qualityPreference=qualityPreference;
    }
    public StyleScreenState reduce(StyleUiAction action){
        if(action==null||action.value==null||action.value.trim().isEmpty())throw new IllegalArgumentException("Empty style action");
        return action.kind==StyleUiAction.Kind.SELECT_STYLE
                ?new StyleScreenState(action.value,qualityPreference)
                :new StyleScreenState(selectedId,action.value);
    }
}
