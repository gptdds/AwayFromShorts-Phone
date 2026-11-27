package com.afs.guard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 待办卡的本机存储：标题 + 说明 + 可选定时。
 *
 * <p>待办是独立于学习的东西：完成一张待办不会改学习进度、冻结时段或惩罚期，
 * 打卡页只是它到点后出现的处理面。数据只落在自己的偏好里，不联网。</p>
 *
 * <p>没有定时的待办（{@code remindAt == 0}）建完就能在打卡页看到；
 * 设了时间的要到点之后才会进卡组，同时会有一条本地通知。</p>
 */
public final class TodoStore {

    public static final int MAX_TODOS = 500;
    public static final int MAX_TITLE = 120;
    public static final int MAX_DETAIL = 2000;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DONE = 1;
    public static final int STATUS_CANCELLED = 2;

    private static final String SP = "afs_todo";
    private static final String KEY = "todos_v1";
    private static final String UNTITLED = "无标题待办";

    /** 一条待办。 */
    public static final class Todo {
        public String id;
        public String title;
        public String detail;
        /** 0 = 立即进打卡页；>0 = 到点才进打卡页。 */
        public long remindAt;
        public long createdAt;
        public long updatedAt;
        public long resolvedAt;
        /** 已经为这个时间发过通知；同一个 remindAt 不再重复弹。 */
        public long remindedAt;
        /** Empty = system default notification sound; silent = no sound; otherwise a sound URI. */
        public String soundUri;
        public int status;

        public Todo(String id, String title, String detail, long remindAt, long createdAt) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
            this.remindAt = remindAt;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
            this.soundUri = "";
            this.status = STATUS_PENDING;
        }

        public Todo copy() {
            Todo copy = new Todo(id, title, detail, remindAt, createdAt);
            copy.updatedAt = updatedAt;
            copy.resolvedAt = resolvedAt;
            copy.remindedAt = remindedAt;
            copy.soundUri = soundUri == null ? "" : soundUri;
            copy.status = status;
            return copy;
        }

        public boolean pending() {
            return status == STATUS_PENDING;
        }
    }

    private TodoStore() {
    }

    /* ==================== 纯函数 ==================== */

    /** 未处理在前；未处理里立即项优先，再按定时时间、创建时间排；已处理按处理时间倒序。 */
    public static int compare(Todo a, Todo b) {
        boolean ap = a != null && a.pending(), bp = b != null && b.pending();
        if (ap != bp) {
            return ap ? -1 : 1;
        }
        if (!ap) {
            if (a == null || b == null) {
                return a == null ? (b == null ? 0 : 1) : -1;
            }
            if (a.resolvedAt != b.resolvedAt) {
                return a.resolvedAt > b.resolvedAt ? -1 : 1;
            }
            return stableId(a, b);
        }
        long at = a == null ? 0L : a.remindAt;
        long bt = b == null ? 0L : b.remindAt;
        if (at != bt) {
            return at < bt ? -1 : 1;
        }
        long ac = a == null ? 0L : a.createdAt;
        long bc = b == null ? 0L : b.createdAt;
        if (ac != bc) {
            return ac < bc ? -1 : 1;
        }
        return stableId(a, b);
    }

    private static int stableId(Todo a, Todo b) {
        String left = a == null || a.id == null ? "" : a.id;
        String right = b == null || b.id == null ? "" : b.id;
        return left.compareTo(right);
    }

    public static List<Todo> order(List<Todo> todos) {
        List<Todo> copy = new ArrayList<Todo>();
        if (todos != null) {
            for (Todo todo : todos) {
                if (todo != null) {
                    copy.add(todo);
                }
            }
        }
        Collections.sort(copy, TodoStore::compare);
        return copy;
    }

    /** 未处理的全部待办。 */
    public static List<Todo> pending(List<Todo> todos) {
        List<Todo> out = new ArrayList<Todo>();
        if (todos != null) {
            for (Todo todo : todos) {
                if (todo != null && todo.pending()) {
                    out.add(todo);
                }
            }
        }
        return order(out);
    }

    /** 到点、该进打卡页的待办：立即项 + 定时已到的项。 */
    public static List<Todo> due(List<Todo> todos, long now) {
        List<Todo> out = new ArrayList<Todo>();
        if (todos != null) {
            for (Todo todo : todos) {
                if (isDue(todo, now)) {
                    out.add(todo);
                }
            }
        }
        return order(out);
    }

    public static boolean isDue(Todo todo, long now) {
        return todo != null && todo.pending() && (todo.remindAt <= 0L || todo.remindAt <= now);
    }

    public static List<Todo> resolved(List<Todo> todos) {
        List<Todo> out = new ArrayList<Todo>();
        if (todos != null) {
            for (Todo todo : todos) {
                if (todo != null && !todo.pending()) {
                    out.add(todo);
                }
            }
        }
        return order(out);
    }

    public static Todo find(List<Todo> todos, String id) {
        if (todos == null || id == null) {
            return null;
        }
        for (Todo todo : todos) {
            if (todo != null && id.equals(todo.id)) {
                return todo;
            }
        }
        return null;
    }

    /** 标题空时退回说明第一行，再空就是「无标题待办」。 */
    public static String displayTitle(Todo todo) {
        if (todo == null) {
            return UNTITLED;
        }
        String title = oneLine(todo.title);
        if (!title.isEmpty()) {
            return clip(title, MAX_TITLE);
        }
        String detail = todo.detail == null ? "" : todo.detail;
        for (String line : detail.split("\r?\n")) {
            String text = oneLine(line);
            if (!text.isEmpty()) {
                return clip(text, 40);
            }
        }
        return UNTITLED;
    }

    public static String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    public static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 打卡页轮询用：一组到期待办的身份，变了就代表卡组要重排。 */
    public static String dueSignature(List<Todo> todos, long now) {
        StringBuilder sig = new StringBuilder();
        for (Todo todo : due(todos, now)) {
            if (sig.length() > 0) {
                sig.append(',');
            }
            sig.append(todo.id).append(':').append(todo.remindAt);
        }
        return sig.toString();
    }

    /* ==================== JSON ==================== */

    public static String toJson(List<Todo> todos) {
        JSONArray array = new JSONArray();
        try {
            if (todos != null) {
                for (Todo todo : todos) {
                    if (todo == null) {
                        continue;
                    }
                    JSONObject object = new JSONObject();
                    object.put("id", todo.id == null ? "" : todo.id);
                    object.put("title", todo.title == null ? "" : todo.title);
                    object.put("detail", todo.detail == null ? "" : todo.detail);
                    object.put("remindAt", todo.remindAt);
                    object.put("createdAt", todo.createdAt);
                    object.put("updatedAt", todo.updatedAt);
                    object.put("resolvedAt", todo.resolvedAt);
                    object.put("remindedAt", todo.remindedAt);
                    object.put("soundUri", normalizeSoundUri(todo.soundUri));
                    object.put("status", todo.status);
                    array.put(object);
                }
            }
        } catch (Exception ignored) {
            // 一条坏数据不值得让整个待办列表消失
        }
        return array.toString();
    }

    /** 坏数据跳过；重复 id 只留第一条，避免「完成一条却还剩一条」。 */
    public static List<Todo> fromJson(String json) {
        List<Todo> out = new ArrayList<Todo>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        HashSet<String> seen = new HashSet<String>();
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length() && out.size() < MAX_TODOS; index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                String id = clip(oneLine(object.optString("id", "")), 64);
                if (id.isEmpty() || seen.contains(id)) {
                    continue;
                }
                String title = clip(object.optString("title", ""), MAX_TITLE);
                String detail = clip(object.optString("detail", ""), MAX_DETAIL);
                if (title.trim().isEmpty() && detail.trim().isEmpty()) {
                    continue;
                }
                Todo todo = new Todo(id, title, detail, Math.max(0L, object.optLong("remindAt", 0L)),
                        Math.max(0L, object.optLong("createdAt", 0L)));
                todo.updatedAt = Math.max(0L, object.optLong("updatedAt", todo.createdAt));
                todo.resolvedAt = Math.max(0L, object.optLong("resolvedAt", 0L));
                todo.remindedAt = Math.max(0L, object.optLong("remindedAt", 0L));
                todo.soundUri = normalizeSoundUri(object.optString("soundUri", ""));
                int status = object.optInt("status", STATUS_PENDING);
                todo.status = status == STATUS_DONE || status == STATUS_CANCELLED ? status : STATUS_PENDING;
                if (!todo.pending() && todo.resolvedAt <= 0L) {
                    todo.resolvedAt = todo.updatedAt;
                }
                seen.add(id);
                out.add(todo);
            }
        } catch (Exception ignored) {
            // 整个 JSON 坏了就当作没有待办；用户的输入已经丢不了，从这里开始重写
        }
        return order(out);
    }

    /* ==================== Context 包装 ==================== */

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static synchronized List<Todo> all(Context context) {
        return fromJson(prefs(context).getString(KEY, "[]"));
    }

    private static boolean save(Context context, List<Todo> todos) {
        return prefs(context).edit().putString(KEY, toJson(todos)).commit();
    }

    public static synchronized Todo find(Context context, String id) {
        return find(all(context), id);
    }

    public static synchronized Todo add(Context context, String title, String detail, long remindAt) {
        return add(context, title, detail, remindAt, "");
    }

    public static synchronized Todo add(Context context, String title, String detail, long remindAt,
                                        String soundUri) {
        String cleanTitle = clip(title == null ? "" : title.trim(), MAX_TITLE);
        String cleanDetail = clip(detail == null ? "" : detail.trim(), MAX_DETAIL);
        if (cleanTitle.isEmpty() && cleanDetail.isEmpty()) {
            throw new IllegalArgumentException(T.s("待办不能为空"));
        }
        List<Todo> todos = all(context);
        if (todos.size() >= MAX_TODOS) {
            throw new IllegalStateException(T.s("待办已满，请先清掉一些已处理的"));
        }
        long now = System.currentTimeMillis();
        Todo todo = new Todo(UUID.randomUUID().toString(), cleanTitle, cleanDetail, Math.max(0L, remindAt), now);
        todo.soundUri = normalizeSoundUri(soundUri);
        todos.add(todo);
        if (!save(context, todos)) {
            throw new IllegalStateException(T.s("待办没能保存"));
        }
        return todo;
    }

    /** 只改没处理的待办；已处理的记录不该被改回去。 */
    public static synchronized boolean update(Context context, String id, String title, String detail, long remindAt) {
        List<Todo> current = all(context);
        Todo existing = find(current, id);
        return existing != null && update(context, id, title, detail, remindAt, existing.soundUri);
    }

    public static synchronized boolean update(Context context, String id, String title, String detail,
                                              long remindAt, String soundUri) {
        List<Todo> todos = all(context);
        Todo todo = find(todos, id);
        if (todo == null || !todo.pending()) {
            return false;
        }
        String cleanTitle = clip(title == null ? "" : title.trim(), MAX_TITLE);
        String cleanDetail = clip(detail == null ? "" : detail.trim(), MAX_DETAIL);
        if (cleanTitle.isEmpty() && cleanDetail.isEmpty()) {
            throw new IllegalArgumentException(T.s("待办不能为空"));
        }
        todo.title = cleanTitle;
        todo.detail = cleanDetail;
        long next = Math.max(0L, remindAt);
        if (next != todo.remindAt) {
            todo.remindedAt = 0L;
        }
        todo.remindAt = next;
        todo.soundUri = normalizeSoundUri(soundUri);
        todo.updatedAt = System.currentTimeMillis();
        return save(context, todos);
    }

    public static synchronized boolean complete(Context context, String id, long now) {
        return resolve(context, id, STATUS_DONE, now);
    }

    /** Reschedule only pending items, preserving content and completion history. */
    static Todo deferred(Todo original,long minutes,long now){
        if(original==null||!original.pending())return null;
        if(minutes<1||minutes>525600||now>Long.MAX_VALUE-minutes*60000L)
            throw new IllegalArgumentException("1–525600");
        return deferredUntil(original,now+minutes*60000L,now);
    }
    static Todo deferredUntil(Todo original,long at,long now){
        if(original==null||!original.pending())return null;
        if(at<=now)throw new IllegalArgumentException("Future reminder required");
        Todo next=original.copy();next.remindAt=at;
        next.remindedAt=0;next.updatedAt=now;return next;
    }
    public static synchronized Todo postpone(Context context,String id,long minutes,long now){
        List<Todo> todos=all(context);Todo old=find(todos,id),next=deferred(old,minutes,now);
        if(next==null)return null;
        todos.set(todos.indexOf(old),next);
        return save(context,todos)?next:null;
    }
    public static synchronized Todo postponeUntil(Context context,String id,long at,long now){
        List<Todo> todos=all(context);Todo old=find(todos,id),next=deferredUntil(old,at,now);
        if(next==null)return null;
        todos.set(todos.indexOf(old),next);
        return save(context,todos)?next:null;
    }

    public static synchronized boolean cancel(Context context, String id, long now) {
        return resolve(context, id, STATUS_CANCELLED, now);
    }

    private static boolean resolve(Context context, String id, int status, long now) {
        List<Todo> todos = all(context);
        Todo todo = find(todos, id);
        if (todo == null || !todo.pending()) {
            return false;
        }
        todo.status = status;
        todo.resolvedAt = now;
        todo.updatedAt = now;
        return save(context, todos);
    }

    public static synchronized boolean remove(Context context, String id) {
        List<Todo> todos = all(context);
        Todo todo = find(todos, id);
        if (todo == null) {
            return false;
        }
        todos.remove(todo);
        return save(context, todos);
    }

    /** 清掉所有已处理的记录；没处理的保留。 */
    public static synchronized int clearResolved(Context context) {
        List<Todo> todos = all(context);
        int before = todos.size();
        for (int index = todos.size() - 1; index >= 0; index--) {
            if (!todos.get(index).pending()) {
                todos.remove(index);
            }
        }
        if (todos.size() == before) {
            return 0;
        }
        return save(context, todos) ? before - todos.size() : 0;
    }

    public static boolean reminderDue(Todo todo, long expected, long now) {
        return todo != null && todo.pending() && expected > 0L && todo.remindAt == expected
                && todo.remindedAt != expected && now >= expected;
    }

    /**
     * Keep persisted sound references within Android URI schemes the reminder can
     * reopen after a process restart. Missing or malformed legacy values fall back
     * to the system default rather than making the whole to-do unreadable.
     */
    static String normalizeSoundUri(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.trim();
        if (clean.isEmpty()) {
            return "";
        }
        if ("silent".equals(clean)) {
            return clean;
        }
        if (clean.length() > 2048) {
            return "";
        }
        int colon = clean.indexOf(':');
        if (colon <= 0 || colon == clean.length() - 1) {
            return "";
        }
        String scheme = clean.substring(0, colon).toLowerCase(Locale.US);
        char first = scheme.charAt(0);
        if (!((first >= 'a' && first <= 'z'))) {
            return "";
        }
        for (int index = 1; index < scheme.length(); index++) {
            char ch = scheme.charAt(index);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '+' || ch == '-' || ch == '.')) {
                return "";
            }
        }
        return "content".equals(scheme) || "file".equals(scheme) || "android.resource".equals(scheme)
                ? clean : "";
    }

    /** 通知只发一次：先把 remindedAt 记上，再去弹通知。 */
    public static synchronized Todo claimReminder(Context context, String id, long expected, long now) {
        List<Todo> todos = all(context);
        Todo todo = find(todos, id);
        if (!reminderDue(todo, expected, now)) {
            return null;
        }
        todo.remindedAt = expected;
        if (!save(context, todos)) {
            return null;
        }
        return todo.copy();
    }
}
