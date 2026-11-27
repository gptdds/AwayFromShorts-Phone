package com.afs.guard;

import org.json.*;
import java.util.*;

/** Independent/shared allowances; each app follows its assigned schedule. Ledger stays per app. */
final class DailyQuotaData {
    final JSONObject config;
    JSONObject usage;
    DailyQuotaData(String raw,String ledger,long now){this(raw,ledger,now,DayBoundary.DEFAULT_HOUR);}
    /** resetHour 决定账本 date 键按哪个额度日写；只在账本为空/损坏时用到。 */
    DailyQuotaData(String raw,String ledger,long now,int resetHour){
        this(raw,ledger,now,resetHour,0);
    }
    DailyQuotaData(String raw,String ledger,long now,int resetHour,int resetMinute){
        try{config=new JSONObject(raw);if(!config.has("groups")){if(config.length()!=0)throw new IllegalArgumentException(T.s("限额配置缺少分组"));put(config,"enabled",false);put(config,"groups",new JSONArray());}validate(config);}
        catch(JSONException e){throw new IllegalArgumentException(T.s("每日限额配置无法读取"),e);}
        try{usage=new JSONObject(ledger);if(usage.length()==0)usage=fresh(now,resetHour,resetMinute);validateUsage(usage);}
        catch(RuntimeException|JSONException e){usage=fresh(now,resetHour,resetMinute);put(usage,"error",T.s("用量记录损坏，限额保护性冻结至次日"));}
    }
    static JSONObject remainingLegacy(JSONObject original,Set<String> migrated){JSONObject remaining=MemoryData.copy(original);for(String pkg:migrated)remaining.remove(pkg);return remaining;}
    static void validate(JSONObject config){
        if(!(config.opt("enabled") instanceof Boolean)||config.optJSONArray("groups")==null)throw new IllegalArgumentException(T.s("每日限额配置无效"));
        if(config.has("learningLink")&&!(config.opt("learningLink") instanceof Boolean))throw new IllegalArgumentException(T.s("每日限额配置无效"));
        Set<String> ids=new HashSet<>(),apps=new HashSet<>();Map<String,Boolean> orExitModes=new HashMap<>();Map<String,Integer> orExitMinutes=new HashMap<>();JSONArray groups=config.optJSONArray("groups");
        if(groups.length()>100)throw new IllegalArgumentException(T.s("最多设置100个限额格子"));
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g==null)throw new IllegalArgumentException(T.s("限额格子无效"));String id=g.optString("id"),title=g.optString("name");Object minutes=g.opt("minutes");JSONArray members=g.optJSONArray("apps");
            if(!id.matches("[A-Za-z0-9_-]{1,80}")||!ids.add(id)||title.trim().isEmpty()||title.length()>80||!(minutes instanceof Number)||((Number)minutes).doubleValue()!=g.optInt("minutes")||g.optInt("minutes")<1||g.optInt("minutes")>1440||members==null||members.length()<1||members.length()>100)throw new IllegalArgumentException(T.s("格子名称、应用或分钟数无效（1–1440整数分钟）"));
            for(int j=0;j<members.length();j++){Object pkg=members.opt(j);if(!(pkg instanceof String)||!((String)pkg).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")||!apps.add((String)pkg))throw new IllegalArgumentException(T.s("同一应用只能放在一个限额格子里"));}
            Set<String> groupApps=new HashSet<>();for(int j=0;j<members.length();j++)groupApps.add(members.optString(j));
            if(g.has("orGroup")&&!(g.opt("orGroup") instanceof String))throw new IllegalArgumentException(T.s("或集标识无效"));
            if(g.has("orForceExit")&&!(g.opt("orForceExit") instanceof Boolean))throw new IllegalArgumentException(T.s("或集退出设置无效"));
            if(g.has("orForceExitMinutes")&&(!(g.opt("orForceExitMinutes") instanceof Number)
                    ||((Number)g.opt("orForceExitMinutes")).doubleValue()!=g.optInt("orForceExitMinutes")
                    ||g.optInt("orForceExitMinutes")<1||g.optInt("orForceExitMinutes")>5))
                throw new IllegalArgumentException(T.s("首次提醒时间必须是1–5分钟"));
            if(g.has("orGroup")){
                String or=g.optString("orGroup");if(!or.matches("[A-Za-z0-9_-]{1,40}"))throw new IllegalArgumentException(T.s("或集标识无效"));
                boolean force=orForceExit(g);
                if(orExitModes.containsKey(or)&&orExitModes.get(or)!=force)throw new IllegalArgumentException(T.s("同一或集的退出设置必须一致"));
                int delay=orForceExitMinutes(g);
                if(orExitMinutes.containsKey(or)&&orExitMinutes.get(or)!=delay)throw new IllegalArgumentException(T.s("同一或集的首次提醒时间必须一致"));
                orExitModes.put(or,force);
                orExitMinutes.put(or,delay);
            }
            JSONArray background=g.optJSONArray("background");
            if(g.has("background")&&background==null)throw new IllegalArgumentException(T.s("挂后台计时的应用必须先在这个格子里"));
            if(background!=null)for(int j=0;j<background.length();j++){Object pkg=background.opt(j);if(!(pkg instanceof String)||!groupApps.contains((String)pkg))throw new IllegalArgumentException(T.s("挂后台计时的应用必须先在这个格子里"));}
            if(g.has("freezeDays")){Object fd=g.opt("freezeDays");
                if(!(fd instanceof Number)||((Number)fd).doubleValue()!=g.optInt("freezeDays")||g.optInt("freezeDays")<0||g.optInt("freezeDays")>Rule.ALL_DAYS)throw new IllegalArgumentException(T.s("星期冻结选择无效"));}
        }
        if(config.optBoolean("enabled")&&groups.length()==0)throw new IllegalArgumentException(T.s("请至少添加一个应用限额格子"));
    }
    /**
     * 额度的「收紧」权限只在全局调整时段内开放（{@link TimeWindow}）。
     *
     * 其余时间走 {@link #additive} 那条路 —— 能加（新格子、加应用、加分钟数、把总开关打开），
     * 不能减（移出应用、调小分钟数、删格子、关总开关）。理由很直白：
     * 白天额度不够就该补得上，但想把自己放出来这件事，只能等到调整时段。
     * 想彻底放开，把 EDIT_WINDOW 设成 false 即可。
     */
    static final boolean EDIT_WINDOW = true;
    static boolean editable(Calendar now){return editable(now,TimeWindow.DEFAULT_START,TimeWindow.DEFAULT_END);}
    static boolean editable(Calendar now,int winStart,int winEnd){return !EDIT_WINDOW||TimeWindow.contains(now.getTimeInMillis(),winStart,winEnd);}
    static void requireEdit(Calendar now){if(!editable(now))throw new IllegalStateException(T.s("这个改动要减少额度，只在调整时段内开放"));}
    /**
     * 候选配置相对当前是不是「只加不减」：新开格子、给格子加成员、加分钟数、把总开关打开。
     * 只要出现移除成员、调小分钟数、删格子、关总开关，就不算 —— 那些得等编辑窗口。
     * 纯数据，离线可测。
     */
    static boolean additive(JSONObject current,JSONObject candidate){
        if(current==null||candidate==null)return false;
        if(current.optBoolean("enabled")&&!candidate.optBoolean("enabled"))return false;
        if(learningLinked(current)&&!learningLinked(candidate))return false;
        JSONArray before=current.optJSONArray("groups"),after=candidate.optJSONArray("groups");
        if(before==null||after==null)return false;
        Map<String,JSONObject> was=new HashMap<>(),is=new HashMap<>();
        for(int i=0;i<before.length();i++){JSONObject g=before.optJSONObject(i);if(g!=null)was.put(g.optString("id"),g);}
        for(int i=0;i<after.length();i++){JSONObject g=after.optJSONObject(i);if(g!=null)is.put(g.optString("id"),g);}
        for(String id:was.keySet())if(!is.containsKey(id))return false;
        for(Map.Entry<String,JSONObject> row:is.entrySet()){
            JSONObject old=was.get(row.getKey());
            if(old==null)continue;
            JSONObject next=row.getValue();
            if(next.optInt("minutes")<old.optInt("minutes"))return false;
            Set<String> kept=new HashSet<>(),now=new HashSet<>();
            JSONArray a=old.optJSONArray("apps"),b=next.optJSONArray("apps");
            if(a!=null)for(int i=0;i<a.length();i++)kept.add(a.optString(i));
            if(b!=null)for(int i=0;i<b.length();i++)now.add(b.optString(i));
            if(!now.containsAll(kept))return false;
            // 挂后台计时一旦取消就是放宽：跟移出应用同一条规矩，只能等调整时段。
            if(!DailyQuotaData.backgroundApps(next).containsAll(DailyQuotaData.backgroundApps(old)))return false;
            // 取消某个星期冻结日＝放宽，要等调整时段；只新增冻结日随时可改。
            int oldDays=freezeDays(old),newDays=freezeDays(next);
            if((oldDays&~newDays)!=0)return false;
        }
        // 已加入的或集不能被拆开；改标识但成员仍连在一起、加入更多格子都算收紧。
        if(!orRelationsAdditive(current,candidate))return false;
        if(!orExitModesAdditive(current,candidate))return false;
        return true;
    }
    /** 关闭首次提醒或把等待时间加长属于放宽，只能在调整时段执行。 */
    private static boolean orExitModesAdditive(JSONObject old,JSONObject candidate){
        Map<String,Boolean> before=new HashMap<>(),after=new HashMap<>();
        Map<String,Integer> beforeDelay=new HashMap<>(),afterDelay=new HashMap<>();
        JSONArray was=old.optJSONArray("groups"),is=candidate.optJSONArray("groups");
        if(was==null||is==null)return false;
        for(int i=0;i<was.length();i++){
            JSONObject g=was.optJSONObject(i);String or=g==null?"":g.optString("orGroup","");
            if(!or.isEmpty()){before.put(or,orForceExit(g));beforeDelay.put(or,orForceExitMinutes(g));}
        }
        for(int i=0;i<is.length();i++){
            JSONObject g=is.optJSONObject(i);String or=g==null?"":g.optString("orGroup","");
            if(!or.isEmpty()){after.put(or,orForceExit(g));afterDelay.put(or,orForceExitMinutes(g));}
        }
        for(Map.Entry<String,Boolean> row:before.entrySet()){
            if(row.getValue()&&Boolean.FALSE.equals(after.get(row.getKey())))return false;
            Integer next=afterDelay.get(row.getKey());
            if(next!=null&&next>beforeDelay.getOrDefault(row.getKey(),DEFAULT_OR_FORCE_EXIT_MINUTES))return false;
        }
        return true;
    }
    private static boolean orRelationsAdditive(JSONObject old,JSONObject candidate){
        Map<String,Set<String>> was=new HashMap<>(),is=new HashMap<>();
        JSONArray before=old.optJSONArray("groups"),after=candidate.optJSONArray("groups");
        if(before==null||after==null)return false;
        for(int i=0;i<before.length();i++){
            JSONObject g=before.optJSONObject(i);String or=g==null?"":g.optString("orGroup","");
            if(or.isEmpty())continue;if(!was.containsKey(or))was.put(or,new HashSet<String>());was.get(or).add(g.optString("id"));
        }
        for(int i=0;i<after.length();i++){
            JSONObject g=after.optJSONObject(i);String or=g==null?"":g.optString("orGroup","");
            if(or.isEmpty())continue;if(!is.containsKey(or))is.put(or,new HashSet<String>());is.get(or).add(g.optString("id"));
        }
        for(Set<String> members:was.values()){
            if(members.size()<2)continue;
            String joined=null;
            for(String id:members){
                String current=null;
                for(Map.Entry<String,Set<String>> row:is.entrySet())if(row.getValue().contains(id)){current=row.getKey();break;}
                if(current==null||(joined!=null&&!joined.equals(current)))return false;
                joined=current;
            }
        }
        return true;
    }
    /** 这一格里勾了「挂后台也计时」的应用集合；没这个字段的旧配置返回空集。 */
    static Set<String> backgroundApps(JSONObject group){Set<String> out=new HashSet<>();JSONArray background=group.optJSONArray("background");if(background!=null)for(int i=0;i<background.length();i++)out.add(background.optString(i));return out;}
    /** 下一次调整时段开门的时间；时段为全天（start==end）时随时可改，返回当前时刻。 */
    static long nextEdit(Calendar now){return nextEdit(now,TimeWindow.DEFAULT_START,TimeWindow.DEFAULT_END);}
    static long nextEdit(Calendar now,int winStart,int winEnd){
        if(winStart==winEnd)return now.getTimeInMillis();
        if(TimeWindow.contains(now.getTimeInMillis(),winStart,winEnd))return now.getTimeInMillis();   // 已在窗口内（含跨午夜的后半夜）
        Calendar c=(Calendar)now.clone();
        c.set(Calendar.HOUR_OF_DAY,winStart/60);c.set(Calendar.MINUTE,winStart%60);
        c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        if(!c.after(now))c.add(Calendar.DAY_OF_MONTH,1);                                              // 今天的开点已过 → 明天
        return c.getTimeInMillis();
    }
    boolean enabled(){return config.optBoolean("enabled");}
    /**
     * 使用限额默认参与学习联动。旧配置没有这个字段时也按开启处理，
     * 升级后不需要再次保存才能获得联动；显式 false 才表示用户已关闭。
     */
    boolean learningLinked(){return learningLinked(config);}
    static boolean learningLinked(JSONObject config){
        return config!=null&&(!config.has("learningLink")||config.optBoolean("learningLink"));
    }
    JSONArray groups(){return config.optJSONArray("groups");}
    Set<String> packages(){Set<String> out=new LinkedHashSet<>();for(int i=0;i<groups().length();i++){JSONArray apps=groups().optJSONObject(i).optJSONArray("apps");for(int j=0;j<apps.length();j++)out.add(apps.optString(j));}return out;}
    JSONObject group(String pkg){for(int i=0;i<groups().length();i++){JSONObject group=groups().optJSONObject(i);JSONArray apps=group.optJSONArray("apps");for(int j=0;j<apps.length();j++)if(pkg.equals(apps.optString(j)))return group;}return null;}
    /** 按 id 取额度组；跨设备共享那边只存着组 id。 */
    JSONObject groupById(String id){if(id==null||id.isEmpty())return null;for(int i=0;i<groups().length();i++){JSONObject group=groups().optJSONObject(i);if(id.equals(group.optString("id")))return group;}return null;}
    static boolean hasOrGroup(JSONObject group){return group!=null&&!group.optString("orGroup","").isEmpty();}
    static boolean sharedCell(JSONObject group){return group!=null&&(hasOrGroup(group)||group.optJSONArray("apps")!=null&&group.optJSONArray("apps").length()>1);}
    static boolean allDay(JSONObject group,Rule rule){return sharedCell(group)||rule==null||FreezeSlotStore.unassigned(rule);}
    long used(JSONObject group){long total=0;JSONArray apps=group.optJSONArray("apps");for(int i=0;i<apps.length();i++)total+=usage.optJSONObject("targets").optLong(apps.optString(i));return total;}
    /**
     * 禁忌卡的限额惩罚：每个额度格子只把一份已用时间记到第一个成员上，
     * 共享格子的合计因此只增加一次，不会按成员数重复扣。
     */
    static int addUsedPenalty(JSONObject config,JSONObject usage,int minutes){
        if(config==null||usage==null||minutes<=0)return 0;
        JSONArray groups=config.optJSONArray("groups");
        if(groups==null||groups.length()==0)return 0;
        JSONObject targets=usage.optJSONObject("targets");
        if(targets==null){
            targets=new JSONObject();
            put(usage,"targets",targets);
        }
        long add=Math.min(86400000L,((long)minutes)*60000L);
        int changed=0;
        for(int i=0;i<groups.length();i++){
            JSONObject group=groups.optJSONObject(i);
            JSONArray apps=group==null?null:group.optJSONArray("apps");
            if(apps==null||apps.length()==0)continue;
            String pkg=apps.optString(0);
            if(pkg.isEmpty())continue;
            long before=targets.optLong(pkg);
            long after=Math.min(86400000L,before+add);
            if(after<=before)continue;
            put(targets,pkg,after);
            changed++;
        }
        return changed;
    }
    boolean exceeded(String pkg){return exceeded(pkg,usage.optLong("lastAt",0L));}
    boolean exceeded(String pkg,long now){JSONObject g=group(pkg);return enabled()&&g!=null&&(groupExhausted(g)||orFrozenByOther(g,now));}
    /** 只看本格自己的用量或账本故障，避免或集互相判定时递归。 */
    boolean groupExhausted(JSONObject g){return usage.optBoolean("unavailable")||!usage.optString("error").isEmpty()||used(g)>=g.optInt("minutes")*60000L;}
    /** 或集互斥的冻结边界固定为超过 5 分钟；首次提醒另有 1–5 分钟等待时间。 */
    static final long OR_FORGIVENESS_MS=5*60_000L;
    static final int DEFAULT_OR_FORCE_EXIT_MINUTES=4;
    boolean orFrozenByOther(JSONObject g){return orFrozenByOther(g,usage.optLong("lastAt",0L));}
    boolean orFrozenByOther(JSONObject g,long now){
        String or=g.optString("orGroup","");if(or.isEmpty())return false;
        String selected=orChoice(or);
        if(selected.isEmpty()||selected.equals(g.optString("id")))return false;
        JSONObject choice=(usage.optJSONObject("or_choices_meta")==null?null:usage.optJSONObject("or_choices_meta").optJSONObject(or));
        if(choice==null)return true;   // 旧账本没有 meta：立刻冻结，行为与之前一致
        long first=choice.optLong("first_use",0L);
        return first>0L&&now-first>OR_FORGIVENESS_MS;
    }
    /** 只决定是否在当天首次等待时间到点后弹窗提醒一次；冻结本身仍按超过 5 分钟执行。 */
    static boolean orForceExit(JSONObject group){return group!=null&&(!group.has("orForceExit")||group.optBoolean("orForceExit",true));}
    static int orForceExitMinutes(JSONObject group){
        if(group==null)return DEFAULT_OR_FORCE_EXIT_MINUTES;
        int value=group.optInt("orForceExitMinutes",DEFAULT_OR_FORCE_EXIT_MINUTES);
        return value>=1&&value<=5?value:DEFAULT_OR_FORCE_EXIT_MINUTES;
    }
    long orRemindedAt(String orGroup){
        JSONObject meta=usage.optJSONObject("or_choices_meta");
        JSONObject entry=meta==null?null:meta.optJSONObject(orGroup);
        return entry==null?0L:entry.optLong("reminded_at",entry.optLong("force_exited_at",0L));
    }
    /** 兼容旧账本字段名：旧版本把“已提醒”写在 force_exited_at。 */
    long orForceExitedAt(String orGroup){
        return orRemindedAt(orGroup);
    }
    boolean orReminderDue(JSONObject group,long now){
        if(!orForceExit(group))return false;
        String or=group.optString("orGroup","");if(or.isEmpty()||orRemindedAt(or)>0L)return false;
        String selected=orChoice(or);
        if(selected.isEmpty()||selected.equals(group.optString("id")))return false;
        JSONObject meta=usage.optJSONObject("or_choices_meta");
        JSONObject entry=meta==null?null:meta.optJSONObject(or);
        long first=entry==null?0L:entry.optLong("first_use",0L);
        long delay=orForceExitMinutes(group)*60000L;
        // The 5-second service tick can first observe a configured five-minute
        // boundary slightly late. Do not add an upper sampling window here:
        // the per-day marker below is what prevents a second reminder.
        return first>0L&&now-first>=delay;
    }
    boolean orForceExitDue(JSONObject group,long now){
        return orReminderDue(group,now);
    }
    void markOrReminded(String orGroup,long now){
        if(orGroup==null||orGroup.isEmpty())return;
        JSONObject meta=usage.optJSONObject("or_choices_meta");if(meta==null)meta=new JSONObject();
        JSONObject entry=meta.optJSONObject(orGroup);if(entry==null)entry=new JSONObject();
        put(entry,"reminded_at",now);
        // 保留旧字段，旧账本或旧版本仍能识别今天已经提醒过。
        put(entry,"force_exited_at",now);
        put(meta,orGroup,entry);put(usage,"or_choices_meta",meta);
    }
    void markOrForceExited(String orGroup,long now){
        markOrReminded(orGroup,now);
    }
    /** 或集选项属于整组，任一成员页面更改后全部成员同步。 */
    static void setOrForceExit(JSONObject config,String orGroup,boolean force){
        if(config==null||orGroup==null||orGroup.isEmpty())return;
        JSONArray groups=config.optJSONArray("groups");if(groups==null)return;
        for(int i=0;i<groups.length();i++){
            JSONObject group=groups.optJSONObject(i);
            if(group!=null&&orGroup.equals(group.optString("orGroup","")))put(group,"orForceExit",force);
        }
    }
    static void setOrForceExitMinutes(JSONObject config,String orGroup,int minutes){
        if(config==null||orGroup==null||orGroup.isEmpty()||minutes<1||minutes>5)return;
        JSONArray groups=config.optJSONArray("groups");if(groups==null)return;
        for(int i=0;i<groups.length();i++){
            JSONObject group=groups.optJSONObject(i);
            if(group!=null&&orGroup.equals(group.optString("orGroup","")))put(group,"orForceExitMinutes",minutes);
        }
    }
    String orChoice(String orGroup){
        JSONObject choices=usage.optJSONObject("or_choices");
        return choices==null?"":choices.optString(orGroup,"");
    }
    static boolean inScope(Rule rule,long now){Calendar c=Calendar.getInstance();c.setTimeInMillis(now);return rule==null||FreezeSlotStore.unassigned(rule)||!rule.inWindow(c);}
    /**
     * 额度是否触发的纯判定（把 {@link DailyQuotaStore#exceeded} 里那段优先级规则抽出来，离线可测）。
     *
     * 共享格子优先于应用自身的冻结时段：共享组一律不受 {@code inScope} 约束 ——
     * 只要本机这一份满了（{@code localExceeded}）或跨设备合计用尽（{@code lanExhausted}）就冻，
     * 不管它此刻是不是在自己的放行时段里；反过来没满就放行，也不因时段单独冻结它。
     * 独立格子沿用旧口径：必须在本应用承担的时段范围内（{@code inScope}）才判超限。
     */
    static boolean exceededBy(boolean shared,boolean localExceeded,boolean inScope,boolean lanExhausted){
        return shared?(localExceeded||lanExhausted):(inScope&&localExceeded);
    }

    /** Final policy step after quota exhaustion and OR-set exclusion are separated. */
    static boolean enforced(boolean quotaExceeded,boolean orExceeded,
                            boolean quotaPaused,boolean orPaused){
        return (!quotaPaused&&quotaExceeded)||(!orPaused&&orExceeded);
    }

    /** 星期冻结位图：bit0=周一 … bit6=周日；0 / 没这个字段＝不启用，每天照旧按额度。 */
    static int freezeDays(JSONObject group){return group==null?0:group.optInt("freezeDays",0)&Rule.ALL_DAYS;}
    /** 今天是不是这个共享格子指定的「整天冻结日」。Calendar 的周日=1，这里换算成周一=0。 */
    static boolean freezeToday(JSONObject group,Calendar now){
        int mask=freezeDays(group);if(mask==0)return false;
        int dow=(now.get(Calendar.DAY_OF_WEEK)+5)%7;
        return (mask&(1<<dow))!=0;
    }
    /** 「周一、周六」这种文案；没选返回空串。 */
    static String weekdayLabel(int mask){
        mask&=Rule.ALL_DAYS;if(mask==0)return "";
        StringBuilder out=new StringBuilder();
        for(int i=0;i<7;i++)if((mask&(1<<i))!=0){if(out.length()>0)out.append(T.s("、"));out.append(T.s("周")).append(T.s(Rule.DAY_NAMES[i]));}
        return out.toString();
    }
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable){advance(now,spans,reliable,Collections.emptyMap(),DayBoundary.DEFAULT_HOUR);}
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable,Map<String,Rule> rules){advance(now,spans,reliable,rules,DayBoundary.DEFAULT_HOUR);}
    /**
     * 账本推进：共享组全天计时；独立额度按「应用自身冻结时段」裁剪，不看全局调整时段。
     *
     * 调整时段（默认 00:00–01:00）只决定「什么时候能改设置」，跟统计和判超无关。
     * 之前拿它过滤账本，结果白天用了几小时全都不记，额度永远停在 0 —— 那是个错误的口径。
     */
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable,Map<String,Rule> rules,int resetHour){
        advance(now,spans,reliable,rules,resetHour,0);
    }
    void advance(long now,List<AppUsageQuota.Span> spans,boolean reliable,Map<String,Rule> rules,int resetHour,int resetMinute){
        long begin=DayBoundary.startMs(now,resetHour,resetMinute);
        // 跨额度日重置前先读旧账本的起点：reset 会把 since 写成 now，白白丢掉今天零点到现在的用量。
        long since=usage.optLong("since",begin);   // 老账本没有 since：按「从额度日起点算」自愈重算
        if(!DayBoundary.key(now,resetHour,resetMinute).equals(usage.optString("date"))){usage=fresh(begin,resetHour,resetMinute);since=begin;}
        // since = 这份账本开始统计的时刻（新建当天账本 / 启用额度那一刻）。早于它的用量不计，
        // 否则「下午才开额度」会把上午的用量一次性算进来。
        long limit=Math.min(Math.max(since,begin),now);
        if(enabled()&&reliable&&usage.optString("error").isEmpty()){
            Set<String> apps=packages();JSONObject targets=usage.optJSONObject("targets");
            // 按整段用量「重算」，不再按窗口增量累加。
            // 增量法只要有一次刷新失败 / 进程被杀 / 系统事件延迟，那一小段就永久丢失（真机上一天能丢九成）；
            // 重算每次都从今天的 spans 重新求和，漏掉的自动补回来，且天然幂等。
            Map<String,Long> computed=new HashMap<>();
            for(AppUsageQuota.Span s:spans)if(apps.contains(s.pkg)){
                JSONObject quota=group(s.pkg);
                // 与 exceededBy 的共享组优先级保持一致：放行时段也消耗共享额度。
                Rule rule=rules.get(s.pkg);Rule scope=allDay(quota,rule)?null:rule;
                JSONObject epochs=usage.optJSONObject("counting_epochs"),epoch=epochs==null?null:epochs.optJSONObject(s.pkg);
                long sinceMode=epoch==null?limit:Math.max(limit,epoch.optLong("at")),baseline=epoch==null?0:epoch.optLong("used");
                long a=Math.max(sinceMode,s.start),b=Math.min(now,s.end);
                if(b>a){long elapsed=AppUsageQuota.overlap(a,b,scope);if(elapsed>0)computed.put(s.pkg,computed.getOrDefault(s.pkg,baseline)+elapsed);}
            }
            // 只升不降：系统把历史事件裁掉时算出来的数会变小，不能把已记上的用量抹回去。
            for(Map.Entry<String,Long> e:computed.entrySet()){String pkg=e.getKey();if(e.getValue()>targets.optLong(pkg))put(targets,pkg,e.getValue());}
            updateOrChoices(spans,limit,now);
        }
        if(enabled()&&!reliable)put(usage,"unavailable",true);else usage.remove("unavailable");
        put(usage,"lastAt",now);
    }
    /** 按当天最早的真实用量选定或集成员；选定后保留到额度日重置。 */
    private void updateOrChoices(List<AppUsageQuota.Span> spans,long limit,long now){
        JSONObject choices=usage.optJSONObject("or_choices");if(choices==null)choices=new JSONObject();
        JSONObject oldMeta=usage.optJSONObject("or_choices_meta");if(oldMeta==null)oldMeta=new JSONObject();
        JSONObject meta=new JSONObject();
        Map<String,String> selected=new HashMap<>();
        for(int i=0;i<groups().length();i++){
            JSONObject group=groups().optJSONObject(i);String or=group==null?"":group.optString("orGroup",""),id=group==null?"":group.optString("id");
            if(or.isEmpty()||id.isEmpty())continue;
            String existing=choices.optString(or,"");
            if(!existing.isEmpty()&&groupById(existing)!=null&&or.equals(groupById(existing).optString("orGroup","")))selected.put(or,existing);
        }
        Map<String,Long> earliestStart=new HashMap<>();
        Map<String,String> earliestGroup=new HashMap<>();
        for(AppUsageQuota.Span span:spans){
            JSONObject quota=group(span.pkg);String or=quota==null?"":quota.optString("orGroup","");if(or.isEmpty())continue;
            long start=Math.max(limit,span.start),end=Math.min(now,span.end);
            if(end<=start)continue;
            if(selected.containsKey(or))continue;
            long old=earliestStart.containsKey(or)?earliestStart.get(or):Long.MAX_VALUE;
            if(start<old){earliestStart.put(or,start);earliestGroup.put(or,quota.optString("id",""));}
        }
        for(Map.Entry<String,String> row:earliestGroup.entrySet())selected.put(row.getKey(),row.getValue());
        for(Map.Entry<String,String> row:selected.entrySet()){
            String or=row.getKey();
            JSONObject prior=oldMeta.optJSONObject(or);
            long firstUse=prior!=null&&prior.optLong("first_use",0L)>0L?prior.optLong("first_use",0L):earliestStart.getOrDefault(or,now);
            JSONObject m=new JSONObject();put(m,"first_use",firstUse);
            long remindedAt=prior==null?0L:prior.optLong("reminded_at",prior.optLong("force_exited_at",0L));
            if(remindedAt>0L)put(m,"reminded_at",remindedAt);
            if(prior!=null&&prior.optLong("force_exited_at",0L)>0L)put(m,"force_exited_at",prior.optLong("force_exited_at"));
            put(meta,or,m);
        }
        for(Map.Entry<String,String> row:selected.entrySet())put(choices,row.getKey(),row.getValue());
        if(choices.length()>0)put(usage,"or_choices",choices);else usage.remove("or_choices");
        if(meta.length()>0)put(usage,"or_choices_meta",meta);else usage.remove("or_choices_meta");
    }
    /** 开关切换先结清旧口径，保存每应用的起点，避免重算把关闭后的前台用量吞掉。 */
    void checkpoint(JSONObject next,long now){
        DailyQuotaData candidate=new DailyQuotaData(next.toString(),usage.toString(),now);
        Set<String> before=packages(),oldBg=backgroundPackages(),newBg=candidate.backgroundPackages();
        JSONObject epochs=usage.optJSONObject("counting_epochs");if(epochs==null)epochs=new JSONObject();
        for(String pkg:candidate.packages())if(!before.contains(pkg)||oldBg.contains(pkg)!=newBg.contains(pkg)||enabled()!=candidate.enabled()){
            JSONObject epoch=new JSONObject();put(epoch,"at",now);put(epoch,"used",usage.optJSONObject("targets").optLong(pkg));put(epochs,pkg,epoch);
        }
        if(epochs.length()>0)put(usage,"counting_epochs",epochs);
    }
    /** 所有格子里勾了「挂后台也计时」的应用集合；接线处拿它决定哪些应用要并上前台服务区间。 */
    Set<String> backgroundPackages(){Set<String> out=new HashSet<>();for(int i=0;i<groups().length();i++)out.addAll(backgroundApps(groups().optJSONObject(i)));return out;}
    static JSONObject fresh(long now){return fresh(now,DayBoundary.DEFAULT_HOUR);}
    static JSONObject fresh(long now,int resetHour){return fresh(now,resetHour,0);}
    static JSONObject fresh(long now,int resetHour,int resetMinute){JSONObject out=new JSONObject();put(out,"date",DayBoundary.key(now,resetHour,resetMinute));put(out,"lastAt",now);put(out,"since",now);put(out,"targets",new JSONObject());return out;}
    static void validateUsage(JSONObject usage){validateEpochs(usage);validateOrChoices(usage);validateOrChoiceMeta(usage);if(!usage.optString("date").matches("\\d{4}-\\d{2}-\\d{2}")||!(usage.opt("lastAt") instanceof Number)||usage.optLong("lastAt")<0||usage.optJSONObject("targets")==null)throw new IllegalArgumentException(T.s("用量数据无效"));Iterator<String> keys=usage.optJSONObject("targets").keys();while(keys.hasNext()){Object value=usage.optJSONObject("targets").opt(keys.next());if(!(value instanceof Number)||((Number)value).doubleValue()<0||!Double.isFinite(((Number)value).doubleValue())||((Number)value).doubleValue()>86400000L)throw new IllegalArgumentException(T.s("用量数据无效"));}}
    private static void validateOrChoices(JSONObject usage){
        if(!usage.has("or_choices"))return;
        JSONObject choices=usage.optJSONObject("or_choices");if(choices==null)throw new IllegalArgumentException(T.s("用量数据无效"));
        for(Iterator<String> keys=choices.keys();keys.hasNext();){
            String or=keys.next();Object id=choices.opt(or);
            if(!or.matches("[A-Za-z0-9_-]{1,40}")||!(id instanceof String)||!((String)id).matches("[A-Za-z0-9_-]{1,80}"))
                throw new IllegalArgumentException(T.s("用量数据无效"));
        }
    }
    private static void validateOrChoiceMeta(JSONObject usage){
        if(!usage.has("or_choices_meta"))return;
        JSONObject meta=usage.optJSONObject("or_choices_meta");if(meta==null)throw new IllegalArgumentException(T.s("用量数据无效"));
        for(Iterator<String> keys=meta.keys();keys.hasNext();){
            String or=keys.next();JSONObject entry=meta.optJSONObject(or);
            if(!or.matches("[A-Za-z0-9_-]{1,40}")||entry==null||!(entry.opt("first_use") instanceof Number)
                    ||entry.optLong("first_use")<0
                    ||(entry.has("reminded_at")&&(!(entry.opt("reminded_at") instanceof Number)
                    ||((Number)entry.opt("reminded_at")).doubleValue()!=entry.optLong("reminded_at")
                    ||entry.optLong("reminded_at")<0))
                    ||(entry.has("force_exited_at")&&(!(entry.opt("force_exited_at") instanceof Number)
                    ||((Number)entry.opt("force_exited_at")).doubleValue()!=entry.optLong("force_exited_at")
                    ||entry.optLong("force_exited_at")<0)))
                throw new IllegalArgumentException(T.s("用量数据无效"));
        }
    }
    private static void validateEpochs(JSONObject usage){
        if(!usage.has("counting_epochs"))return;
        JSONObject epochs=usage.optJSONObject("counting_epochs");if(epochs==null)throw new IllegalArgumentException(T.s("用量数据无效"));
        for(Iterator<String> keys=epochs.keys();keys.hasNext();){JSONObject epoch=epochs.optJSONObject(keys.next());
            if(epoch==null)throw new IllegalArgumentException(T.s("用量数据无效"));
            for(String key:new String[]{"at","used"}){Object v=epoch.opt(key);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue())||((Number)v).doubleValue()<0||((Number)v).doubleValue()!=epoch.optLong(key)||("used".equals(key)&&epoch.optLong(key)>86400000L))throw new IllegalArgumentException(T.s("用量数据无效"));}
        }
    }
    static void put(JSONObject o,String key,Object value){MemoryData.put(o,key,value);}
    /** 只剩一个成员的或集不会冻结任何人，清除它的残留标识。 */
    static void normalizeOrGroups(JSONObject config){
        JSONArray groups=config.optJSONArray("groups");if(groups==null)return;
        Map<String,Integer> count=new HashMap<>();
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);String or=g==null?"":g.optString("orGroup","");if(!or.isEmpty())count.put(or,count.getOrDefault(or,0)+1);}
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g!=null&&count.getOrDefault(g.optString("orGroup",""),0)<2){g.remove("orGroup");g.remove("orForceExit");g.remove("orForceExitMinutes");}}
    }
    /** 将两格连成同一或集；已有或集时做链条合并，合并只增加联动，属于收紧。 */
    static boolean joinOrGroup(JSONObject config,String sourceId,String targetId,String freshId){
        if(config==null||sourceId==null||targetId==null||sourceId.equals(targetId)||freshId==null||freshId.isEmpty())return false;
        JSONArray groups=config.optJSONArray("groups");if(groups==null)return false;
        JSONObject source=null,target=null;
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g==null)continue;if(sourceId.equals(g.optString("id")))source=g;if(targetId.equals(g.optString("id")))target=g;}
        if(source==null||target==null)return false;
        String sourceOr=source.optString("orGroup",""),targetOr=target.optString("orGroup","");
        boolean forceExit=orForceExit(source)||orForceExit(target);
        int forceMinutes=Math.min(orForceExitMinutes(source),orForceExitMinutes(target));
        String result=!sourceOr.isEmpty()?sourceOr:!targetOr.isEmpty()?targetOr:freshId;
        if(!sourceOr.isEmpty()&&!targetOr.isEmpty()&&!sourceOr.equals(targetOr))
            for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g!=null&&targetOr.equals(g.optString("orGroup","")))put(g,"orGroup",sourceOr);}
        put(source,"orGroup",result);put(target,"orGroup",result);
        setOrForceExit(config,result,forceExit);
        setOrForceExitMinutes(config,result,forceMinutes);
        return true;
    }
    /** 从一个或集退出；成员少于两人时把残留标识一并清掉。 */
    static void leaveOrGroup(JSONObject config,String groupId){
        JSONArray groups=config.optJSONArray("groups");if(groups==null)return;
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g!=null&&groupId.equals(g.optString("id"))){g.remove("orGroup");break;}}
        normalizeOrGroups(config);
    }
    /** 把 id 对应的格子摘掉，其余原样返回（新建/改名/合并都用它铺底）。 */
    static JSONArray without(JSONObject config,String id){
        JSONArray out=new JSONArray();JSONArray groups=config.optJSONArray("groups");if(groups==null)return out;
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g!=null&&!id.equals(g.optString("id")))out.put(g);}
        return out;
    }
    /** A returned picker selection replaces the original members, including an empty selection. */
    static Set<String> editorMembers(JSONObject group,Set<String> draft,String preset){
        Set<String> chosen=new LinkedHashSet<>();
        if(draft!=null)chosen.addAll(draft);
        else{JSONArray apps=group==null?null:group.optJSONArray("apps");if(apps!=null)for(int i=0;i<apps.length();i++)chosen.add(apps.optString(i));}
        if(preset!=null)chosen.add(preset);
        return chosen;
    }
    private static void retainBackground(JSONObject group){
        JSONArray apps=group.optJSONArray("apps"),background=group.optJSONArray("background");if(background==null)return;
        Set<String> members=new HashSet<>();if(apps!=null)for(int i=0;i<apps.length();i++)members.add(apps.optString(i));
        JSONArray keep=new JSONArray();for(int i=0;i<background.length();i++)if(members.contains(background.optString(i)))keep.put(background.optString(i));
        if(keep.length()==0)group.remove("background");else put(group,"background",keep);
    }
    /** Removes membership only; the separate per-app usage ledger and freeze rules are untouched. */
    static int removeMember(JSONObject config,String id,String pkg){
        JSONArray groups=config.optJSONArray("groups");if(groups==null||id==null||pkg==null)return -1;
        for(int i=0;i<groups.length();i++){
            JSONObject group=groups.optJSONObject(i);if(group==null||!id.equals(group.optString("id")))continue;
            JSONArray apps=group.optJSONArray("apps"),keep=new JSONArray();boolean removed=false;
            if(apps!=null)for(int j=0;j<apps.length();j++){String member=apps.optString(j);if(pkg.equals(member))removed=true;else keep.put(member);}
            if(!removed)return 0;
            if(keep.length()==0){groups.remove(i);if(groups.length()==0)put(config,"enabled",false);}
            else{put(group,"apps",keep);retainBackground(group);}
            return 1;
        }
        return -1;
    }
    /** 把 fromId 的成员并进 intoId（分钟取调用方给的较小值），返回是否合并成功。纯数据，离线可测。 */
    static boolean mergeInto(JSONObject config,String intoId,String fromId,int minutes){
        if(intoId==null||fromId==null||intoId.equals(fromId))return false;
        JSONArray groups=config.optJSONArray("groups");if(groups==null)return false;
        JSONObject into=null,from=null;
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g==null)continue;if(intoId.equals(g.optString("id")))into=g;else if(fromId.equals(g.optString("id")))from=g;}
        if(into==null||from==null)return false;
        Set<String> apps=new LinkedHashSet<>();JSONArray a=into.optJSONArray("apps"),b=from.optJSONArray("apps");
        if(a==null||b==null)return false;
        for(int i=0;i<a.length();i++)apps.add(a.optString(i));
        for(int i=0;i<b.length();i++)apps.add(b.optString(i));
        Set<String> background=new LinkedHashSet<>(backgroundApps(into));background.addAll(backgroundApps(from));background.retainAll(apps);
        put(into,"apps",new JSONArray(apps));put(into,"minutes",minutes);
        if(background.isEmpty())into.remove("background");else put(into,"background",new JSONArray(background));
        put(config,"groups",without(config,fromId));
        return true;
    }
    /**
     * 把一只应用迁到 targetId 那一格：先从别的格子摘掉，再加进目标格子；
     * 被搬空的格子一起删掉。返回 1＝迁成功，0＝已经在这一格里，-1＝目标不存在。纯数据，离线可测。
     */
    static int moveInto(JSONObject config,String targetId,String pkg){
        JSONArray groups=config.optJSONArray("groups");
        if(groups==null||pkg==null||pkg.isEmpty())return -1;
        JSONObject target=null;
        for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g!=null&&targetId.equals(g.optString("id")))target=g;}
        if(target==null)return -1;
        JSONArray members=target.optJSONArray("apps");
        if(members==null)return -1;
        for(int i=0;i<members.length();i++)if(pkg.equals(members.optString(i)))return 0;
        boolean carryBackground=false;
        for(int i=0;i<groups.length();i++){
            JSONObject g=groups.optJSONObject(i);if(g==null||g==target)continue;
            JSONArray apps=g.optJSONArray("apps");if(apps==null)continue;
            JSONArray keep=new JSONArray();boolean removed=false;
            for(int j=0;j<apps.length();j++){String p=apps.optString(j);if(p.equals(pkg)){removed=true;continue;}keep.put(p);}
            if(removed){carryBackground|=backgroundApps(g).contains(pkg);put(g,"apps",keep);retainBackground(g);}
        }
        members.put(pkg);
        if(carryBackground){Set<String> keep=backgroundApps(target);keep.add(pkg);put(target,"background",new JSONArray(keep));}
        for(int i=groups.length()-1;i>=0;i--){JSONObject g=groups.optJSONObject(i);JSONArray apps=g==null?null:g.optJSONArray("apps");if(apps==null||apps.length()==0)groups.remove(i);}
        return 1;
    }
}
