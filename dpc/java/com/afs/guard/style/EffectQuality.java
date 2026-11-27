package com.afs.guard.style;

/** Shared render budget; selected from real measurements plus user preferences. */
public enum EffectQuality {
    HIGH,MEDIUM,LOW,REDUCED_MOTION;
    public boolean allowShaders(){return this==HIGH||this==MEDIUM;}
    public boolean allowHeavyBlur(){return this==HIGH;}
    public boolean allowParticles(){return this==HIGH||this==MEDIUM;}
    public boolean allowMotion(){return this!=REDUCED_MOTION;}
}
