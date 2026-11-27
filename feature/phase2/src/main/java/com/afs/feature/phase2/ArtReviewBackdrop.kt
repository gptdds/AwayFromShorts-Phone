package com.afs.feature.phase2

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.afs.guard.style.EffectQuality
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen material fields for the prototype review. The backdrop is deliberately
 * stateful: gesture energy changes speed, pressure and particle wake.
 */
@Composable
internal fun ArtReviewBackdrop(
    direction: Phase2ArtDirection,
    active: Boolean,
    quality: EffectQuality,
    energy: Float,
    anchor: Offset,
    modifier: Modifier = Modifier
) {
    val moving = active && quality.allowMotion()
    val phase = if (moving) {
        val transition = rememberInfiniteTransition(label = "review-backdrop")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(
                    durationMillis = when (direction) {
                        Phase2ArtDirection.LIQUID_GLASS -> 18_000
                        Phase2ArtDirection.SPATIAL -> 22_000
                        Phase2ArtDirection.FLUID_GRADIENT -> 15_000
                        Phase2ArtDirection.LEGACY -> 20_000
                    },
                    easing = LinearEasing
                )
            ),
            label = "review-backdrop-phase"
        ).value
    } else {
        0f
    }
    Canvas(modifier) {
        when (direction) {
            Phase2ArtDirection.LIQUID_GLASS -> drawLiquidField(phase, energy, anchor, quality)
            Phase2ArtDirection.SPATIAL -> drawSpatialField(phase, energy, anchor, quality)
            Phase2ArtDirection.FLUID_GRADIENT -> drawFluidField(phase, energy, anchor, quality)
            Phase2ArtDirection.LEGACY -> drawLegacyField(phase, quality)
        }
    }
}

@Composable
fun LiquidGlassPhase2Backdrop(
    active: Boolean,
    quality: EffectQuality,
    energy: Float,
    anchor: Offset,
    modifier: Modifier = Modifier
) = ArtReviewBackdrop(
    direction = Phase2ArtDirection.LIQUID_GLASS,
    active = active,
    quality = quality,
    energy = energy,
    anchor = anchor,
    modifier = modifier
)

@Composable
fun SpatialPhase2Backdrop(
    active: Boolean,
    quality: EffectQuality,
    energy: Float,
    anchor: Offset,
    modifier: Modifier = Modifier
) = ArtReviewBackdrop(
    direction = Phase2ArtDirection.SPATIAL,
    active = active,
    quality = quality,
    energy = energy,
    anchor = anchor,
    modifier = modifier
)

@Composable
fun FluidGradientPhase2Backdrop(
    active: Boolean,
    quality: EffectQuality,
    energy: Float,
    anchor: Offset,
    modifier: Modifier = Modifier
) = ArtReviewBackdrop(
    direction = Phase2ArtDirection.FLUID_GRADIENT,
    active = active,
    quality = quality,
    energy = energy,
    anchor = anchor,
    modifier = modifier
)

private fun DrawScope.drawLiquidField(
    phase: Float,
    energy: Float,
    anchor: Offset,
    quality: EffectQuality
) {
    drawRect(
        Brush.verticalGradient(
            listOf(
                Color(0xFFF5F8F7),
                Color(0xFFE3ECEC),
                Color(0xFFC9D9DD),
                Color(0xFFB7C9CF)
            )
        )
    )

    val lens = Offset(
        size.width * (.50f + (anchor.x - .5f) * .04f),
        size.height * (.36f + (anchor.y - .5f) * .03f)
    )
    val lensRadius = size.width * .43f
    for (index in 0 until 7) {
        val y = size.height * (-.05f + index * .17f)
        val bend = (phase * .10f + index * .025f) * size.width
        val path = Path().apply {
            moveTo(-size.width * .08f, y)
            quadraticBezierTo(
                size.width * .26f + bend,
                y - size.height * .035f,
                size.width * .50f,
                y + size.height * .012f
            )
            quadraticBezierTo(
                size.width * .74f - bend * .35f,
                y + size.height * .045f,
                size.width * 1.08f,
                y - size.height * .008f
            )
        }
        drawPath(
            path,
            color = Color.White.copy(alpha = .28f - index * .022f),
            style = Stroke(width = if (index % 3 == 0) 1.5f else .8f)
        )
    }

    val warp = 1f + energy * .12f
    for (index in 0 until 5) {
        val x = size.width * (.12f + index * .19f)
        val path = Path().apply {
            moveTo(x, -size.height * .04f)
            quadraticBezierTo(
                x + sin(phase * PI.toFloat() * 2f + index) * size.width * (.08f + energy * .05f),
                lens.y - lensRadius * .9f,
                x + (x - lens.x) * .09f * warp,
                lens.y
            )
            quadraticBezierTo(
                x - sin(phase * PI.toFloat() * 2f + index) * size.width * .06f,
                lens.y + lensRadius * .9f,
                x,
                size.height * 1.04f
            )
        }
        drawPath(
            path,
            color = LiquidInk.tealDeep.copy(alpha = .055f + index * .008f),
            style = Stroke(width = .8f)
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            listOf(
                Color.White.copy(alpha = .40f),
                Color(0xFFBBD9DE).copy(alpha = .12f),
                Color.Transparent
            ),
            center = lens,
            radius = lensRadius * 1.48f
        ),
        radius = lensRadius * 1.48f,
        center = lens
    )
    drawCircle(
        color = Color.White.copy(alpha = .30f),
        radius = lensRadius,
        center = lens,
        style = Stroke(width = 1.1f)
    )

    val particleCount = when (quality) {
        EffectQuality.HIGH -> 34
        EffectQuality.MEDIUM -> 20
        EffectQuality.LOW -> 12
        EffectQuality.REDUCED_MOTION -> 0
    }
    repeat(particleCount) { index ->
        val seed = ((index * 47) % 101) / 101f
        val orbit = lensRadius * (.58f + seed * 1.10f)
        val angle = seed * PI.toFloat() * 2f + phase * PI.toFloat() * 2f * (.18f + seed * .40f) * (1f + energy)
        val point = Offset(
            lens.x + cos(angle) * orbit,
            lens.y + sin(angle) * orbit * .72f
        )
        val wake = (1f - ((index % 5) / 5f)) * (1f + energy * 1.4f)
        drawLine(
            brush = Brush.linearGradient(
                listOf(Color.White.copy(alpha = .20f * wake), Color.Transparent)
            ),
            start = point,
            end = Offset(
                point.x - cos(angle + .55f) * (7f + seed * 17f) * wake,
                point.y - sin(angle + .55f) * (5f + seed * 12f) * wake
            ),
            strokeWidth = .8f + seed,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color.White.copy(alpha = .28f + seed * .34f),
            radius = .8f + seed * 1.3f,
            center = point
        )
    }
}

private fun DrawScope.drawSpatialField(
    phase: Float,
    energy: Float,
    anchor: Offset,
    quality: EffectQuality
) {
    drawRect(
        Brush.verticalGradient(
            listOf(
                Color(0xFFF0E9DE),
                Color(0xFFD3C8B8),
                Color(0xFFB7AA98),
                Color(0xFF887E70)
            )
        )
    )

    val light = Offset(size.width * .16f, size.height * .02f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFFAEC).copy(alpha = .92f), Color.Transparent),
            center = light,
            radius = size.width * 1.18f
        ),
        radius = size.width * 1.18f,
        center = light
    )

    val horizon = size.height * .42f
    val vanishing = Offset(size.width * .55f, horizon)
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFFB7AB9B).copy(alpha = .70f), Color(0xFF7F766A))
        ),
        topLeft = Offset(0f, horizon),
        size = Size(size.width, size.height - horizon)
    )
    drawLine(
        color = Color(0xFFE8DED0).copy(alpha = .54f),
        start = Offset(0f, horizon),
        end = Offset(size.width, horizon),
        strokeWidth = 1.2f
    )

    for (index in -5..5) {
        val floorX = size.width / 2f + index * size.width * .18f
        drawLine(
            color = Color(0xFF403B35).copy(alpha = .11f),
            start = vanishing,
            end = Offset(floorX, size.height * 1.08f),
            strokeWidth = .8f
        )
    }
    for (index in 0..7) {
        val t = index / 7f
        val y = horizon + (size.height - horizon) * t * t
        drawLine(
            color = Color(0xFFF1E7DA).copy(alpha = .12f + t * .06f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = .6f + t
        )
    }

    val shadowCenter = Offset(
        size.width * (.50f + (anchor.x - .5f) * .05f),
        size.height * .68f
    )
    drawOval(
        brush = Brush.radialGradient(
            listOf(
                Color(0xFF201A15).copy(alpha = .34f + energy * .10f),
                Color(0xFF201A15).copy(alpha = .12f),
                Color.Transparent
            ),
            center = shadowCenter,
            radius = size.width * .53f
        ),
        topLeft = Offset(shadowCenter.x - size.width * .43f, shadowCenter.y - size.height * .05f),
        size = Size(size.width * .86f, size.height * .10f)
    )

    val beamPhase = phase * PI.toFloat() * 2f
    for (index in 0 until 4) {
        val dx = size.width * (.18f + index * .18f) + sin(beamPhase + index) * 8f
        drawLine(
            brush = Brush.verticalGradient(
                listOf(Color(0xFFFFFAEA).copy(alpha = .15f), Color.Transparent)
            ),
            start = Offset(light.x + dx * .15f, light.y),
            end = Offset(light.x + dx, size.height * .72f),
            strokeWidth = 2f + index
        )
    }

    val count = when (quality) {
        EffectQuality.HIGH -> 30
        EffectQuality.MEDIUM -> 18
        EffectQuality.LOW -> 10
        EffectQuality.REDUCED_MOTION -> 0
    }
    repeat(count) { index ->
        val seed = ((index * 61) % 103) / 103f
        val depth = .35f + seed * .65f
        val x = size.width * ((seed * 7.3f + phase * (.10f + energy * .12f) * depth) % 1f)
        val y = horizon + ((seed * 4.9f + phase * (.12f + energy * .08f)) % 1f) * (size.height - horizon)
        drawCircle(
            color = Color(0xFFFFF8E8).copy(alpha = .08f + depth * .18f),
            radius = .7f + depth * 1.7f,
            center = Offset(x, y)
        )
        drawLine(
            color = Color(0xFF29241F).copy(alpha = .06f + depth * .08f),
            start = Offset(x + 1.5f, y + 2f),
            end = Offset(x + 4f + energy * 4f, y + 5f + energy * 5f),
            strokeWidth = .7f
        )
    }
}

private fun DrawScope.drawFluidField(
    phase: Float,
    energy: Float,
    anchor: Offset,
    quality: EffectQuality
) {
    drawRect(
        Brush.verticalGradient(
            listOf(
                Color(0xFFF9F1E4),
                Color(0xFFEFE7D8),
                Color(0xFFE2D8C6)
            )
        )
    )

    val wave = phase * PI.toFloat() * 2f
    val center = Offset(
        size.width * (.48f + (anchor.x - .5f) * .055f),
        size.height * (.49f + (anchor.y - .5f) * .035f)
    )
    val body = Path()
    val radiusX = size.width * (.39f + energy * .018f)
    val radiusY = size.height * (.29f + energy * .014f)
    val points = 30
    for (index in 0..points) {
        val angle = index / points.toFloat() * PI.toFloat() * 2f
        val wobble = 1f +
            sin(angle * 3f + wave) * .055f +
            sin(angle * 5f - wave * 1.27f) * .025f +
            energy * .035f
        val x = center.x + cos(angle) * radiusX * wobble
        val y = center.y + sin(angle) * radiusY * wobble
        if (index == 0) body.moveTo(x, y) else body.lineTo(x, y)
    }
    body.close()

    drawPath(
        body,
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF3CA286).copy(alpha = .92f),
                Color(0xFF20715D).copy(alpha = .88f),
                Color(0xFF123F39).copy(alpha = .94f)
            ),
            center = Offset(center.x - radiusX * .24f, center.y - radiusY * .32f),
            radius = radiusX * 1.12f
        )
    )

    val apricot = Path()
    val ac = Offset(center.x + radiusX * .38f, center.y + radiusY * .20f)
    for (index in 0..24) {
        val angle = index / 24f * PI.toFloat() * 2f
        val r = 1f + sin(angle * 4f - wave * .8f) * .13f
        val x = ac.x + cos(angle) * radiusX * .34f * r
        val y = ac.y + sin(angle) * radiusY * .43f * r
        if (index == 0) apricot.moveTo(x, y) else apricot.lineTo(x, y)
    }
    apricot.close()
    drawPath(
        apricot,
        brush = Brush.radialGradient(
            listOf(
                Color(0xFFF4B177).copy(alpha = .72f),
                Color(0xFFDB8069).copy(alpha = .52f),
                Color.Transparent
            ),
            center = Offset(ac.x - radiusX * .08f, ac.y - radiusY * .13f),
            radius = radiusX * .48f
        ),
        blendMode = BlendMode.Multiply
    )

    drawPath(
        body,
        color = Color.White.copy(alpha = .22f),
        style = Stroke(width = 1.6f)
    )
    drawArc(
        brush = Brush.sweepGradient(
            listOf(
                Color.Transparent,
                Color.White.copy(alpha = .55f),
                Color.Transparent,
                Color(0xFFF5B994).copy(alpha = .22f),
                Color.Transparent
            ),
            center = center
        ),
        startAngle = -155f + sin(wave) * 8f,
        sweepAngle = 138f,
        useCenter = false,
        topLeft = Offset(center.x - radiusX * .80f, center.y - radiusY * .72f),
        size = Size(radiusX * 1.60f, radiusY * 1.44f),
        style = Stroke(width = 3f, cap = StrokeCap.Round)
    )
    drawOval(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = .34f), Color.Transparent),
            center = Offset(center.x - radiusX * .35f, center.y - radiusY * .38f),
            radius = radiusX * .42f
        ),
        topLeft = Offset(center.x - radiusX * .52f, center.y - radiusY * .48f),
        size = Size(radiusX * .60f, radiusY * .38f)
    )

    repeat(3) { index ->
        val orbit = wave + index * 2.1f
        val drop = Offset(
            center.x + cos(orbit) * radiusX * (.70f + index * .12f),
            center.y + sin(orbit) * radiusY * (.64f + index * .10f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFE89B72).copy(alpha = .35f), Color(0xFF2A856C).copy(alpha = .30f)),
                center = drop,
                radius = 18f + index * 6f
            ),
            radius = 18f + index * 6f,
            center = drop,
            blendMode = BlendMode.Multiply
        )
    }

    val count = when (quality) {
        EffectQuality.HIGH -> 36
        EffectQuality.MEDIUM -> 22
        EffectQuality.LOW -> 12
        EffectQuality.REDUCED_MOTION -> 0
    }
    repeat(count) { index ->
        val seed = ((index * 59) % 107) / 107f
        val angle = seed * PI.toFloat() * 2f + wave * (.34f + seed * .46f) * (1f + energy)
        val radius = radiusX * (.54f + seed * .66f)
        val point = Offset(
            center.x + cos(angle) * radius,
            center.y + sin(angle) * radius * .78f
        )
        val tail = Offset(
            point.x - sin(angle) * (6f + seed * 13f) * (1f + energy),
            point.y + cos(angle) * (3f + seed * 7f) * (1f + energy)
        )
        drawLine(
            color = Color(0xFFF8EBDD).copy(alpha = .16f + seed * .22f),
            start = tail,
            end = point,
            strokeWidth = .75f + seed * 1.1f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color.White.copy(alpha = .20f + seed * .24f),
            radius = .7f + seed,
            center = point
        )
    }
}

private fun DrawScope.drawLegacyField(phase: Float, quality: EffectQuality) {
    drawRect(Brush.verticalGradient(listOf(Phase2Colors.background, Phase2Colors.backgroundDeep)))
    val count = if (quality.allowParticles()) 20 else 8
    repeat(count) { index ->
        val seed = ((index * 43) % 97) / 97f
        drawCircle(
            color = Phase2Colors.ice.copy(alpha = .04f + seed * .08f),
            radius = .8f + seed * 1.8f,
            center = Offset(
                size.width * ((seed * 5.1f + phase) % 1f),
                size.height * ((seed * 3.7f + phase * .6f) % 1f)
            )
        )
    }
}
