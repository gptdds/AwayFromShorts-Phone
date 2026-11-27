package com.afs.style.fluidgradient

import kotlin.math.*

class FluidParticleField(val count: Int) {
    val horizontal = FloatArray(count) { .5f + cos(it * 2.399963f) * (.15f + it % 13 * .015f) }
    val vertical = FloatArray(count) { .37f + sin(it * 2.399963f) * (.09f + it % 13 * .01f) }
    val tailX = horizontal.copyOf()
    val tailY = vertical.copyOf()
    private val velocityX = FloatArray(count)
    private val velocityY = FloatArray(count)
    private var lastTime = Float.NaN
    private var lastRelease = 0L

    fun step(time: Float, fingerX: Float, fingerY: Float, held: Boolean, holdAge: Float,
             released: Long, impulseX: Float, impulseY: Float) {
        val elapsed = if (lastTime.isNaN()) 0f else (time - lastTime).coerceIn(0f, .05f)
        lastTime = time
        if (elapsed == 0f) return
        val burst = released != 0L && released != lastRelease
        lastRelease = released
        val damping = exp(-elapsed * 2.5f)
        for (index in 0 until count) {
            tailX[index] = horizontal[index]
            tailY[index] = vertical[index]
            val deltaX = horizontal[index] - fingerX
            val deltaY = vertical[index] - fingerY
            val distance = sqrt(deltaX * deltaX + deltaY * deltaY).coerceAtLeast(.03f)
            val influence = exp(-distance * 9f)
            val curl = if (held) (holdAge * 2f).coerceIn(.2f, 1.5f) * influence else 0f
            val orbitX = horizontal[index] - .5f
            val orbitY = vertical[index] - .37f
            velocityX[index] += (sin(vertical[index] * 10f + time * .2f) * .06f - orbitY * .3f - orbitX * .04f - deltaY / distance * curl) * elapsed
            velocityY[index] += (cos(horizontal[index] * 9f - time * .18f) * .04f + orbitX * .22f - orbitY * .04f + deltaX / distance * curl) * elapsed
            if (burst) {
                velocityX[index] += influence * (deltaX / distance * .35f + impulseX.coerceIn(-1f, 1f))
                velocityY[index] += influence * (deltaY / distance * .35f + impulseY.coerceIn(-1f, 1f))
            }
            velocityX[index] *= damping
            velocityY[index] *= damping
            horizontal[index] = (horizontal[index] + velocityX[index] * elapsed + 1f) % 1f
            vertical[index] = (vertical[index] + velocityY[index] * elapsed + 1f) % 1f
        }
    }
}
