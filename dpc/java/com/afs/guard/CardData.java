package com.afs.guard;

import org.json.*;
import java.util.*;

/**
 * 热爱卡账本：每张卡各自攒券，用一张开一段时段冻结豁免，与学习联动无关。
 * 用掉一张券就开一段「时段冻结豁免」窗口，持续该卡配置的分钟数；
 * 额度用尽、学习联动、禁忌罚时仍照常拦（见 Guard.blockReason）。
 *
 * 不再抽奖、不再全局分钟池、不再自动 reconcile 抵扣。
 */
public final class CardData {
    public static final int MAX_REWARD_MINUTES=60;
    /** 每张热爱券领取后需满 24 小时才能兑换。 */
    public static final long CLAIM_COOLDOWN_MS=24L*60L*60L*1000L;
    public final JSONObject db;
    /** 从旧「抽奖分钟池」账本迁移过；CardStore 要把它落盘。 */
    transient boolean migrated;

    public CardData(long now){db=new JSONObject();put(db,"version",1);put(db,"love",new JSONArray());put(db,"earned",new JSONArray());put(db,"uses",new JSONArray());put(db,"summary",new JSONObject());summary(now);}

    public CardData(String raw){try{db=new JSONObject(raw);if(db.optInt("version")!=1||db.optJSONArray("love")==null||db.optJSONArray("earned")==null||db.optJSONArray("uses")==null||db.optJSONObject("summary")==null)throw new Exception();
            migrate();validate();}catch(Exception e){throw new IllegalArgumentException(T.s("卡管理数据无法读取，原文件已保留"),e);}}

    /**
     * 旧账本（1.91.33 及以前）：earned 带 minutes（抽奖分钟），uses 是自动抵扣窗口。
     * 迁移后每条 earned 变成一张可用券（时间改取卡配置），旧自动抵扣窗口整体作废。
     * 判定只认 earned 里的 minutes —— uses 非空不是旧格式特征：
     * 新模型的合法解冻窗口也存 uses，绝不能清（2026-11-09 用户用券被吞的事故）。
     */
    private void migrate(){
        JSONArray earned=db.optJSONArray("earned");boolean legacy=false;
        for(int i=0;i<earned.length();i++){JSONObject e=earned.optJSONObject(i);if(e!=null&&e.has("minutes")){legacy=true;break;}}
        if(!legacy)return;
        for(int i=0;i<earned.length();i++){JSONObject e=earned.optJSONObject(i);if(e==null)continue;e.remove("minutes");if(!e.has("used"))put(e,"used",false);}
        put(db,"uses",new JSONArray());migrated=true;
    }

    private void validate(){
        Set<String> ids=new HashSet<>(),tickets=new HashSet<>(),cardDays=new HashSet<>();
        for(int i=0;i<love().length();i++){JSONObject c=love().optJSONObject(i);if(c==null||c.optString("id").isEmpty()||!ids.add(c.optString("id"))||c.optInt("minutes")<1||c.optInt("minutes")>60||c.optString("title").trim().isEmpty())throw new IllegalArgumentException(T.s("热爱卡配置无效"));}
        for(int i=0;i<earned().length();i++){
            JSONObject e=earned().optJSONObject(i);
            if(e==null||e.optString("id").isEmpty()||!tickets.add(e.optString("id"))||e.optString("card").isEmpty()||!e.optString("day").matches("\\d{4}-\\d{2}-\\d{2}")||e.optLong("at")<=0||!(e.opt("used") instanceof Boolean))throw new IllegalArgumentException(T.s("热爱券记录无效"));
            if(e.has("photo")&&(!(e.opt("photo") instanceof String)||e.optString("photo").trim().isEmpty()))throw new IllegalArgumentException(T.s("热爱券记录无效"));
            if(e.has("photoAt")&&(!(e.opt("photoAt") instanceof Number)||e.optLong("photoAt")<=0L))throw new IllegalArgumentException(T.s("热爱券记录无效"));
            if(!cardDays.add(e.optString("card")+"@"+e.optString("day")))throw new IllegalArgumentException(T.s("同一张卡每天最多攒一张券"));
        }
        JSONArray uses=db.optJSONArray("uses");
        for(int i=0;i<uses.length();i++){JSONObject u=uses.optJSONObject(i);if(u==null||u.optLong("start")<=0||u.optLong("end")<=u.optLong("start")||u.optInt("minutes")<1||u.optInt("minutes")>60)throw new IllegalArgumentException(T.s("热爱券使用记录无效"));}
        if(!db.optJSONObject("summary").has("mode"))throw new IllegalArgumentException(T.s("总结配置无效"));summary(System.currentTimeMillis());
    }

    public CycleSummary summary(long now){return new CycleSummary(db.optJSONObject("summary"),now);}
    public JSONArray love(){return db.optJSONArray("love");}
    public JSONArray earned(){return db.optJSONArray("earned");}
    public JSONObject find(String id){for(int i=0;i<love().length();i++){JSONObject card=love().optJSONObject(i);if(id.equals(card.optString("id")))return card;}return null;}

    public String saveLove(String id,String title,int reward){
        title=title==null?"":title.trim();if(title.isEmpty()||title.length()>80)throw new IllegalArgumentException(T.s("热爱名称须为 1–80 字"));
        if(reward<1||reward>MAX_REWARD_MINUTES)throw new IllegalArgumentException(T.s("每张热爱卡的奖励只能为 1–60 分钟"));
        JSONObject card=id==null?null:find(id);if(id!=null&&card==null)throw new IllegalArgumentException(T.s("热爱卡不存在"));
        if(card==null){card=new JSONObject();id=UUID.randomUUID().toString();put(card,"id",id);love().put(card);}
        put(card,"title",title);put(card,"minutes",reward);put(card,"archived",false);return id;
    }
    public void archive(String id,boolean archived){JSONObject card=find(id);if(card==null)throw new IllegalArgumentException(T.s("热爱卡不存在"));put(card,"archived",archived);}


    /** 今天有没有攒过任意一张券（仅用于界面状态提示）。 */
    public boolean earnedToday(long now){String day=CycleSummary.day(now);for(int i=0;i<earned().length();i++)if(day.equals(earned().optJSONObject(i).optString("day")))return true;return false;}
    /** 这张卡今天攒过了没有 —— 按卡计，不再全局一天一张。 */
    public boolean claimed(String id,long now){String day=CycleSummary.day(now);for(int i=0;i<earned().length();i++){JSONObject e=earned().optJSONObject(i);if(day.equals(e.optString("day"))&&id.equals(e.optString("card")))return true;}return false;}

    /** 完成一次这张热爱：必须带一张已经保存好的照片，时间取卡配置，不再抽奖。 */
    public boolean claim(String id,long now){
        return claim(id,now,null);
    }

    /** 完成一次这张热爱：攒一张券，并记录本次拍照的私有文件路径。 */
    public boolean claim(String id,long now,String photoPath){
        if(photoPath==null||photoPath.trim().isEmpty())throw new IllegalArgumentException(T.s("先拍一张照片才能领取热爱卡"));
        JSONObject card=find(id);if(card==null||card.optBoolean("archived"))throw new IllegalArgumentException(T.s("热爱卡不存在"));
        if(claimed(id,now))return false;
        JSONObject entry=new JSONObject();put(entry,"id",UUID.randomUUID().toString());put(entry,"card",id);
        put(entry,"title",card.optString("title"));put(entry,"day",CycleSummary.day(now));put(entry,"at",now);put(entry,"used",false);
        put(entry,"photo",photoPath.trim());put(entry,"photoAt",now);
        earned().put(entry);return true;
    }

    /** 这张卡攒了几张没用的券。 */
    public int tickets(String cardId){int n=0;for(int i=0;i<earned().length();i++){JSONObject e=earned().optJSONObject(i);if(e!=null&&cardId.equals(e.optString("card"))&&!e.optBoolean("used"))n++;}return n;}
    public int tickets(){int n=0;for(int i=0;i<love().length();i++){JSONObject c=love().optJSONObject(i);if(c!=null&&!c.optBoolean("archived"))n+=tickets(c.optString("id"));}return n;}

    /** 一张券的领取时间加上 24 小时；旧账本没有额外字段时也按领取时间计算。 */
    public long availableAt(JSONObject ticket){
        if(ticket==null)return 0;
        long at=ticket.optLong("at",0);
        if(at<=0)return 0;
        return at>Long.MAX_VALUE-CLAIM_COOLDOWN_MS?Long.MAX_VALUE:at+CLAIM_COOLDOWN_MS;
    }

    /** 这张券当前是否已经过了领取后的 24 小时等待期，并且尚未使用。 */
    public boolean ticketAvailable(JSONObject ticket,long now){
        return ticket!=null&&!ticket.optBoolean("used")&&now>=availableAt(ticket);
    }

    /** 已经领取满 24 小时、可以兑换的券。 */
    public int availableTickets(String cardId,long now){int n=0;for(int i=0;i<earned().length();i++){JSONObject e=earned().optJSONObject(i);if(e!=null&&cardId.equals(e.optString("card"))&&ticketAvailable(e,now))n++;}return n;}
    public int availableTickets(long now){int n=0;for(int i=0;i<love().length();i++){JSONObject c=love().optJSONObject(i);if(c!=null&&!c.optBoolean("archived"))n+=availableTickets(c.optString("id"),now);}return n;}

    /** 尚未到 24 小时可用时间的未使用券。 */
    public int waitingTickets(String cardId,long now){int n=0;for(int i=0;i<earned().length();i++){JSONObject e=earned().optJSONObject(i);if(e!=null&&cardId.equals(e.optString("card"))&&!e.optBoolean("used")&&now<availableAt(e))n++;}return n;}
    public int waitingTickets(long now){int n=0;for(int i=0;i<love().length();i++){JSONObject c=love().optJSONObject(i);if(c!=null&&!c.optBoolean("archived"))n+=waitingTickets(c.optString("id"),now);}return n;}

    /** 最早一张待解锁券的可用时刻；没有返回 0。 */
    public long nextAvailableAt(String cardId,long now){long next=0;for(int i=0;i<earned().length();i++){JSONObject e=earned().optJSONObject(i);if(e==null||!cardId.equals(e.optString("card"))||e.optBoolean("used"))continue;long at=availableAt(e);if(at>now&&(next==0||at<next))next=at;}return next;}

    /** 用掉该卡一张券，开一段时段冻结豁免窗口；返回分钟数，0=没券可用。 */
    public int use(String cardId,long now){
        JSONObject card=find(cardId);if(card==null||card.optBoolean("archived"))throw new IllegalArgumentException(T.s("热爱卡不存在"));
        JSONObject ticket=null;
        for(int i=0;i<earned().length();i++){JSONObject e=earned().optJSONObject(i);if(e!=null&&cardId.equals(e.optString("card"))&&ticketAvailable(e,now)){ticket=e;break;}}
        if(ticket==null)return 0;
        int minutes=card.optInt("minutes");put(ticket,"used",true);
        JSONObject u=new JSONObject();put(u,"card",cardId);put(u,"earned",ticket.optString("id"));
        put(u,"title",card.optString("title"));put(u,"start",now);put(u,"end",now+minutes*60000L);put(u,"minutes",minutes);
        db.optJSONArray("uses").put(u);return minutes;
    }

    /** 当前生效的豁免窗口终点；没有返回 0。多次使用取最晚的那个。 */
    public long rewardUntil(long now){long until=0;JSONArray uses=db.optJSONArray("uses");for(int i=0;i<uses.length();i++){JSONObject u=uses.optJSONObject(i);if(u!=null&&now>=u.optLong("start")&&now<u.optLong("end"))until=Math.max(until,u.optLong("end"));}return until;}

    private static void put(JSONObject o,String key,Object value){MemoryData.put(o,key,value);}
}
