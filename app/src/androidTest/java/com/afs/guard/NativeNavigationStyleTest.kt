package com.afs.guard

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.*
import com.afs.style.api.NavigationPainter
import com.afs.style.liquidglass.GlassNavigationPainter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.lang.ref.WeakReference

class NativeNavigationStyleTest {
    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun dock(activity: Activity): FloatingNav? {
        val type = Class.forName("com.afs.guard.LiveNavWindow")
        val windows = type.getDeclaredField("windows").apply { isAccessible = true }.get(null) as Map<*, *>
        val window = (windows[activity] as? WeakReference<*>)?.get() ?: return null
        return field(window, "nav") as? FloatingNav
    }
    @Test fun lensDrawsWithoutAViewOrEngineAndReleasedInstancesAreInert() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(220, 64, Bitmap.Config.ARGB_8888)
        val painter = GlassNavigationPainter()
        assertTrue(painter.draw(Canvas(bitmap), RectF(0f, 0f, 220f, 64f), RectF(70f, 4f, 150f, 60f),
            Ui(context).tokens(), EffectQuality.HIGH, 1f, .6f, .08f, 100f, true))
        assertNotEquals(bitmap.getPixel(15, 32), bitmap.getPixel(110, 32))
        painter.release()
        assertTrue(painter.isReleased)
        assertFalse(painter.draw(Canvas(bitmap), RectF(0f, 0f, 220f, 64f), null,
            Ui(context).tokens(), EffectQuality.LOW, 1f, 0f, 0f, 0f, true))
        bitmap.recycle()
    }
    @Test fun actualDockPreservesNavigationStateAndReleasesOrFallsBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bridge = StylePreferencesBridge.get(context)
        runBlocking { withTimeout(10000) { bridge.ready.first { it } } }
        val prefs = context.getSharedPreferences(Ui.SP, 0)
        val oldStyle = StyleManager.selected(context); val oldQuality = EffectQualityManager.preference(context)
        val reduced = prefs.getBoolean("reduce_motion", false)
        val key = "qa_navigation_broken"
        fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)
        fun eventually(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false; main { ready = condition() }
                if (ready) return
                SystemClock.sleep(80)
            }
            fail(message)
        }
        try {
            main { prefs.edit().putBoolean("reduce_motion", false).commit(); EffectQualityManager.setPreference(context, "high"); StyleManager.select(context, "paper") }
            ActivityScenario.launch(FreezeBoardActivity::class.java).use { scenario ->
                lateinit var activity: FreezeBoardActivity
                lateinit var nav: FloatingNav
                scenario.onActivity { activity = it }
                eventually("main dock did not appear") {
                    val candidate = dock(activity)
                    if (candidate != null) nav = candidate
                    candidate != null && candidate.width > 0 && field(candidate, "glassVisible") == true
                }
                val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val rules = RuleStore.exportRules(context)
                val records = StudyStore.recs(context).size
                main { StyleManager.select(context, "liquidglass") }
                eventually("liquid navigation did not draw") { field(nav, "navigationPainter") is GlassNavigationPainter }
                val first = fieldOnMain<GlassNavigationPainter>(instrumentation, nav, "navigationPainter")
                main {
                    val rect = field(nav, "lensRect") as RectF
                    val now = SystemClock.uptimeMillis()
                    for ((action, x) in listOf(MotionEvent.ACTION_DOWN to rect.centerX(),
                        MotionEvent.ACTION_MOVE to (rect.centerX() + nav.width * .2f),
                        MotionEvent.ACTION_CANCEL to (rect.centerX() + nav.width * .2f))) {
                        MotionEvent.obtain(now, now + 50, action, x, nav.height * .5f, 0).also {
                            nav.onTouchEvent(it); it.recycle()
                        }
                    }
                    EffectQualityManager.setPreference(context, "reduced")
                }
                eventually("reduced motion kept a navigation frame loop") { field(nav, "framePosted") == false }
                main { StyleManager.select(context, "paper") }
                eventually("previous navigation painter was retained") { first.isReleased && field(nav, "navigationPainter") == null }
                var attempts = 0
                main {
                    StyleRegistry.register(object : RendererFactory {
                        override fun id() = key
                        override fun displayName(context: Context) = key
                        override fun create(): StyleRenderer = object : StyleRenderer by PaperStyle() { override fun id() = key }
                    })
                    StyleRegistry.registerCapability(key, NavigationPainter::class.java) {
                        attempts++; throw IllegalStateException("expected navigation module failure")
                    }
                    StyleManager.select(context, key)
                }
                eventually("broken navigation was not evaluated") { field(nav, "navigationStyle") == key && field(nav, "navigationAttempted") == true }
                main {
                    nav.invalidate()
                    assertNull(field(nav, "navigationPainter")); assertEquals(1, attempts)
                    assertFalse(activity.isFinishing)
                    assertSame(content, activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                    assertEquals(rules, RuleStore.exportRules(context)); assertEquals(records, StudyStore.recs(context).size)
                    StyleManager.select(context, "liquidglass")
                }
                eventually("fresh liquid painter did not attach") { field(nav, "navigationPainter") is GlassNavigationPainter }
                val second = fieldOnMain<GlassNavigationPainter>(instrumentation, nav, "navigationPainter")
                assertNotSame(first, second)
                scenario.moveToState(Lifecycle.State.CREATED)
                main { assertTrue(second.isReleased); assertEquals(false, field(nav, "framePosted")) }
            }
        } finally {
            main {
                StyleManager.select(context, oldStyle); EffectQualityManager.setPreference(context, oldQuality)
                prefs.edit().putBoolean("reduce_motion", reduced).commit()
                RegistryFixtures.remove(key)
            }
            runBlocking { withTimeout(10000) { bridge.awaitWrites() } }
        }
    }
    private fun <T> fieldOnMain(instrumentation: android.app.Instrumentation, owner: Any, name: String): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = field(owner, name) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
