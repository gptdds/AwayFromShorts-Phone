package com.afs.guard

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import com.afs.style.fluidgradient.FluidComponents
import com.afs.style.liquidglass.LiquidGlassComponents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class UniverseMotionCaptureTest {
    @Test fun recordTapHoldDragFlickAndNavigation() {
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
                    var ready = false
                    val deadline = SystemClock.uptimeMillis() + 15000
                    while (!ready && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity {
                            ready = when (val renderer = it.componentRenderer) {
                                is LiquidGlassComponents -> renderer.activeShaders > 0
                                is FluidComponents -> renderer.activeShaders > 0
                                else -> false
                            }
                        }
                        if (!ready) SystemClock.sleep(100)
                    }
                    assertTrue("Missing material shader for $style", ready)
                    var down = SystemClock.uptimeMillis()
                    fun send(action: Int, horizontal: Float, vertical: Float) {
                        scenario.onActivity {
                            val decor = it.window.decorView
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                                decor.width * horizontal, decor.height * vertical, 0)
                            it.dispatchTouchEvent(event); event.recycle()
                        }
                    }
                    SystemClock.sleep(700)
                    send(MotionEvent.ACTION_DOWN, .35f, .28f)
                    send(MotionEvent.ACTION_UP, .35f, .28f)
                    SystemClock.sleep(650)
                    down = SystemClock.uptimeMillis()
                    send(MotionEvent.ACTION_DOWN, .48f, .29f)
                    SystemClock.sleep(1300)
                    repeat(12) { send(MotionEvent.ACTION_MOVE, .25f + it * .045f, .29f); SystemClock.sleep(35) }
                    send(MotionEvent.ACTION_UP, .78f, .29f)
                    SystemClock.sleep(800)
                    down = SystemClock.uptimeMillis()
                    send(MotionEvent.ACTION_DOWN, .2f, .31f)
                    repeat(5) { send(MotionEvent.ACTION_MOVE, .2f + it * .14f, .31f); SystemClock.sleep(8) }
                    send(MotionEvent.ACTION_UP, .85f, .31f)
                    SystemClock.sleep(1100)
                    down = SystemClock.uptimeMillis()
                    send(MotionEvent.ACTION_DOWN, .88f, .075f)
                    SystemClock.sleep(200)
                    send(MotionEvent.ACTION_UP, .88f, .075f)
                    SystemClock.sleep(700)
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    SystemClock.sleep(700)
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
