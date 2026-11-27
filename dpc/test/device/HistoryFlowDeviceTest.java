package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Emulator-only UI coverage for every card outcome in the recent-activity timeline. */
public final class HistoryFlowDeviceTest extends Instrumentation {
    private int checks;
    private Activity history;

    private void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private void main(Runnable action) {
        Throwable[] failure = {null};
        runOnMainSync(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private Object field(Class<?> type, Object owner, String name) {
        try {
            Field value = type.getDeclaredField(name);
            value.setAccessible(true);
            return value.get(owner);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private void set(Class<?> type, Object owner, String name, Object value) {
        try {
            Field target = type.getDeclaredField(name);
            target.setAccessible(true);
            target.set(owner, value);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private TextView text(View view, String value) {
        if (view instanceof TextView && !(view instanceof EditText)
                && value.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                TextView found = text(((ViewGroup) view).getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean contains(View view, String value) {
        if (view instanceof TextView && !(view instanceof EditText)
                && ((TextView) view).getText().toString().contains(value)) {
            return true;
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                if (contains(((ViewGroup) view).getChildAt(i), value)) return true;
            }
        }
        return false;
    }

    private int countContains(View view, String value) {
        int count = view instanceof TextView && !(view instanceof EditText)
                && ((TextView) view).getText().toString().contains(value) ? 1 : 0;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                count += countContains(((ViewGroup) view).getChildAt(i), value);
            }
        }
        return count;
    }

    private String matchingRows(View view, String value) {
        StringBuilder rows = new StringBuilder();
        if (view instanceof TextView && !(view instanceof EditText)
                && ((TextView) view).getText().toString().contains(value)) {
            if (rows.length() > 0) rows.append(" || ");
            rows.append(((TextView) view).getText());
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                String child = matchingRows(((ViewGroup) view).getChildAt(i), value);
                if (!child.isEmpty()) {
                    if (rows.length() > 0) rows.append(" || ");
                    rows.append(child);
                }
            }
        }
        return rows.toString();
    }

    private byte[] read(File file) throws Exception {
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private void write(File file, byte[] bytes) throws Exception {
        if (bytes == null) {
            file.delete();
            return;
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    private void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof java.util.Set) {
                editor.putStringSet(entry.getKey(), (java.util.Set<String>) value);
            }
        }
        editor.commit();
    }

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Context context = getTargetContext();
        Bundle result = new Bundle();
        SharedPreferences setup = context.getSharedPreferences("afs_setup", 0);
        SharedPreferences taboo = context.getSharedPreferences(StudyGate.TABOO_SP, 0);
        SharedPreferences todo = context.getSharedPreferences("afs_todo", 0);
        Map<String, ?> oldSetup = new HashMap<>(setup.getAll());
        Map<String, ?> oldTaboo = new HashMap<>(taboo.getAll());
        Map<String, ?> oldTodo = new HashMap<>(todo.getAll());

        File studyFile = new File(context.getFilesDir(), "study.json");
        File memoryFile = new File(context.getFilesDir(), "memory-v1.json");
        File cardsFile = new File(context.getFilesDir(), "cards-v1.json");
        File quizFile = new File(context.getCacheDir(), "history-flow-qa-quiz.json");
        byte[] oldStudy = null;
        byte[] oldMemory = null;
        byte[] oldCards = null;
        Object oldMemoryCache = field(MemoryStore.class, null, "cache");
        Object oldMemoryPath = field(MemoryStore.class, null, "cachePath");
        Object oldCardsCache = field(CardStore.class, null, "cache");
        Object oldCardsPath = field(CardStore.class, null, "path");
        File oldQuiz = (File) field(QuizStore.class, null, "testFile");

        try {
            check(Build.MODEL.toLowerCase().contains("sdk"), "owned emulator only");
            oldStudy = read(studyFile);
            oldMemory = read(memoryFile);
            oldCards = read(cardsFile);
            setup.edit().putBoolean("guide_v1_seen", true).commit();

            long now = System.currentTimeMillis();
            String today = StudyStore.today();
            JSONArray goals = new JSONArray().put(new JSONObject()
                    .put("id", "qa-goal")
                    .put("name", "目标卡 QA")
                    .put("unit", "页")
                    .put("total", 10)
                    .put("base", 0)
                    .put("archived", false));
            JSONArray records = new JSONArray().put(new JSONObject()
                    .put("id", "qa-record")
                    .put("goalId", "qa-goal")
                    .put("date", today)
                    .put("amount", 1)
                    .put("ts", now + 8));
            write(studyFile, new JSONObject()
                    .put("goals", goals)
                    .put("recs", records)
                    .toString()
                    .getBytes("UTF-8"));

            MemoryData memory = new MemoryData();
            memory.db.optJSONArray("logs").put(new JSONObject()
                    .put("deck", memory.decks().optJSONObject(0).optString("id"))
                    .put("card", "qa-memory")
                    .put("ts", now + 1)
                    .put("rating", 3));
            main(() -> {
                set(MemoryStore.class, null, "cache", memory);
                set(MemoryStore.class, null, "cachePath", context.getFilesDir().getAbsolutePath());
            });

            QuizData quiz = new QuizData();
            quiz.db.optJSONObject("days").put(today, new JSONObject()
                    .put("attempts", 9)
                    .put("correct", 8));
            QuizStore.setTestFile(quizFile);
            QuizStore.commit(context, quiz);

            CardData cards = new CardData(now);
            cards.db.optJSONArray("love").put(new JSONObject()
                    .put("id", "qa-love")
                    .put("title", "热爱卡 QA")
                    .put("minutes", 15)
                    .put("archived", false));
            cards.earned().put(new JSONObject()
                    .put("id", "qa-ticket")
                    .put("card", "qa-love")
                    .put("day", today)
                    .put("at", now + 4)
                    .put("used", false));
            cards.db.optJSONObject("summary").optJSONObject("records").put("qa-summary",
                    new JSONObject().put("at", now + 5).put("label", "每周总结 QA"));
            main(() -> {
                set(CardStore.class, null, "cache", cards);
                set(CardStore.class, null, "path", cardsFile.getAbsolutePath());
            });

            taboo.edit()
                    .putString("log", new JSONArray().put(new JSONObject()
                            .put("name", "吃辣 QA").put("ts", now + 2)).toString())
                    .putString("declined_log", new JSONArray().put(new JSONObject()
                            .put("name", "吃甜 QA").put("ts", now + 3)).toString())
                    .putString("declined", new JSONObject().put("吃甜 QA", now + 3).toString())
                    .commit();

            List<TodoStore.Todo> todos = new ArrayList<>();
            TodoStore.Todo done = new TodoStore.Todo("qa-todo-done", "待办完成 QA", "", 0, now);
            done.status = TodoStore.STATUS_DONE;
            done.resolvedAt = now + 6;
            todos.add(done);
            TodoStore.Todo cancelled = new TodoStore.Todo("qa-todo-cancel", "待办取消 QA", "", 0, now);
            cancelled.status = TodoStore.STATUS_CANCELLED;
            cancelled.resolvedAt = now + 7;
            todos.add(cancelled);
            todo.edit().putString("todos_v1", TodoStore.toJson(todos)).commit();
            StudyGate.invalidate(context);

            history = startActivitySync(new Intent(context, HistoryActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            waitForIdleSync();
            check(history != null, "history activity opens");
            View root = history.findViewById(android.R.id.content);
            TextView toggle = text(root, "最近流水  ▸");
            check(toggle != null, "recent flow starts collapsed");
            main(toggle::performClick);
            LinearLayout flow = (LinearLayout) ((ViewGroup) toggle.getParent()).getChildAt(1);
            check(contains(flow, "总结卡 · 完成：每周总结 QA"), "summary card appears");
            check(contains(flow, "禁忌卡 · 认领：吃辣 QA"), "taboo claim appears");
            int declineRows = countContains(flow, "禁忌卡 · 没做：吃甜 QA");
            check(declineRows == 1,
                    "taboo decline appears once without legacy duplicate; rows=" + declineRows
                            + " text=" + matchingRows(flow, "禁忌卡 · 没做：吃甜 QA"));
            check(contains(flow, "记忆卡 · 复习：当天 1 张"), "memory card appears");
            check(contains(flow, "刷题卡 · 答题：今天 9 题"), "quiz card appears by day");
            check(contains(flow, "热爱卡 · 领取：热爱卡 QA"), "reward card appears");
            check(contains(flow, "目标卡 QA"), "target card appears");
            check(contains(flow, "待办卡 · 完成：待办完成 QA"), "completed todo appears");
            check(contains(flow, "待办卡 · 取消：待办取消 QA"), "cancelled todo appears");
            result.putString("stream", "History flow device: " + checks + " checks passed\n");
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
        } finally {
            if (history != null) main(history::finish);
            try {
                write(studyFile, oldStudy);
                write(memoryFile, oldMemory);
                write(cardsFile, oldCards);
            } catch (Exception error) {
                result.putString("stream", "RESTORE FAILED " + error);
            }
            main(() -> {
                set(MemoryStore.class, null, "cache", oldMemoryCache);
                set(MemoryStore.class, null, "cachePath", oldMemoryPath);
                set(CardStore.class, null, "cache", oldCardsCache);
                set(CardStore.class, null, "path", oldCardsPath);
            });
            QuizStore.setTestFile(oldQuiz);
            quizFile.delete();
            restore(setup, oldSetup);
            restore(taboo, oldTaboo);
            restore(todo, oldTodo);
            StudyGate.invalidate(context);
        }
        finish(result.getString("stream", "").startsWith("History flow device:") ? -1 : 0, result);
    }
}
