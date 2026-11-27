package com.afs.guard

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import com.afs.style.fluidgradient.FluidComponents
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class EffectQualityDeviceTest {
    @Test fun measuredDegradationUpdatesVisibleRendererButRespectsManualTier() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val prefs = context.getSharedPreferences(Ui.SP, 0)
        val oldStyle = StyleManager.selected(context); val oldQuality = EffectQualityManager.preference(context)
        val reduced = prefs.getBoolean("reduce_motion", false)
        fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)
        fun eventually(condition: () -> Boolean) {
            val end = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < end) {
                var passed = false; main { passed = condition() }
                if (passed) return
                SystemClock.sleep(80)
            }
            fail("visible renderer did not follow measured quality")
        }
        try {
            main { prefs.edit().putBoolean("reduce_motion", false).commit(); StyleManager.select(context, "fluidgradient"); EffectQualityManager.setPreference(context, "high") }
            ActivityScenario.launch(SharedHomeActivity::class.java).use { scenario ->
                lateinit var activity: SharedHomeActivity
                scenario.onActivity { activity = it }
                eventually { (activity.componentRenderer as? FluidComponents)?.resources?.activeCount ?: 0 > 0 }
                val renderer = activity.componentRenderer as FluidComponents
                val model = activity.homeModel; val reads = activity.repository.readCount
                eventually { activity.observedFrames > 0 }
                main {
                    repeat(20) { EffectQualityManager.reportFrame(55) }
                    assertEquals(EffectQuality.HIGH, EffectQualityManager.resolve(context))
                    EffectQualityManager.setPreference(context, "auto")
                    repeat(10) { EffectQualityManager.reportFrame(55) }
                    assertNotEquals(EffectQuality.HIGH, EffectQualityManager.resolve(context))
                    repeat(10) { EffectQualityManager.reportFrame(55) }
                }
                eventually { EffectQualityManager.resolve(context) == EffectQuality.LOW && renderer.activeShaders == 0 && renderer.resources.activeCount <= 1 }
                main { assertSame(model, activity.homeModel); assertEquals(reads, activity.repository.readCount) }
            }
        } finally {
            main { StyleManager.select(context, oldStyle); EffectQualityManager.setPreference(context, oldQuality); prefs.edit().putBoolean("reduce_motion", reduced).commit() }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
}
