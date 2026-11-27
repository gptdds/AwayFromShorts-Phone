package com.afs.guard

import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import android.view.*
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import com.afs.style.api.NativeComponentLayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeMaterialIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun layer(): NativeComponentLayer? {
        val active = StyleManager::class.java.getDeclaredField("active").apply { isAccessible = true }.get(null) ?: return null
        val delegate = active.javaClass.getDeclaredField("delegate").apply { isAccessible = true }.get(active) ?: return null
        return delegate.javaClass.getDeclaredField("components").apply { isAccessible = true }.get(delegate) as? NativeComponentLayer
    }
    private fun waitFor(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            SystemClock.sleep(80)
        }
        fail(label)
    }
    private fun capture(page: ThemedActivity, name: String) {
        val bitmap = Bitmap.createBitmap(page.window.decorView.width, page.window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1); var result = -1
        instrumentation.runOnMainSync { PixelCopy.request(page.window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper())) }
        assertTrue(done.await(8, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        File(context.filesDir, "native-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (InstrumentationRegistry.getArguments().getString("exportScreenshots") == "status") {
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            val chunks = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).chunked(45000)
            chunks.forEachIndexed { index, data -> instrumentation.sendStatus(2, Bundle().apply {
                putString("capture_name", "native-$name.jpg"); putInt("capture_part", index); putInt("capture_parts", chunks.size); putString("capture_data", data)
            }) }
        }
        bitmap.recycle()
    }
    private fun freezeCards(page: ThemedActivity): List<View> {
        val cards = mutableListOf<View>()
        fun visit(view: View) {
            if (view is ViewGroup) {
                if ((0 until view.childCount).any { view.getChildAt(it) is FreezeArtwork }) cards += view
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(page.window.decorView)
        return cards
    }
    /** Copies the actual window at reduced resolution; timestamps retain real playback speed. */
    private fun recordMotion(page: ThemedActivity, style: String) {
        if (InstrumentationRegistry.getArguments().getString("recordMotion") != "status") return
        val root = page.window.decorView
        val bitmap = Bitmap.createBitmap(600, 600 * root.height / root.width, Bitmap.Config.ARGB_8888)
        val position = IntArray(2); val origin = IntArray(2)
        var x = 0f; var y = 0f; var width = 0
        instrumentation.runOnMainSync {
            val card = freezeCards(page).first()
            card.getLocationOnScreen(position); root.getLocationOnScreen(origin)
            x = position[0] - origin[0] + card.width * .5f
            y = position[1] - origin[1] + card.height * .42f
            width = card.width
        }
        var down = 0L
        try {
            repeat(100) { frame ->
                val start = SystemClock.uptimeMillis()
                val action = when (frame) {
                    12 -> MotionEvent.ACTION_DOWN
                    in 28..55 -> MotionEvent.ACTION_MOVE
                    56 -> MotionEvent.ACTION_CANCEL
                    else -> -1
                }
                if (action != -1) instrumentation.runOnMainSync {
                    if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
                    val offset = if (frame >= 28) ((frame.coerceAtMost(55) - 28) / 27f) * width * .25f else 0f
                    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + offset, y, 0).also {
                        page.dispatchTouchEvent(it); it.recycle()
                    }
                }
                val ready = CountDownLatch(1); var result = -1
                instrumentation.runOnMainSync {
                    PixelCopy.request(page.window, bitmap, { result = it; ready.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue(ready.await(8, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
                val timestamp = SystemClock.uptimeMillis()
                val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
                val chunks = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).chunked(45000)
                chunks.forEachIndexed { index, data -> instrumentation.sendStatus(2, Bundle().apply {
                    putString("capture_name", "native-motion-$style-%04d.jpg".format(frame))
                    putInt("capture_part", index); putInt("capture_parts", chunks.size)
                    putLong("capture_timestamp", timestamp); putString("capture_data", data)
                }) }
                val remaining = 80 - (SystemClock.uptimeMillis() - start)
                if (remaining > 0) SystemClock.sleep(remaining)
            }
        } finally {
            instrumentation.runOnMainSync {
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y, 0).also {
                    page.dispatchTouchEvent(it); it.recycle()
                }
            }
            bitmap.recycle()
        }
    }
    @Test fun realPagesUseMaterialsAndKeepDraftRulesAndNativeBackground() {
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val oldStyle = StyleManager.selected(context); val oldQuality = EffectQualityManager.preference(context)
        val prefs = context.getSharedPreferences(Ui.SP, 0); val oldReduced = prefs.getBoolean("reduce_motion", false)
        val rules = RuleStore.exportRules(context)
        try {
            instrumentation.runOnMainSync { prefs.edit().putBoolean("reduce_motion", false).commit() }
            for (style in listOf("liquidglass", "fluidgradient")) {
                instrumentation.runOnMainSync { StyleManager.select(context, style); EffectQualityManager.setPreference(context, "low") }
                for (type in listOf(SettingsActivity::class.java, FreezeBoardActivity::class.java, CheckinActivity::class.java, MemoActivity::class.java)) {
                    ActivityScenario.launch<ThemedActivity>(Intent(context, type).putExtra("new", true)).use { scenario ->
                        lateinit var page: ThemedActivity
                        scenario.onActivity { page = it; it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                        fun main(block: (ThemedActivity) -> Unit) = instrumentation.runOnMainSync { block(page) }
                        main { EffectQualityManager.setPreference(context, "high") }
                        waitFor("Native material not ready: $style / ${type.simpleName}") { layer()?.let { it.componentCount() > 0 && (Build.VERSION.SDK_INT < 33 || it.shaderReady()) } == true }
                        val current = layer()!!
                        if (type == FreezeBoardActivity::class.java) {
                            main {
                                val cards = freezeCards(it)
                                assertEquals("Both freeze overview cards must be present", 2, cards.size)
                                cards.forEach { card -> assertTrue("Overview card missed native material", current.hasMaterial(card)) }
                            }
                            recordMotion(page, style)
                        }
                        main {
                            val root = it.window.decorView
                            val before = current.observedPointerEvents()
                            val now = SystemClock.uptimeMillis()
                            for ((action, x) in listOf(MotionEvent.ACTION_DOWN to root.width*.5f, MotionEvent.ACTION_MOVE to root.width*.6f, MotionEvent.ACTION_CANCEL to root.width*.6f)) {
                                MotionEvent.obtain(now,now+action+1,action,x,root.height*.5f,0).also { event -> it.dispatchTouchEvent(event);event.recycle() }
                            }
                            assertEquals("Visual pointer stream lost or duplicated",before+3,current.observedPointerEvents())
                        }
                        var background: android.graphics.drawable.Drawable? = null
                        var backgroundView: View? = null
                        var content: View? = null
                        main {
                            val root = it.findViewById<ViewGroup>(android.R.id.content)
                            content = root.getChildAt(0)
                            fun find(v: View) {
                                if (backgroundView == null && v.getTag(R.id.visual_page) != null) backgroundView = v
                                if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
                            }
                            find(root); assertNotNull("Page semantic background missing", backgroundView)
                            background = backgroundView!!.background
                        }
                        SystemClock.sleep(350)
                        capture(page, "$style-${type.simpleName}")
                        instrumentation.runOnMainSync { EffectQualityManager.setPreference(context, "reduced") }
                        waitFor("Previous material retained resources") { current.isClosed }
                        waitFor("Reduced material did not attach") { layer() != null && layer() !== current }
                        main {
                            assertSame(content, it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                            assertSame(background, backgroundView!!.background)
                            assertFalse(layer()!!.frameScheduled())
                            if (it is MemoActivity) {
                                val body = MemoActivity::class.java.getDeclaredField("bodyInput").apply { isAccessible = true }.get(it) as EditText
                                body.setText("# 材质接入测试\n草稿保持"); body.setSelection(2, 4)
                                StyleManager.select(context, "paper")
                            }
                        }
                        if (type == MemoActivity::class.java) {
                            waitFor("Paper fallback not restored") { StyleManager.activeId() == "paper" }
                            main {
                                val body = MemoActivity::class.java.getDeclaredField("bodyInput").apply { isAccessible = true }.get(it) as EditText
                                assertEquals("# 材质接入测试\n草稿保持", body.text.toString()); assertEquals(2, body.selectionStart); assertEquals(4, body.selectionEnd)
                            }
                        }
                        instrumentation.runOnMainSync { EffectQualityManager.setPreference(context, "low"); StyleManager.select(context, style) }
                    }
                    assertTrue("Activity exit retained native materials", layerOrNull() == null)
                }
            }
            assertEquals(rules, RuleStore.exportRules(context))
        } finally {
            instrumentation.runOnMainSync { StyleManager.select(context, oldStyle); EffectQualityManager.setPreference(context, oldQuality); prefs.edit().putBoolean("reduce_motion", oldReduced).commit() }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
    private fun layerOrNull(): NativeComponentLayer? = runCatching { layer() }.getOrNull()
}
