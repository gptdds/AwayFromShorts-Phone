package com.afs.guard;

import java.util.ArrayList;
import java.util.List;

/** 待办卡的排序、到期、JSON 容错和通知去重，全是不碰设备的纯函数。 */
public final class TodoTest {

    private static int count;

    private static void check(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
        count++;
    }

    private static TodoStore.Todo todo(String id, String title, long remindAt, long created) {
        return new TodoStore.Todo(id, title, "", remindAt, created);
    }

    private static String ids(List<TodoStore.Todo> todos) {
        StringBuilder builder = new StringBuilder();
        for (TodoStore.Todo todo : todos) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(todo.id);
        }
        return builder.toString();
    }

    public static void main(String[] args) {
        List<TodoStore.Todo> all = new ArrayList<TodoStore.Todo>();
        TodoStore.Todo immediate = todo("a", "立即", 0L, 100L);
        TodoStore.Todo later = todo("b", "晚上", 5000L, 200L);
        TodoStore.Todo sooner = todo("c", "稍后", 3000L, 300L);
        TodoStore.Todo done = todo("d", "完成了", 0L, 50L);
        done.status = TodoStore.STATUS_DONE;
        done.resolvedAt = 900L;
        TodoStore.Todo cancelled = todo("e", "取消了", 1000L, 60L);
        cancelled.status = TodoStore.STATUS_CANCELLED;
        cancelled.resolvedAt = 800L;
        all.add(done);
        all.add(later);
        all.add(cancelled);
        all.add(immediate);
        all.add(sooner);

        List<TodoStore.Todo> ordered = TodoStore.order(all);
        check(ids(ordered).equals("a,c,b,d,e"), "pending first, immediate, then schedule time; resolved newest first");
        check(TodoStore.pending(all).size() == 3, "pending excludes resolved records");
        check(TodoStore.resolved(all).size() == 2, "resolved keeps completed and cancelled");
        check(ids(TodoStore.due(all, 3500L)).equals("a,c"), "due hides future reminders and resolved records");
        check(ids(TodoStore.due(all, 6000L)).equals("a,c,b"), "due adds a reminder once its time arrives");
        check(TodoStore.isDue(immediate, 0L), "immediate todo is due everywhere");
        check(!TodoStore.isDue(later, 4999L), "future todo is not due early");
        check(!TodoStore.isDue(done, 99999L), "resolved todo is never due");
        check(!TodoStore.isDue(null, 99999L), "null todo is safe");
        check(TodoStore.order(null).isEmpty(), "null list is safe");

        String before = TodoStore.dueSignature(all, 3500L);
        String after = TodoStore.dueSignature(all, 6000L);
        check(before.equals("a:0,c:3000"), "due signature lists due ids and times");
        check(!before.equals(after) && after.equals("a:0,c:3000,b:5000"), "due signature changes as cards arrive");

        TodoStore.Todo fallback = new TodoStore.Todo("f", "", "第一行\n第二行", 0L, 1L);
        check(TodoStore.displayTitle(fallback).equals("第一行"), "empty title falls back to the first detail line");
        check(TodoStore.displayTitle(null).equals("无标题待办"), "null todo has a stable title");
        check(TodoStore.oneLine(" a\n\tb   c ").equals("a b c"), "title text folds whitespace");

        String json = TodoStore.toJson(all);
        List<TodoStore.Todo> restored = TodoStore.fromJson(json);
        check(restored.size() == 5, "json round trip keeps all records");
        TodoStore.Todo restoredCancelled = TodoStore.find(restored, "e");
        check(restoredCancelled != null && restoredCancelled.status == TodoStore.STATUS_CANCELLED,
                "status survives json");
        check(restoredCancelled != null && restoredCancelled.resolvedAt == 800L, "resolved time survives json");

        String duplicates = "["
                + "{\"id\":\"x\",\"title\":\"one\",\"status\":0},"
                + "{\"id\":\"x\",\"title\":\"two\",\"status\":0},"
                + "{\"id\":\"y\",\"title\":\"\",\"detail\":\"\"},"
                + "{\"id\":\"z\",\"title\":\"" + new String(new char[TodoStore.MAX_TITLE + 20]).replace('\0', 'q') + "\"}"
                + "]";
        List<TodoStore.Todo> tolerant = TodoStore.fromJson(duplicates);
        check(tolerant.size() == 2, "duplicate ids and empty records are skipped");
        check(TodoStore.find(tolerant, "x").title.equals("one"), "first duplicate wins");
        check(TodoStore.find(tolerant, "z").title.length() == TodoStore.MAX_TITLE, "over-long titles are clipped");
        check(TodoStore.fromJson("not json").isEmpty(), "broken json becomes an empty list");

        List<TodoStore.Todo> empty = new ArrayList<TodoStore.Todo>();
        empty.add(null);
        empty.add(fallback);
        check(TodoStore.order(empty).size() == 1, "null records are dropped");

        TodoStore.Todo reminder = todo("r", "提醒", 5000L, 1L);
        reminder.soundUri = "content://media/internal/audio/media/42";
        check(TodoStore.reminderDue(reminder, 5000L, 5000L), "reminder is due exactly at its time");
        check(!TodoStore.reminderDue(reminder, 5000L, 4999L), "reminder is not due before its time");
        reminder.remindedAt = 5000L;
        check(!TodoStore.reminderDue(reminder, 5000L, 6000L), "same reminder never fires twice");
        check(!TodoStore.reminderDue(reminder, 4000L, 6000L), "stale pending intent does not match");

        TodoStore.Todo deferred=TodoStore.deferred(reminder,17,6000L);
        check(deferred.remindAt==1026000L,"custom minutes measured from confirmation");
        check(deferred.pending()&&deferred.resolvedAt==0,"delay never completes or cancels");
        check(deferred.remindedAt==0&&deferred.title.equals(reminder.title),"new reminder preserves content and resets dedup");
        check(deferred.soundUri.equals(reminder.soundUri),"new reminder preserves custom sound");
        check(reminder.remindAt==5000L,"preview does not mutate original");
        check(!TodoStore.isDue(deferred,1025999L)&&TodoStore.isDue(deferred,1026000L),"delayed card returns exactly at due time");
        check(!TodoStore.reminderDue(deferred,5000L,1026000L),"old alarm cannot notify deferred card");
        deferred.status=TodoStore.STATUS_DONE;
        check(TodoStore.deferred(deferred,5,6000L)==null,"resolved item cannot be delayed");
        for(long invalid:new long[]{0,-1,525601,Long.MAX_VALUE}){
            boolean rejected=false;try{TodoStore.deferred(reminder,invalid,6000L);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"invalid delay rejected");
        }
        TodoStore.Todo exact=TodoStore.deferredUntil(reminder,1234567L,6000L);
        check(exact.remindAt==1234567L,"custom clock time is exact, not rounded to relative minutes");
        check(exact.pending()&&exact.remindedAt==0,"custom clock preserves pending status");
        boolean pastRejected=false;try{TodoStore.deferredUntil(reminder,6000L,6000L);}catch(IllegalArgumentException expected){pastRejected=true;}
        check(pastRejected,"past custom times rejected");

        TodoStore.Todo sounding = todo("s", "声音", 8000L, 2L);
        sounding.soundUri = "content://media/internal/audio/media/7";
        String soundJson = TodoStore.toJson(java.util.Collections.singletonList(sounding));
        TodoStore.Todo soundRestored = TodoStore.find(TodoStore.fromJson(soundJson), "s");
        check(soundRestored != null && soundRestored.soundUri.equals(sounding.soundUri),
                "custom sound survives json");
        check(sounding.copy().soundUri.equals(sounding.soundUri), "copy preserves custom sound");

        String soundMatrix = "["
                + "{\"id\":\"legacy\",\"title\":\"old\"},"
                + "{\"id\":\"default\",\"title\":\"default\",\"soundUri\":\"\"},"
                + "{\"id\":\"silent\",\"title\":\"silent\",\"soundUri\":\"silent\"},"
                + "{\"id\":\"file\",\"title\":\"file\",\"soundUri\":\"file:///sdcard/alarm.ogg\"},"
                + "{\"id\":\"resource\",\"title\":\"resource\",\"soundUri\":\"android.resource://com.afs.guard/1\"},"
                + "{\"id\":\"bad\",\"title\":\"bad\",\"soundUri\":\"https://example.com/a.mp3\"}"
                + "]";
        List<TodoStore.Todo> sounds = TodoStore.fromJson(soundMatrix);
        check(TodoStore.find(sounds, "legacy").soundUri.equals(""), "legacy record defaults to system sound");
        check(TodoStore.find(sounds, "silent").soundUri.equals("silent"), "silent sound survives json");
        check(TodoStore.find(sounds, "file").soundUri.startsWith("file://"), "file sound URI survives json");
        check(TodoStore.find(sounds, "resource").soundUri.startsWith("android.resource://"),
                "resource sound URI survives json");
        check(TodoStore.find(sounds, "bad").soundUri.equals(""), "unsupported sound scheme falls back");
        check(TodoStore.normalizeSoundUri(null).equals(""), "null sound falls back");
        check(TodoStore.normalizeSoundUri("javascript:alert(1)").equals(""), "script URI is rejected");
        String tooLong = "content://" + new String(new char[TodoStore.MAX_DETAIL + 100]).replace('\0', 'a');
        check(TodoStore.normalizeSoundUri(tooLong).equals(""), "over-long sound URI falls back");
        System.out.println("Todo: " + count + " assertions passed");
    }
}
