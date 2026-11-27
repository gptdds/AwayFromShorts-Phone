package com.afs.guard;

import org.json.JSONObject;
import java.util.*;

/** Snapshots are cumulative within a calendar bucket, never increments. */
final class ScreenUsageData {
    private final Map<String,Map<String,Long>> days=new TreeMap<>(),months=new TreeMap<>(),years=new TreeMap<>();
    final Map<String,String> labels=new HashMap<>();
    ScreenUsageData(){}
    ScreenUsageData(String raw)throws Exception{
        JSONObject root=new JSONObject(raw);
        if(root.optInt("version")!=1)throw new IllegalArgumentException("usage archive version");
        read(root.optJSONObject("days"),days,"\\d{4}-\\d{2}-\\d{2}");
        read(root.optJSONObject("months"),months,"\\d{4}-\\d{2}");
        read(root.optJSONObject("years"),years,"\\d{4}");
        // Earlier preview assigned OEM buckets to their start date. Rebuild
        // these day/month views from calendar boundaries and retain year history.
        if(root.optInt("calendar",0)!=2){days.clear();months.clear();}
        JSONObject names=root.optJSONObject("labels");
        if(names!=null)for(Iterator<String> it=names.keys();it.hasNext();){String p=it.next();labels.put(p,names.optString(p,p));}
    }
    private static void read(JSONObject json,Map<String,Map<String,Long>> out,String pattern)throws Exception{
        if(json==null)return;
        for(Iterator<String> it=json.keys();it.hasNext();){String key=it.next();
            if(!key.matches(pattern))throw new IllegalArgumentException("usage bucket");
            JSONObject row=json.getJSONObject(key);Map<String,Long> values=new HashMap<>();
            for(Iterator<String> pkgs=row.keys();pkgs.hasNext();){String p=pkgs.next();long v=row.getLong(p);if(v<0)throw new IllegalArgumentException("negative usage");if(v>0)values.put(p,v);}
            out.put(key,values);
        }
    }
    void sample(String kind,String key,Map<String,Long> values){
        Map<String,Map<String,Long>> target="day".equals(kind)?days:"month".equals(kind)?months:years;
        Map<String,Long> previous=target.get(key);if(previous==null){previous=new HashMap<>();target.put(key,previous);}
        maximum(previous,values);
    }
    private static void maximum(Map<String,Long> into,Map<String,Long> from){
        for(Map.Entry<String,Long> e:from.entrySet())if(e.getValue()>0)into.put(e.getKey(),Math.max(into.containsKey(e.getKey())?into.get(e.getKey()):0,e.getValue()));
    }
    private static void add(Map<String,Long> into,Map<String,Long> from){
        for(Map.Entry<String,Long> e:from.entrySet())into.put(e.getKey(),Math.addExact(into.containsKey(e.getKey())?into.get(e.getKey()):0,e.getValue()));
    }
    Map<String,Long> period(String kind,String key){
        if("day".equals(kind))return new HashMap<>(days.containsKey(key)?days.get(key):Collections.emptyMap());
        if("month".equals(kind)){
            Map<String,Long> out=new HashMap<>();
            for(String d:days.keySet())if(d.startsWith(key+"-"))add(out,days.get(d));
            if(months.containsKey(key))maximum(out,months.get(key));return out;
        }
        Set<String> monthKeys=new TreeSet<>(months.keySet());for(String d:days.keySet())monthKeys.add(d.substring(0,7));
        if("year".equals(kind)){
            Map<String,Long> out=new HashMap<>();for(String m:monthKeys)if(m.startsWith(key+"-"))add(out,period("month",m));
            if(years.containsKey(key))maximum(out,years.get(key));return out;
        }
        Set<String> yearKeys=new TreeSet<>(years.keySet());for(String m:monthKeys)yearKeys.add(m.substring(0,4));
        Map<String,Long> out=new HashMap<>();for(String y:yearKeys)add(out,period("year",y));return out;
    }
    Set<String> yearKeys(){Set<String> out=new TreeSet<>(years.keySet());for(String d:days.keySet())out.add(d.substring(0,4));for(String m:months.keySet())out.add(m.substring(0,4));return out;}
    String firstDay(String prefix){for(String d:days.keySet())if(d.startsWith(prefix))return d;return "";}
    String lastDay(){String last="";for(String d:days.keySet())last=d;return last;}
    String json()throws Exception{
        return new JSONObject().put("version",1).put("calendar",2).put("days",new JSONObject(days)).put("months",new JSONObject(months)).put("years",new JSONObject(years)).put("labels",new JSONObject(labels)).toString();
    }
    static long total(Map<String,Long> values){long n=0;for(long v:values.values())n=Math.addExact(n,v);return n;}
    static int percent(Map<String,Long> values,Set<String> entertainment){long all=total(values),fun=0;for(String p:entertainment)if(values.containsKey(p))fun+=values.get(p);return all<=0?0:(int)Math.round(100.0*fun/all);}
}
