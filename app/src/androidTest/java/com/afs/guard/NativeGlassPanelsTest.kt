package com.afs.guard

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.EffectQuality
import com.afs.style.liquidglass.NativeGlassPanels
import org.junit.Assert.*
import org.junit.Test

class NativeGlassPanelsTest {
    @Test fun materialKeepsDraftGeometryAndLateCardsAndRestoresOriginals() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val ui = Ui(context)
            val root = LinearLayout(context)
            val card = ui.card(18f, 11f)
            val original = card.background
            val padding = card.paddingLeft
            val input = EditText(context).apply { setText("保留原有草稿"); setSelection(3) }
            card.addView(input); root.addView(card)
            val panels = NativeGlassPanels(root, R.id.visual_card, ui.tokens(), EffectQuality.LOW)
            assertNotSame(original, card.background)
            assertEquals((original as GradientDrawable).cornerRadius, (card.background as GradientDrawable).cornerRadius, .01f)
            assertEquals(padding, card.paddingLeft)
            assertEquals("保留原有草稿", input.text.toString()); assertEquals(3, input.selectionStart)
            val late = ui.card(); val lateOriginal = late.background; root.addView(late)
            root.viewTreeObserver.dispatchOnGlobalLayout()
            assertNotSame(lateOriginal, late.background)
            panels.close(); panels.close()
            assertSame(original, card.background); assertSame(lateOriginal, late.background)
            assertEquals(padding, card.paddingLeft); assertSame(input, card.getChildAt(0))
            val second = NativeGlassPanels(root, R.id.visual_card, ui.tokens(), EffectQuality.HIGH)
            val changedByPage = ColorDrawable(0xff123456.toInt())
            card.background = changedByPage
            second.close()
            assertSame(changedByPage, card.background)
            assertEquals(0f, card.elevation, .001f)
            val dragPanels = NativeGlassPanels(root, R.id.visual_card, ui.tokens(), EffectQuality.HIGH)
            card.setTag(R.id.visual_card, false)
            val highlight = ColorDrawable(0xff00ccaa.toInt())
            card.background = highlight
            root.viewTreeObserver.dispatchOnGlobalLayout()
            assertSame(highlight, card.background)
            card.setTag(R.id.visual_card, true)
            card.background = original
            root.viewTreeObserver.dispatchOnGlobalLayout()
            assertNotSame(original, card.background)
            dragPanels.close()
            assertSame(original, card.background)
            assertEquals(0f, card.elevation, .001f)
        }
    }
}
