package com.afs.guard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.io.File

/** Optional screenshot channel, kept separate from functional/state acceptance. */
class StylePreviewCaptureTest {
    private fun snapshot(): Bitmap? {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.takeScreenshot()?.let { return it }
        // Emulator display capture can fail while SurfaceFlinger still supports system screencap.
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p")).use {
            BitmapFactory.decodeStream(it)
        }
    }
    @Test fun captureFourStylesAndSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val oldStyle = StyleManager.selected(context); val oldQuality = EffectQualityManager.preference(context)
        val prefs = context.getSharedPreferences(Ui.SP, 0)
        val reduced = prefs.getBoolean("reduce_motion", false)
        try {
            instrumentation.runOnMainSync { prefs.edit().putBoolean("reduce_motion", false).commit(); EffectQualityManager.setPreference(context, "high") }
            // Each visual reference starts in its requested style; the separate UI test covers live switching.
            for (style in listOf("paper", "liquidglass", "spatial3d", "fluidgradient")) {
                instrumentation.runOnMainSync { StyleManager.select(context, style) }
                ActivityScenario.launch(SharedHomeActivity::class.java).use { scenario ->
                    var ready = false
                    val deadline = SystemClock.uptimeMillis() + 15000
                    while (!ready && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity {
                            ready = it.componentRenderer?.id == style && !it.homeModel.state.value.loading && it.observedFrames >= 2
                            it.window.decorView.postInvalidateOnAnimation()
                        }
                        if (!ready) SystemClock.sleep(80)
                    }
                    check(ready) { "Requested style did not render completed frames: $style" }
                    SystemClock.sleep(500)
                    val file = File(context.filesDir, "preview-$style.png")
                    val image = snapshot()
                    check(image != null) { "Screenshot channel unavailable for $style" }
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
            }
            instrumentation.runOnMainSync { StyleManager.select(context, "liquidglass"); EffectQualityManager.setPreference(context, "low") }
            ActivityScenario.launch(ThemeActivity::class.java).use {
                SystemClock.sleep(1800)
                val image = snapshot()
                check(image != null) { "Screenshot channel unavailable for style options" }
                File(context.filesDir, "preview-style-options.png").outputStream().use { output ->
                    image.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                image.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { StyleManager.select(context, oldStyle); EffectQualityManager.setPreference(context, oldQuality); prefs.edit().putBoolean("reduce_motion", reduced).commit() }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }

    @Test fun captureNativeFluidCardsAfterLateLayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val oldStyle = StyleManager.selected(context)
        val oldQuality = EffectQualityManager.preference(context)
        val prefs = context.getSharedPreferences(Ui.SP, 0)
        val reduced = prefs.getBoolean("reduce_motion", false)
        try {
            instrumentation.runOnMainSync {
                prefs.edit().putBoolean("reduce_motion", false).commit()
                StyleManager.select(context, "fluidgradient")
                EffectQualityManager.setPreference(context, "high")
            }
            ActivityScenario.launch(MemoActivity::class.java).use { scenario ->
                val deadline = SystemClock.uptimeMillis() + 15000
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    scenario.onActivity {
                        ready = StyleManager.activeId() == "fluidgradient" &&
                            it.window.decorView.width > 0 && it.window.decorView.height > 0
                        it.window.decorView.postInvalidateOnAnimation()
                    }
                    if (ready) break
                    SystemClock.sleep(80)
                }
                SystemClock.sleep(600)
                val image = snapshot() ?: error("Screenshot channel unavailable for native fluid page")
                File(context.filesDir, "preview-native-fluid-memo.png").outputStream().use { output ->
                    image.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                image.recycle()
            }
        } finally {
            instrumentation.runOnMainSync {
                StyleManager.select(context, oldStyle)
                EffectQualityManager.setPreference(context, oldQuality)
                prefs.edit().putBoolean("reduce_motion", reduced).commit()
            }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
}
