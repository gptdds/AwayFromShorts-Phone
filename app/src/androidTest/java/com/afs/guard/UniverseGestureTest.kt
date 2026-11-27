package com.afs.guard

import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.style.api.UniverseGesture
import org.junit.Assert.*
import org.junit.Test

class UniverseGestureTest {
    @Test fun cancellationClearsEffectsAndCoordinatesRemainBounded() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val gesture = UniverseGesture()
            gesture.accept(MotionEvent.ACTION_DOWN, .2f, .4f)
            assertTrue(gesture.held)
            gesture.accept(MotionEvent.ACTION_MOVE, 1.4f, -.2f)
            assertEquals(1f, gesture.position.x)
            assertEquals(0f, gesture.position.y)
            assertTrue(gesture.trail.isNotEmpty())
            repeat(50) { gesture.accept(MotionEvent.ACTION_MOVE, .3f, .5f) }
            assertEquals(24, gesture.trail.size)
            gesture.accept(MotionEvent.ACTION_UP, .7f, .6f)
            assertFalse(gesture.held)
            assertTrue(gesture.released > 0)
            gesture.accept(MotionEvent.ACTION_CANCEL, .7f, .6f)
            assertEquals(0L, gesture.released)
            assertTrue(gesture.trail.isEmpty())
            gesture.accept(MotionEvent.ACTION_DOWN, .3f, .5f)
            gesture.clear()
            assertFalse(gesture.held)
            assertEquals(0L, gesture.released)
        }
    }
}
