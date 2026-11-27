package com.afs.guard

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.core.model.HomeUiAction
import com.afs.guard.style.*
import com.afs.style.api.ComponentContracts
import com.afs.style.fluidgradient.FluidComponents
import com.afs.style.liquidglass.LiquidGlassComponents
import com.afs.style.spatial3d.SpatialComponents
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** HIGH uses real frame callbacks rather than Compose's idle clock, which continuous shaders never idle. */
class HighStyleSwitchTest {
    private fun leases(renderer: ComponentContracts?): Int = when (renderer) {
        is LiquidGlassComponents -> renderer.resources.activeCount
        is FluidComponents -> renderer.resources.activeCount
        is SpatialComponents -> renderer.resources.activeCount
        else -> 0
    }
    @Test fun fourHighStylesCompleteFramesKeepStateAndReleasePreviousRenderer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val prefs = context.getSharedPreferences(Ui.SP, 0)
        val oldStyle = StyleManager.selected(context); val oldQuality = EffectQualityManager.preference(context)
        val reduced = prefs.getBoolean("reduce_motion", false)
        val rules = RuleStore.exportRules(context)
        fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)
        fun eventually(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false; main { ready = condition() }
                if (ready) return
                SystemClock.sleep(80)
            }
            fail(label)
        }
        try {
            main { prefs.edit().putBoolean("reduce_motion", false).commit(); EffectQualityManager.setPreference(context, "high"); StyleManager.select(context, "paper") }
            ActivityScenario.launch(SharedHomeActivity::class.java).use { scenario ->
                lateinit var activity: SharedHomeActivity
                scenario.onActivity { activity = it }
                eventually("home did not load") { !activity.homeModel.state.value.loading }
                val model = activity.homeModel; val reads = activity.repository.readCount
                val query = model.state.value.apps.firstOrNull()?.id?.take(6) ?: "保留草稿"
                main { model.dispatch(HomeUiAction.Search(query)) }
                repeat(2) {
                    for (style in listOf("liquidglass", "fluidgradient", "spatial3d", "paper")) {
                        var previous: ComponentContracts? = null
                        var startingFrames = 0L
                        main { previous = activity.componentRenderer; startingFrames = activity.observedFrames; StyleManager.select(context, style) }
                        eventually("HIGH style did not finish frames: $style") {
                            activity.window.decorView.postInvalidateOnAnimation()
                            activity.componentRenderer?.id == style && activity.observedFrames >= startingFrames + 3
                        }
                        main {
                            assertEquals("previous style still owns effects", 0, leases(previous))
                            assertSame(model, activity.homeModel)
                            assertEquals(query, model.state.value.query)
                            assertEquals(reads, activity.repository.readCount)
                        }
                    }
                }
            }
            assertEquals(rules, RuleStore.exportRules(context))
        } finally {
            main { StyleManager.select(context, oldStyle); EffectQualityManager.setPreference(context, oldQuality); prefs.edit().putBoolean("reduce_motion", reduced).commit() }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
}
