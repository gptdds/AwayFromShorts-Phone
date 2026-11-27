package com.afs.guard;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Quiz adapter for the same daily recall/extraction rules used by MemoryFlashcards. */
public final class QuizReview {
    private QuizReview() { }
    private static JSONObject root(QuizData data) {
        JSONObject r=data.db.optJSONObject("review");
        if(r==null){r=new JSONObject();QuizData.put(data.db,"review",r);}return r;
    }
    public static JSONObject settings(QuizData data) {
        JSONObject r=data.db.optJSONObject("review"),s=r==null?null:r.optJSONObject("settings");
        return s==null?new JSONObject():s;
    }
    public static int count(QuizData data){return Math.max(1,Math.min(1000,settings(data).optInt("count",LearningRules.DEFAULT_COUNT)));}
    public static int newLimit(QuizData data){return Math.max(0,Math.min(1000,settings(data).optInt("newLimit",LearningRules.DEFAULT_NEW_LIMIT)));}
    public static JSONArray subjects(QuizData data) {
        JSONArray a=settings(data).optJSONArray("subjects");return a==null?new JSONArray(data.subjects()):a;
    }
    public static void configure(QuizData data,JSONArray subjects,int count,int newLimit,double retention,int maxInterval,long now) {
        if(count<1||count>1000||newLimit<0||newLimit>1000||!Double.isFinite(retention)||retention<.70||retention>.97||maxInterval<1||maxInterval>36500)
            throw new IllegalArgumentException(T.s("复习设置无效"));
        JSONArray chosen=new JSONArray();Set<String> unique=new HashSet<>();
        for(int i=0;i<subjects.length();i++){String s=subjects.optString(i);if(!data.subjects().contains(s))throw new IllegalArgumentException(T.s("请选择仍在使用的学科"));if(unique.add(s))chosen.put(s);}
        if(chosen.length()==0)throw new IllegalArgumentException(T.s("至少选择一个学科"));
        JSONObject s=new JSONObject();QuizData.put(s,"subjects",chosen);QuizData.put(s,"count",count);QuizData.put(s,"newLimit",newLimit);
        QuizData.put(s,"requestRetention",retention);QuizData.put(s,"maxInterval",maxInterval);
        JSONObject r=root(data);QuizData.put(r,"settings",s);r.remove("session");session(data,now);
    }
    private static JSONObject day(QuizData data,long now) {
        JSONObject r=root(data),d=r.optJSONObject("day");String date=MemoryData.date(now);
        if(d==null||!date.equals(d.optString("date"))) {
            d=new JSONObject();QuizData.put(d,"date",date);QuizData.put(d,"seen",new JSONArray());QuizData.put(d,"newSeen",new JSONArray());
            QuizData.put(r,"day",d);r.remove("session");
        }
        return d;
    }
    /** Projection only: preserve old answer statistics and initialize FSRS on the next real rating. */
    static List<JSONObject> items(QuizData data) {
        List<JSONObject> out=new ArrayList<>();Set<String> subjects=LearningRules.strings(subjects(data));
        for(int i=0;i<data.questions().length();i++) {
            JSONObject q=data.questions().optJSONObject(i);if(q==null||data.deleted(q.optString("subject"))||!subjects.contains(q.optString("subject")))continue;
            String id=q.optString("id");JSONObject item=MemoryData.copy(data.progress(id));QuizData.put(item,"id",id);
            if(!item.has("reps")&&item.optInt("attempts")>0) {
                // Legacy practiced questions are overdue reviews, not brand new admissions.
                // No stability or successful recall is invented from their historical attempt count.
                QuizData.put(item,"reps",1);QuizData.put(item,"state",MemoryScheduler.RELEARNING);QuizData.put(item,"due",0);
            }
            out.add(item);
        }
        return out;
    }
    public static JSONObject session(QuizData data,long now) {
        JSONObject d=day(data,now),r=root(data),s=r.optJSONObject("session");List<JSONObject> items=items(data);
        if(s!=null&&MemoryData.date(now).equals(s.optString("date"))&&s.optJSONArray("ids")!=null) {
            if(s.optJSONArray("ids").length()>0)return s;
            if(LearningRules.reviews(items,now).isEmpty()&&LearningRules.fresh(items).isEmpty())return s;
        }
        JSONArray chosen=LearningRules.choose(items,LearningRules.strings(d.optJSONArray("seen")),LearningRules.strings(d.optJSONArray("newSeen")),count(data),newLimit(data),now);
        s=new JSONObject();QuizData.put(s,"date",MemoryData.date(now));QuizData.put(s,"ids",chosen);QuizData.put(r,"session",s);return s;
    }
    public static JSONObject next(QuizData data,long now) {
        JSONObject item=LearningRules.next(items(data),session(data,now).optJSONArray("ids"),LearningRules.strings(day(data,now).optJSONArray("seen")),now);
        return item==null?null:data.question(item.optString("id"));
    }
    public static MemoryScheduler.State preview(QuizData data,String id,int rating,long now) {
        if(data.question(id)==null||data.deleted(data.question(id).optString("subject")))throw new IllegalArgumentException(T.s("题目不存在"));
        JSONObject cfg=settings(data);return LearningRules.next(data.progress(id),rating,now,MemoryData.retention(cfg),MemoryData.maxInterval(cfg));
    }
    public static void requirePending(QuizData data,String id,long now,String date) {
        if(!MemoryData.date(now).equals(date))throw new IllegalArgumentException(T.s("日期已更新，请从今日刷题继续"));
        JSONObject next=next(data,now);if(next==null||!id.equals(next.optString("id")))throw new IllegalArgumentException(T.s("请按当前复习顺序作答"));
    }
    public static void complete(QuizData data,String id,boolean wasNew,long now) {
        JSONObject d=day(data,now);JSONArray seen=d.optJSONArray("seen"),newSeen=d.optJSONArray("newSeen");
        if(!MemoryData.contains(seen,id))seen.put(id);if(wasNew&&!MemoryData.contains(newSeen,id))newSeen.put(id);
    }
    public static final class Progress {
        public int total,done,processed;public long nextDue;
    }
    public static Progress progress(QuizData data,long now) {
        Progress out=new Progress();Set<String> seen=LearningRules.strings(day(data,now).optJSONArray("seen"));
        JSONArray ids=session(data,now).optJSONArray("ids");Map<String,JSONObject> items=new HashMap<>();for(JSONObject item:items(data))items.put(item.optString("id"),item);
        for(int i=0;i<ids.length();i++) {
            JSONObject item=items.get(ids.optString(i));if(item==null)continue;out.total++;
            if(LearningRules.finished(item,seen))out.done++;
            else if(LearningRules.waiting(item,now)&&(out.nextDue==0||item.optLong("due")<out.nextDue))out.nextDue=item.optLong("due");
        }
        out.processed=seen.size();return out;
    }
}
