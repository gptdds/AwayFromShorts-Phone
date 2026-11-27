package com.afs.guard

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class VisualEntryTest {
    private fun find(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index), text)?.let { return it }
        return null
    }
    @Test fun originalSettingsOpensSharedHostAndReflectsItsChangesWithoutRebuilding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val oldStyle = StyleManager.selected(context); val oldQuality = EffectQualityManager.preference(context)
        val rules = RuleStore.exportRules(context)
        val monitor = instrumentation.addMonitor(SharedHomeActivity::class.java.name, null, false)
        var opened: Activity? = null
        try {
            instrumentation.runOnMainSync { StyleManager.select(context, "paper"); EffectQualityManager.setPreference(context, "low") }
            ActivityScenario.launch(ThemeActivity::class.java).use { scenario ->
                val preview = ThemeActivity::class.java.getDeclaredField("preview").apply { isAccessible = true }
                var originalPreview: Any? = null
                scenario.onActivity { activity ->
                    originalPreview = preview.get(activity)
                    val styleViews = ThemeActivity::class.java.getDeclaredField("skinOptionViews").apply { isAccessible = true }
                        .get(activity) as List<*>
                    assertEquals(4, styleViews.size)
                    for (option in styleViews) {
                        val cell = option as ViewGroup
                        val label = cell.getChildAt(0) as TextView
                        assertTrue(cell.width > activity.resources.displayMetrics.widthPixels / 3)
                        assertNotNull(label.layout)
                        for (line in 0 until label.layout.lineCount) assertEquals(0, label.layout.getEllipsisCount(line))
                    }
                    val entry = find(activity.window.decorView, "查看风格效果")
                    assertNotNull(entry); entry!!.performClick()
                }
                opened = instrumentation.waitForMonitorWithTimeout(monitor, 15000)
                assertTrue(opened is SharedHomeActivity)
                instrumentation.runOnMainSync { StyleManager.select(context, "spatial3d") }
                SystemClock.sleep(700)
                instrumentation.runOnMainSync { opened!!.finish() }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertSame(originalPreview, preview.get(activity))
                    assertNotNull(find(activity.window.decorView, "空间立体 ✓"))
                    assertEquals("spatial3d", StyleManager.activeId())
                }
            }
            assertEquals(rules, RuleStore.exportRules(context))
        } finally {
            instrumentation.runOnMainSync {
                opened?.let { if (!it.isFinishing) it.finish() }
                StyleManager.select(context, oldStyle); EffectQualityManager.setPreference(context, oldQuality)
            }
            instrumentation.removeMonitor(monitor)
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
}
