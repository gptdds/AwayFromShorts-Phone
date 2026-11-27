package com.afs.guard;

import java.util.*;

/** Stable entry IDs; a tool has exactly one home. */
final class ToolShelfModel {
    static final List<String> ALL=Collections.unmodifiableList(Arrays.asList("cards","history","memo","toolbox","todo","usage-stats","pomodoro","mindful","future"));
    private final ArrayList<String> home=new ArrayList<>();
    private final ArrayList<String> box=new ArrayList<>();
    ToolShelfModel(List<String> saved){
        this(saved,null);
    }
    ToolShelfModel(List<String> saved,List<String> savedBox){
        for(String id:saved==null?ALL.subList(0,6):saved)
            if(ALL.contains(id)&&!home.contains(id))home.add(id);
        if(!home.contains("toolbox"))home.add("toolbox");
        if(savedBox!=null)for(String id:savedBox)if(ALL.contains(id)&&!home.contains(id)&&!box.contains(id))box.add(id);
        for(String id:ALL)if(!home.contains(id)&&!box.contains(id))box.add(id);
    }
    List<String> home(){return new ArrayList<>(home);}
    List<String> box(){return new ArrayList<>(box);}
    boolean toBox(String id){return toBox(id,null);}
    boolean toBox(String id,String before){
        if(!ALL.contains(id)||"toolbox".equals(id)||id.equals(before))return false;
        List<String> old=box();boolean removed=home.remove(id);box.remove(id);
        int index=box.indexOf(before);box.add(index<0?box.size():index,id);
        return removed||!old.equals(box);
    }
    boolean toHome(String id,String before){
        if(!ALL.contains(id)||id.equals(before))return false;
        List<String> old=home();home.remove(id);box.remove(id);
        int index=home.indexOf(before);home.add(index<0?home.size():index,id);
        return !home.equals(old);
    }
}
