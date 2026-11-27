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

class UniverseWindowRecordingTest {
    @Test fun captureOnlyThisWindowWithRealTimestamps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val oldStyle = StyleManager.selected(context)
        val oldQuality = EffectQualityManager.preference(context)
        try {
            for (style in listOf("liquidglass", "fluidgradient")) {
                instrumentation.runOnMainSync { StyleManager.select(context, style); EffectQualityManager.setPreference(context, "high") }
                ActivityScenario.launch(SharedHomeActivity::class.java).use { scenario ->
                    SystemClock.sleep(2000)
                    lateinit var activity: SharedHomeActivity
                    scenario.onActivity { activity = it }
                    var ready = false
                    val deadline = SystemClock.uptimeMillis() + 15000
                    while (!ready && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity {
                            ready = when (val renderer = it.componentRenderer) {
                                is com.afs.style.liquidglass.LiquidGlassComponents -> renderer.activeShaders > 0
                                is com.afs.style.fluidgradient.FluidComponents -> renderer.activeShaders > 0
                                else -> false
                            }
                        }
                        if (!ready) SystemClock.sleep(80)
                    }
                    assertTrue("Actual shader unavailable for recording $style", ready)
                    val folder = File(context.filesDir, "capture-$style").apply { mkdirs() }
                    val times = StringBuilder()
                    var firstPixels: IntArray? = null
                    var changed = 0
                    var down = SystemClock.uptimeMillis()
                    repeat(40) { frame ->
                        val action = when (frame) { 4 -> MotionEvent.ACTION_DOWN; in 5..20 -> MotionEvent.ACTION_MOVE; 21 -> MotionEvent.ACTION_UP; else -> -1 }
                        if (action >= 0) scenario.onActivity {
                            val decor = it.window.decorView
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                                decor.width * (.25f + (frame - 4).coerceIn(0, 16) * .03f), decor.height * .58f, 0)
                            it.dispatchTouchEvent(event); event.recycle()
                        }
                        val bitmap = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                        val latch = CountDownLatch(1)
                        var result = -1
                        instrumentation.runOnMainSync { PixelCopy.request(activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper())) }
                        assertTrue(latch.await(8, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
                        val timestamp = SystemClock.uptimeMillis()
                        val pixels = IntArray(40) { bitmap.getPixel((bitmap.width * ((it % 8 + 1) / 10f)).toInt(), (bitmap.height * ((it / 8 + 2) / 10f)).toInt()) }
                        if (firstPixels == null) firstPixels = pixels else if (!firstPixels!!.contentEquals(pixels)) changed++
                        val name = "frame-%03d.jpg".format(frame)
                        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        bitmap.recycle()
                        times.append(name).append(',').append(timestamp).append('\n')
                        SystemClock.sleep(90)
                    }
                    File(folder, "timestamps.csv").writeText(times.toString())
                    assertTrue("Rendered scene stayed static: $style", changed >= 10)
                }
            }
        } finally {
            instrumentation.runOnMainSync { StyleManager.select(context, oldStyle); EffectQualityManager.setPreference(context, oldQuality) }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
}
