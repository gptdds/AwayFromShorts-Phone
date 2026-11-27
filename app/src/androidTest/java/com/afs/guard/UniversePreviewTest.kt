package com.afs.guard

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UniversePreviewTest {
    @Test fun captureWindowAndRealDragWithoutScreenAutomation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val oldStyle = StyleManager.selected(context)
        val oldQuality = EffectQualityManager.preference(context)
        try {
            for (style in listOf("liquidglass", "fluidgradient")) {
                instrumentation.runOnMainSync {
                    StyleManager.select(context, style)
                    EffectQualityManager.setPreference(context, "high")
                }
                ActivityScenario.launch(SharedHomeActivity::class.java).use { scenario ->
                    SystemClock.sleep(1800)
                    lateinit var activity: SharedHomeActivity
                    scenario.onActivity { activity = it }
                    val model = activity.homeModel
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        var shaders = 0
                        val deadline = SystemClock.uptimeMillis() + 15000
                        while (shaders == 0 && SystemClock.uptimeMillis() < deadline) {
                            scenario.onActivity {
                                shaders = when (val renderer = it.componentRenderer) {
                                    is com.afs.style.liquidglass.LiquidGlassComponents -> renderer.activeShaders
                                    is com.afs.style.fluidgradient.FluidComponents -> renderer.activeShaders
                                    else -> 0
                                }
                            }
                            if (shaders == 0) SystemClock.sleep(80)
                        }
                        assertTrue("Actual material shader is unavailable for $style", shaders > 0)
                    }
                    val before = model.state.value.query
                    val down = SystemClock.uptimeMillis()
                    for (step in 0..8) {
                        scenario.onActivity {
                            val decor = it.window.decorView
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                                if (step == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                                decor.width * (.15f + step * .07f), decor.height * .8f, 0)
                            it.dispatchTouchEvent(event)
                            event.recycle()
                        }
                        SystemClock.sleep(45)
                    }
                    val bitmap = Bitmap.createBitmap(activity.window.decorView.width,
                        activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                    val copied = CountDownLatch(1)
                    var result = -1
                    instrumentation.runOnMainSync {
                        PixelCopy.request(activity.window, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                    }
                    assertTrue("Window capture timed out", copied.await(8, TimeUnit.SECONDS))
                    assertEquals(PixelCopy.SUCCESS, result)
                    File(context.filesDir, "universe-$style.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                    scenario.onActivity {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                        it.dispatchTouchEvent(event); event.recycle()
                        assertSame(model, it.homeModel)
                        assertEquals(before, model.state.value.query)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                StyleManager.select(context, oldStyle)
                EffectQualityManager.setPreference(context, oldQuality)
            }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
}
