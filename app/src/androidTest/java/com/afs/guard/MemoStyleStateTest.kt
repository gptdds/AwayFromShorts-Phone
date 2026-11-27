package com.afs.guard

import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Uses the actual memo editor, its undo history and Markdown preview, rather than a probe form. */
class MemoStyleStateTest {
    private fun field(page: MemoActivity, name: String): Any? =
        MemoActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(page)
    @Test fun fourStylesKeepRealDraftSelectionUndoHistoryAndPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val oldStyle = StyleManager.selected(context)
        val oldQuality = EffectQualityManager.preference(context)
        val prefs = context.getSharedPreferences("afs_guard", 0)
        val notes = prefs.getString("memos_v1", null)
        val rules = RuleStore.exportRules(context)
        val styles = StyleRegistry.all().map { it.id() }
        assertEquals(4, styles.size)
        fun select(id: String) {
            instrumentation.runOnMainSync { StyleManager.select(context, id) }
            val deadline = SystemClock.uptimeMillis() + 10000
            while (StyleManager.activeId() != id && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40)
            assertEquals(id, StyleManager.activeId())
        }
        try {
            instrumentation.runOnMainSync { EffectQualityManager.setPreference(context, "low") }
            ActivityScenario.launch<MemoActivity>(Intent(context, MemoActivity::class.java).putExtra("new", true)).use { scenario ->
                lateinit var title: EditText; lateinit var body: EditText
                var content: Any? = null; var history: Any? = null
                scenario.onActivity { page ->
                    title = field(page, "titleInput") as EditText
                    body = field(page, "bodyInput") as EditText
                    title.setText("四风格草稿")
                    body.setText("# 标题\n正文保持不变")
                    body.setSelection(2, 4)
                    content = page.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    history = field(page, "history")
                }
                for (style in styles) {
                    select(style)
                    scenario.onActivity { page ->
                        assertSame(content, page.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                        assertSame(title, field(page, "titleInput")); assertSame(body, field(page, "bodyInput"))
                        assertSame(history, field(page, "history"))
                        assertEquals("四风格草稿", title.text.toString())
                        assertEquals("# 标题\n正文保持不变", body.text.toString())
                        assertEquals(2, body.selectionStart); assertEquals(4, body.selectionEnd)
                        assertFalse(page.isFinishing)
                    }
                }
                scenario.onActivity { page ->
                    MemoActivity::class.java.getDeclaredMethod("togglePreview").apply { isAccessible = true }.invoke(page)
                    assertEquals(true, field(page, "previewing"))
                }
                for (style in styles) {
                    select(style)
                    scenario.onActivity { page ->
                        assertEquals(true, field(page, "previewing"))
                        assertSame(history, field(page, "history"))
                        assertEquals("# 标题\n正文保持不变", body.text.toString())
                    }
                }
                var formatted = ""
                scenario.onActivity { page ->
                    MemoActivity::class.java.getDeclaredMethod("togglePreview").apply { isAccessible = true }.invoke(page)
                    body.setSelection(2, 4)
                    MemoActivity::class.java.getDeclaredMethod("format", String::class.java, Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(page, "**", true)
                    formatted = body.text.toString()
                    assertNotEquals("# 标题\n正文保持不变", formatted)
                }
                for (style in styles) {
                    select(style)
                    scenario.onActivity { page ->
                        assertTrue((field(page, "undoButton") as android.view.View).performClick())
                        assertEquals("# 标题\n正文保持不变", body.text.toString())
                        assertTrue((field(page, "redoButton") as android.view.View).performClick())
                        assertEquals(formatted, body.text.toString())
                    }
                }
            }
            assertEquals(notes, prefs.getString("memos_v1", null))
            assertEquals(rules, RuleStore.exportRules(context))
        } finally {
            instrumentation.runOnMainSync { StyleManager.select(context, oldStyle); EffectQualityManager.setPreference(context, oldQuality) }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
}
