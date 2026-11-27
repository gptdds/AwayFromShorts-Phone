package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Shared recall scheduling and daily extraction for memory cards and quiz questions. */
public final class LearningRules {
    public static final int DEFAULT_COUNT=20, DEFAULT_NEW_LIMIT=10, MAX_COUNT=1000;
    private LearningRules() { }

    /** Same three-button policy, including vague -> 15 minute relearning and local-day reviews. */
    public static MemoryScheduler.State next(JSONObject item,int rating,long now,double retention,int maxInterval) {
        MemoryScheduler.State n=MemoryScheduler.next(MemoryData.state(item),rating,now,retention,maxInterval);
        if(rating==2 && n.state==MemoryScheduler.REVIEW) {
            n.state=MemoryScheduler.RELEARNING;n.step=0;n.due=now+900000L;
        }
        if(n.state==MemoryScheduler.REVIEW) {
            Calendar day=Calendar.getInstance();day.setTimeInMillis(now);
            day.add(Calendar.DAY_OF_MONTH,(int)((n.due-now)/MemoryScheduler.DAY));
            day.set(Calendar.HOUR_OF_DAY,0);day.set(Calendar.MINUTE,0);day.set(Calendar.SECOND,0);day.set(Calendar.MILLISECOND,0);
            n.due=day.getTimeInMillis();
        }
        return n;
    }
    public static void apply(JSONObject item,MemoryScheduler.State n) {
        MemoryData.put(item,"stability",n.stability);MemoryData.put(item,"difficulty",n.difficulty);
        MemoryData.put(item,"state",n.state);MemoryData.put(item,"step",n.step);
        MemoryData.put(item,"reps",n.reps);MemoryData.put(item,"lapses",n.lapses);
        MemoryData.put(item,"last",n.last);MemoryData.put(item,"due",n.due);
    }
    public static long endOfDay(long now) {
        Calendar end=Calendar.getInstance();end.setTimeInMillis(now);
        end.set(Calendar.HOUR_OF_DAY,23);end.set(Calendar.MINUTE,59);end.set(Calendar.SECOND,59);end.set(Calendar.MILLISECOND,999);
        return end.getTimeInMillis();
    }
    private static double risk(JSONObject item,long now) {
        if(item.optInt("state",MemoryScheduler.LEARNING)!=MemoryScheduler.REVIEW)return 0;
        return MemoryScheduler.retrievability(item.optDouble("stability",0),item.optLong("last",0),now);
    }
    public static List<JSONObject> reviews(List<JSONObject> items,long now) {
        List<JSONObject> out=new ArrayList<>();long end=endOfDay(now);
        for(JSONObject item:items)if(item.optInt("reps")>0 && item.optLong("due")<=end)out.add(item);
        Collections.sort(out,(a,b)->{
            int c=Double.compare(risk(a,now),risk(b,now));
            return c==0?Long.compare(a.optLong("due"),b.optLong("due")):c;
        });
        return out;
    }
    public static List<JSONObject> fresh(List<JSONObject> items) {
        List<JSONObject> out=new ArrayList<>();
        for(JSONObject item:items)if(item.optInt("reps")<=0)out.add(item);
        return out;
    }
    public static boolean finished(JSONObject item,Set<String> seen) {
        return seen.contains(item.optString("id")) && item.optInt("state")==MemoryScheduler.REVIEW;
    }
    public static boolean waiting(JSONObject item,long now) {
        return item.optInt("reps")>0 && item.optInt("state")!=MemoryScheduler.REVIEW && item.optLong("due")>now;
    }
    public static Set<String> strings(JSONArray a) {
        Set<String> out=new HashSet<>();if(a!=null)for(int i=0;i<a.length();i++)out.add(a.optString(i));return out;
    }
    /** Due reviews first (forgetting risk), then unseen new items in import order. */
    public static JSONArray choose(List<JSONObject> items,Set<String> seen,Set<String> newSeen,int count,int newLimit,long now) {
        List<String> ids=new ArrayList<>();List<JSONObject> reviews=reviews(items,now);
        // Already-started learning steps survive settings changes, even if the new cap is smaller.
        for(JSONObject item:reviews)if(seen.contains(item.optString("id"))&&!finished(item,seen))ids.add(item.optString("id"));
        int room=Math.max(0,count-seen.size());
        for(JSONObject item:reviews) {
            if(room<=0)break;String id=item.optString("id");if(seen.contains(id))continue;
            ids.add(id);room--;
        }
        int freshRoom=Math.min(Math.max(0,newLimit-newSeen.size()),room);
        for(JSONObject item:fresh(items)) {
            if(freshRoom<=0)break;
            String id=item.optString("id");if(seen.contains(id))continue;
            ids.add(id);freshRoom--;
        }
        return new JSONArray(ids);
    }
    /** A ready same-day consolidation takes priority; future minute steps never block other items. */
    public static JSONObject next(List<JSONObject> items,JSONArray ids,Set<String> seen,long now) {
        Map<String,JSONObject> byId=new HashMap<>();for(JSONObject item:items)byId.put(item.optString("id"),item);
        JSONObject first=null;
        if(ids!=null)for(int i=0;i<ids.length();i++) {
            JSONObject item=byId.get(ids.optString(i));
            if(item==null || finished(item,seen) || waiting(item,now))continue;
            if(seen.contains(item.optString("id")))return item;
            if(first==null)first=item;
        }
        return first;
    }
}
