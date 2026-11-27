package com.afs.guard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class Phase3VisualCaptureTest {
    @Test public void captureStatsAndDiagnostics() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getExternalFilesDir(null), "phase3-captures");
        assertTrue(directory.isDirectory() || directory.mkdirs());

        capture(StatsActivity.class, context, new File(directory, "stats.png"), 5_000L, false,
                "专注与守护", "今日专注");
        capture(DiagnosticsActivity.class, context,
                new File(directory, "diagnostics.png"), 5_000L, true,
                "系统健康诊断", "本地数据库迁移");
        for (File file : directory.listFiles()) {
            assertTrue(file.getName(), file.length() > 0L);
        }
    }

    private static void capture(Class<? extends Activity> type, Context context,
                                File target, long settleMs, boolean scrollToBottom,
                                String... expectedText)
            throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            Thread.sleep(settleMs);
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                ScrollView scroll = findScrollView(activity.getWindow().getDecorView());
                if (scroll == null || scroll.getChildCount() == 0) return;
                int maximum = Math.max(0,
                        scroll.getChildAt(0).getHeight() - scroll.getHeight());
                scroll.scrollTo(0, scrollToBottom
                        ? Math.max(0, maximum - Math.round(
                                140f * activity.getResources().getDisplayMetrics().density))
                        : 0);
            });
            Thread.sleep(400L);
            instrumentation.waitForIdleSync();
            AtomicReference<Bitmap> capture = new AtomicReference<>();
            scenario.onActivity(activity -> {
                View decor = activity.getWindow().getDecorView();
                decor.measure(
                        View.MeasureSpec.makeMeasureSpec(decor.getWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(decor.getHeight(), View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, decor.getWidth(), decor.getHeight());
                for (String value : expectedText) {
                    assertTrue("Missing rendered text: " + value,
                            contains(decor, value));
                }
                Bitmap bitmap = Bitmap.createBitmap(
                        Math.max(1, decor.getWidth()), Math.max(1, decor.getHeight()),
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                decor.draw(canvas);
                capture.set(bitmap);
            });
            Bitmap bitmap = capture.get();
            if (bitmap == null) throw new AssertionError("screenshot unavailable");
            try (FileOutputStream out = new FileOutputStream(target)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                bitmap.recycle();
            }
        }
    }

    private static ScrollView findScrollView(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            ScrollView found = findScrollView(group.getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean contains(View view, String expected) {
        if (view instanceof TextView
                && ((TextView) view).getText().toString().contains(expected)) {
            return true;
        }
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (contains(group.getChildAt(index), expected)) return true;
        }
        return false;
    }
}
