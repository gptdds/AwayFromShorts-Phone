package com.afs.guard.style;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Open registration point; adding a fourth style must not change core business code. */
public final class StyleRegistry {
    private static final Map<String,RendererFactory> renderers=new LinkedHashMap<>();
    private static final Map<String,Map<Class<?>,java.util.function.Supplier<?>>> capabilities=new LinkedHashMap<>();
    private StyleRegistry(){}
    public static synchronized void register(RendererFactory renderer){
        if(renderer==null||renderer.id()==null||renderer.id().trim().isEmpty())
            throw new IllegalArgumentException("Style factory needs a stable id");
        if(renderers.containsKey(renderer.id()))throw new IllegalArgumentException("Duplicate style: "+renderer.id());
        renderers.put(renderer.id(),renderer);
    }
    public static synchronized List<RendererFactory> all(){
        return Collections.unmodifiableList(new ArrayList<>(renderers.values()));
    }
    public static synchronized RendererFactory find(String id){
        return id==null?null:renderers.get(id);
    }
    /** One catalog for View and Compose capabilities. A capability is created only when selected. */
    public static synchronized <T> void registerCapability(String id,Class<T> contract,java.util.function.Supplier<? extends T> factory){
        if(!renderers.containsKey(id)||contract==null||factory==null)throw new IllegalArgumentException("Unknown style or capability");
        Map<Class<?>,java.util.function.Supplier<?>> entries=capabilities.get(id);
        if(entries==null){entries=new LinkedHashMap<>();capabilities.put(id,entries);}
        if(entries.containsKey(contract))throw new IllegalArgumentException("Duplicate capability: "+id);
        entries.put(contract,factory);
    }
    public static synchronized <T> boolean hasCapability(String id,Class<T> contract){
        return capabilities.containsKey(id)&&capabilities.get(id).containsKey(contract);
    }
    public static <T> T createCapability(String id,Class<T> contract){
        java.util.function.Supplier<?> factory;
        synchronized(StyleRegistry.class){Map<Class<?>,java.util.function.Supplier<?>> entries=capabilities.get(id);factory=entries==null?null:entries.get(contract);}
        return factory==null?null:contract.cast(factory.get());
    }
}
