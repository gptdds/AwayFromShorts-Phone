package com.afs.guard

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import android.view.PixelCopy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle

class ComponentLabTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun dialogKeepsLiveGpuSurface() {
        val intent = Intent(instrumentation.targetContext, ComponentLabActivity::class.java).putExtra("lab_static", true)
        ActivityScenario.launch<ComponentLabActivity>(intent).use { scenario ->
            lateinit var activity: ComponentLabActivity
            scenario.onActivity { activity = it }
            compose.waitUntil(15000) { activity.currentEngine?.shaderReady == true }
            compose.onNodeWithTag("category-2").performClick()
            compose.onNodeWithTag("open-dialog").performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue("Dialog closed the shared GPU material", activity.currentEngine!!.shaderReady)
            capture(activity, "glass-dialog-static")
            compose.onNodeWithTag("cancel-dialog").performClick()
        }
    }
    private fun capture(activity: ComponentLabActivity, name: String) {
        compose.waitForIdle()
        if (android.os.Build.VERSION.SDK_INT >= 30 && name.contains("input")) {
            compose.waitUntil(5000) { activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            SystemClock.sleep(350)
        }
        val window = if (name.contains("dialog")) activity.dialogWindow!! else activity.window
        if (name.contains("dialog")) {
            compose.waitUntil(5000) { window.decorView.hasWindowFocus() }
            SystemClock.sleep(250)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            File(instrumentation.targetContext.filesDir, "components-$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            return
        }
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var status = -1
        instrumentation.runOnMainSync { PixelCopy.request(window, bitmap, { status = it; latch.countDown() }, Handler(Looper.getMainLooper())) }
        assertTrue(latch.await(8, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status)
        var black = 0
        for (index in 1..20) if (Color.red(bitmap.getPixel(5, bitmap.height * index / 22)) < 3) black++
        File(instrumentation.targetContext.filesDir, "components-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!name.contains("dialog")) assertTrue("The lab must have a plain black stage ($name, black samples=$black)", black >= 18)
        bitmap.recycle()
    }

    @Test fun bothSkinsKeepStateAndAllControlsOperateOnBlack() {
        val context = instrumentation.targetContext
        val intent = Intent(context, ComponentLabActivity::class.java).putExtra("lab_static", true)
        ActivityScenario.launch<ComponentLabActivity>(intent).use { scenario ->
            lateinit var activity: ComponentLabActivity
            scenario.onActivity { activity = it }
            compose.waitUntil(15000) { activity.currentEngine?.shaderReady == true }
            for (style in listOf("glass", "fluid")) {
                compose.onNodeWithTag("style-$style").performClick()
                compose.waitUntil(15000) { activity.currentEngine?.shaderReady == true }
                compose.onNodeWithTag("category-0").performClick()
                compose.onNodeWithTag("primary-action").performClick()
                compose.onNodeWithTag("action-count").assertTextContains("1", substring = true)
                compose.onNodeWithTag("dismiss-notice").performClick()
                compose.onNodeWithTag("icon-action").performClick()
                compose.onNodeWithTag("action-count").assertTextContains("2", substring = true)
                compose.onNodeWithTag("dismiss-notice").performClick()
                compose.onNodeWithTag("secondary-action").performClick()
                compose.onNodeWithTag("dismiss-notice").performClick()
                capture(activity, "$style-actions-static")
                compose.onNodeWithTag("tag-2").performScrollTo().performClick().assertIsSelected()
                compose.onNodeWithTag("choice-2").performScrollTo().performClick().assertIsSelected()
                compose.onNodeWithTag("category-1").performClick()
                compose.onNodeWithTag("lab-search").performTextInput("test")
                compose.onNodeWithTag("lab-note").performTextInput("保存组件草稿")
                instrumentation.runOnMainSync {
                    (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                compose.onNodeWithTag("reminder-switch").performScrollTo().performClick()
                compose.onNodeWithTag("duration-slider").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
                compose.onNodeWithTag("duration-value").assertTextContains("45", substring = true)
                compose.onNodeWithTag("style-${if (style == "glass") "fluid" else "glass"}").performScrollTo().performClick()
                compose.onNodeWithTag("lab-search").assertTextContains("test")
                compose.onNodeWithTag("style-$style").performClick()
                compose.waitUntil(15000) { activity.currentEngine?.shaderReady == true }
                compose.onNodeWithTag("lab-search").performTextClearance()
                instrumentation.runOnMainSync {
                    (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                capture(activity, "$style-input-static")
                compose.onNodeWithTag("category-2").performClick()
                compose.onNodeWithTag("list-row-1").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.waitUntil(3000) { compose.onNodeWithTag("list-row-1").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false } }
                compose.onNodeWithTag("list-row-1").assertIsSelected()
                capture(activity, "$style-list-static")
                compose.onNodeWithTag("list-row-1").performSemanticsAction(SemanticsActions.OnClick) { it() }
                compose.onNodeWithTag("list-row-1").assertIsNotSelected()
                compose.onNodeWithTag("open-menu").performScrollTo().performClick()
                compose.onNodeWithTag("popup-menu").assertIsDisplayed()
                compose.onNodeWithText("收藏组件").performClick()
                compose.onNodeWithTag("notice").assertIsDisplayed()
                compose.onNodeWithTag("dismiss-notice").performClick()
                compose.onNodeWithTag("open-dialog").performScrollTo().performClick()
                compose.onNodeWithTag("lab-dialog").assertIsDisplayed()
                capture(activity, "$style-dialog-static")
                compose.onNodeWithTag("confirm-dialog").performClick()
                compose.onNodeWithTag("lab-dialog").assertDoesNotExist()
                compose.onNodeWithTag("dismiss-notice").performClick()
                compose.onNodeWithTag("floating-action").performClick()
                compose.onNodeWithTag("cancel-dialog").performClick()
                compose.onNodeWithTag("category-1").performClick()
                compose.onNodeWithTag("lab-note").performTextClearance()
                compose.onNodeWithTag("category-0").performClick()
            }
        }
    }

}

class ComponentLabMotionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun recordLiveComponentsAndReleaseResources() {
        val context = instrumentation.targetContext
        for (style in listOf("liquidglass", "fluidgradient")) {
            val intent = Intent(context, ComponentLabActivity::class.java).putExtra("lab_style", style)
            ActivityScenario.launch<ComponentLabActivity>(intent).use { scenario ->
                lateinit var activity: ComponentLabActivity
                scenario.onActivity { activity = it }
                val deadline = SystemClock.uptimeMillis() + 15000
                while (activity.currentEngine?.shaderReady != true && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
                assertTrue(activity.currentEngine?.shaderReady == true)
                SystemClock.sleep(600)
                val width = activity.window.decorView.width.toFloat()
                val height = activity.window.decorView.height.toFloat()
                var down = 0L
                fun touch(action: Int, x: Float, y: Float) {
                    if (action == android.view.MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
                    instrumentation.runOnMainSync {
                        val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                        activity.dispatchTouchEvent(event); event.recycle()
                    }
                }
                fun tap(x: Float, y: Float) { touch(0, x, y); touch(1, x, y) }
                val folder = File(context.filesDir, "capture-components-$style").apply { mkdirs() }
                val times = StringBuilder()
                repeat(64) { frame ->
                    when (frame) {
                        5 -> touch(0, width * .3f, height * .455f)
                        in 6..13 -> touch(2, width * (.3f + (frame - 5) * .004f), height * .455f)
                        14 -> touch(1, width * .332f, height * .455f)
                        20 -> tap(width * .88f, height * .17f)
                        24 -> tap(width * .5f, height * .93f)
                        32 -> touch(0, width * .5f, height * .7f)
                        in 33..42 -> touch(2, width * .5f, height * (.7f - (frame - 32) * .025f))
                        43 -> touch(1, width * .5f, height * .45f)
                        54 -> tap(width * .77f, height * .93f)
                        58 -> tap(width * .4f, height * .65f)
                    }
                    val bitmap = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                    val latch = CountDownLatch(1)
                    var result = -1
                    instrumentation.runOnMainSync { PixelCopy.request(activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper())) }
                    assertTrue(latch.await(8, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
                    val timestamp = SystemClock.uptimeMillis()
                    val name = "frame-%03d.jpg".format(frame)
                    File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    bitmap.recycle()
                    times.append(name).append(',').append(timestamp).append('\n')
                    SystemClock.sleep(70)
                }
                File(folder, "timestamps.csv").writeText(times.toString())
                val engine = activity.currentEngine!!
                instrumentation.runOnMainSync { activity.finish() }
                val cleanupDeadline = SystemClock.uptimeMillis() + 5000
                while (engine.resources.activeCount != 0 && SystemClock.uptimeMillis() < cleanupDeadline) SystemClock.sleep(50)
                assertEquals(0, engine.resources.activeCount)
            }
        }
    }
}
