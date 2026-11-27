package com.afs.guard

import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import com.afs.style.api.VisualResources
import com.afs.style.fluidgradient.FluidComponents
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No Compose idling during animated HIGH: inspect real leases across Android lifecycle events. */
@RunWith(AndroidJUnit4::class)
class FluidLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)
    private fun eventually(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            var passed = false; main { passed = condition() }
            if (passed) return
            SystemClock.sleep(80)
        }
        fail(label)
    }
    @Test fun highShaderPausesResumesAndReleasesWithoutReload() {
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val oldStyle = StyleManager.selected(context)
        val oldQuality = EffectQualityManager.preference(context)
        val shaderQuality = if (
            Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)
        ) "medium" else "high"
        val prefs = context.getSharedPreferences(Ui.SP, 0)
        val reduced = prefs.getBoolean("reduce_motion", false)
        val rules = RuleStore.exportRules(context)
        var renderer: FluidComponents? = null
        try {
            main { prefs.edit().putBoolean("reduce_motion", false).commit(); StyleManager.select(context, "fluidgradient"); EffectQualityManager.setPreference(context, shaderQuality) }
            ActivityScenario.launch(SharedHomeActivity::class.java).use { scenario ->
                lateinit var activity: SharedHomeActivity
                scenario.onActivity { activity = it }
                eventually("fluid component did not become active") {
                    renderer = activity.componentRenderer as? FluidComponents
                    renderer != null && renderer!!.resources.resumed
                }
                if (Build.VERSION.SDK_INT >= 33) eventually("AGSL shader did not compile or attach") { renderer!!.activeShaders >= 1 }
                val model = activity.homeModel; val reads = activity.repository.readCount
                scenario.moveToState(Lifecycle.State.CREATED)
                main { assertEquals(0, renderer!!.resources.activeCount); assertEquals(0, renderer!!.activeShaders) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                if (Build.VERSION.SDK_INT >= 33) eventually("shader did not resume") { renderer!!.activeShaders >= 1 }
                main { assertSame(model, activity.homeModel); assertEquals(reads, activity.repository.readCount) }
                main { prefs.edit().putBoolean("reduce_motion", true).commit() }
                eventually("reduced motion did not override HIGH") {
                    EffectQualityManager.resolve(context) == EffectQuality.REDUCED_MOTION && renderer!!.activeShaders == 0 && renderer!!.resources.activeCount <= 1
                }
                main { prefs.edit().putBoolean("reduce_motion", false).commit() }
                if (Build.VERSION.SDK_INT >= 33) eventually("shader did not return after motion preference restored") { renderer!!.activeShaders >= 1 }
                main { EffectQualityManager.setPreference(context, "low") }
                eventually("LOW still holds shader/clock leases") { renderer!!.activeShaders == 0 && renderer!!.resources.activeCount <= 1 }
                val lowTime = renderer!!.animationTime
                SystemClock.sleep(250)
                main { assertEquals(lowTime, renderer!!.animationTime, .001f) }
                main { StyleManager.select(context, "paper") }
                eventually("minimal style did not replace fluid") { activity.componentRenderer?.id == "paper" }
                main { assertEquals(0, renderer!!.resources.activeCount); assertEquals(0, renderer!!.activeShaders) }
            }
            assertEquals(rules, RuleStore.exportRules(context))
        } finally {
            main { StyleManager.select(context, oldStyle); EffectQualityManager.setPreference(context, oldQuality); prefs.edit().putBoolean("reduce_motion", reduced).commit() }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
    @Test fun oneFailedCleanupDoesNotLeakTheOtherResources() {
        main {
            val resources = VisualResources(); resources.resume()
            var cleaned = false
            resources.register { throw IllegalStateException("expected cleanup failure") }
            resources.register { cleaned = true }
            resources.pause()
            assertTrue(cleaned); assertEquals(0, resources.activeCount)
            resources.release()
        }
    }
}
