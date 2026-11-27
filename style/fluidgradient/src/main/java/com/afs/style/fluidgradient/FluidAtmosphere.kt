package com.afs.style.fluidgradient

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import com.afs.style.api.UniverseGesture

@Composable
internal fun FluidAtmosphere(
    modifier: Modifier,
    clock: State<Float>,
    gesture: UniverseGesture,
    moving: Boolean,
    high: Boolean,
    stageTop: Float = 0f,
    stageBottom: Float = 1f
) {
    val particles = remember(high, moving) { FluidParticleField(if (high) 96 else 40) }
    val palette = remember { listOf(Color(0xFF3E9EFF), Color(0xFFAC76FF), Color(0xFFFF77CB), Color(0xFF6CFFE8)) }
    Canvas(modifier) {
        val time = if (moving) clock.value else 0f
        val heldSeconds = if (gesture.held && moving) (SystemClock.uptimeMillis() - gesture.started) / 1000f else 0f
        val fingerY = ((gesture.position.y - stageTop) / (stageBottom - stageTop)).coerceIn(0f, 1f)
        if (moving) particles.step(time, gesture.position.x, fingerY, gesture.held,
            heldSeconds, gesture.released, gesture.releaseVelocity.x, gesture.releaseVelocity.y)
        repeat(particles.count) { index ->
            val position = Offset(particles.horizontal[index] * size.width,
                (stageTop + particles.vertical[index] * (stageBottom - stageTop)) * size.height)
            val tail = Offset(particles.tailX[index] * size.width,
                (stageTop + particles.tailY[index] * (stageBottom - stageTop)) * size.height)
            val color = palette[index % palette.size]
            if ((position - tail).getDistance() < size.width * .12f) {
                drawLine(color.copy(alpha = .36f), tail, position, 1.8f, cap = StrokeCap.Round)
            }
            drawCircle(color.copy(alpha = .66f), 1.2f + index % 7 * .35f, position)
        }
        gesture.trail.forEachIndexed { index, sample ->
            val age = (SystemClock.uptimeMillis() - sample.time) / 1000f
            if (moving && age < .65f) {
                drawCircle(palette[index % palette.size].copy(alpha = (1f - age / .65f) * .22f),
                    (1f - age / .65f) * 8f, Offset(sample.position.x * size.width,
                        (stageTop + sample.position.y * (stageBottom - stageTop)) * size.height))
            }
        }
    }
}
