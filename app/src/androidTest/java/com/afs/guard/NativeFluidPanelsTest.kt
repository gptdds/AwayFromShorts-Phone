package com.afs.guard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.EffectQuality
import com.afs.style.fluidgradient.nativeview.NativeFluidPanels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFluidPanelsTest {
    @Test fun bordersLateCardsWithoutChangingBusinessViews() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val ui = Ui(context)
            val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val first = ui.card(18f, 11f)
            val firstBackground = first.background
            val firstPadding = first.paddingLeft
            val input = EditText(context).apply {
                setText("流式边框不改草稿")
                setSelection(4)
            }
            first.addView(input)
            root.addView(first, LinearLayout.LayoutParams(240, 80))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 300, 400)

            val overlay = View(context)
            overlay.layout(0, 0, 300, 400)
            val panels = NativeFluidPanels(root, R.id.visual_card, ui.tokens(), EffectQuality.HIGH)
            val firstBitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            panels.draw(Canvas(firstBitmap), overlay, 0L)
            assertTrue("first card border should be painted", painted(firstBitmap, 0, 0, 300, 80) > 0)

            val late = ui.card()
            val lateBackground = late.background
            val latePadding = late.paddingLeft
            root.addView(late, LinearLayout.LayoutParams(260, 90))
            root.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 300, 400)
            root.viewTreeObserver.dispatchOnGlobalLayout()
            val secondBitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            panels.draw(Canvas(secondBitmap), overlay, 50L)
            assertTrue("late card border should be painted", painted(secondBitmap, 0, 80, 300, 190) > 0)

            assertSame(firstBackground, first.background)
            assertSame(lateBackground, late.background)
            assertEquals(firstPadding, first.paddingLeft)
            assertEquals(latePadding, late.paddingLeft)
            assertEquals("流式边框不改草稿", input.text.toString())
            assertEquals(4, input.selectionStart)
            assertSame(input, first.getChildAt(0))

            panels.close()
            panels.close()
        }
    }

    private fun painted(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) count++
            }
        }
        return count
    }
}
