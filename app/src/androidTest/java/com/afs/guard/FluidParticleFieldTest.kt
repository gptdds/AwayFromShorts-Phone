package com.afs.guard

import com.afs.style.fluidgradient.FluidParticleField
import org.junit.Assert.*
import org.junit.Test

class FluidParticleFieldTest {
    @Test fun releaseCarriesMomentumWithoutTeleportingOrBreakingBounds() {
        val calm = FluidParticleField(45)
        val disturbed = FluidParticleField(45)
        for (frame in 0..120) {
            val time = frame / 30f
            calm.step(time, .5f, .5f, false, 0f, 0L, 0f, 0f)
            disturbed.step(time, .5f, .5f, frame < 30, time, if (frame >= 30) 1L else 0L, .8f, .3f)
            for (index in 0 until disturbed.count) {
                assertTrue(disturbed.horizontal[index].isFinite())
                assertTrue(disturbed.vertical[index].isFinite())
                assertTrue(disturbed.horizontal[index] in 0f..1f)
                assertTrue(disturbed.vertical[index] in 0f..1f)
            }
        }
        assertTrue(calm.horizontal.indices.any { kotlin.math.abs(calm.horizontal[it] - disturbed.horizontal[it]) > .001f })
        val before = disturbed.horizontal.copyOf()
        disturbed.step(4f, .5f, .5f, false, 0f, 1L, .8f, .3f)
        assertArrayEquals(before, disturbed.horizontal, 0f)
        disturbed.step(100f, .5f, .5f, false, 0f, 1L, 0f, 0f)
        assertTrue(disturbed.horizontal.all { it in 0f..1f })
    }
}
