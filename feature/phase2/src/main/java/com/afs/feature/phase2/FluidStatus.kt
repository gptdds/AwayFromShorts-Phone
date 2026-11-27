package com.afs.feature.phase2

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.guard.style.EffectQuality
import com.afs.style.api.LocalVisualEnvironment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Metaball-like status fluid. Several soft colour masses overlap, split and reform; the phase only
 * advances while the page is active and the current quality allows motion.
 */
@Composable
fun FluidStatusOrb(
    level: DiagnosticLevel,
    heading: String,
    detail: String,
    active: Boolean,
    quality: EffectQuality,
    modifier: Modifier = Modifier
) {
    val statusPalette = phase2StatusPalette()
    val moving = active && quality.allowMotion()
    val phase = if (moving) {
        val transition = rememberInfiniteTransition(label = "diagnostic-fluid")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(9200, easing = LinearEasing)),
            label = "diagnostic-fluid-phase"
        ).value
    } else {
        0.18f
    }
    val palette = when (level) {
        DiagnosticLevel.OK -> listOf(Color(0xFF5BE7B0), Color(0xFF48B8FF), Color(0xFF8FF6D7))
        DiagnosticLevel.INFO -> listOf(Color(0xFF65B8FF), Color(0xFF8B86FF), Color(0xFF7EEBFF))
        DiagnosticLevel.WARNING -> listOf(Color(0xFFFFC46B), Color(0xFFFF8B5E), Color(0xFFFFE08A))
        DiagnosticLevel.ERROR -> listOf(Color(0xFFFF7B85), Color(0xFFFF4D8D), Color(0xFFFFB36B))
    }
    val angle = phase * 2f * PI.toFloat()

    GlassCard(modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(15.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .size(132.dp)
                    .semantics { contentDescription = "$heading。$detail" }
                    .testTag("diagnostic-fluid-orb")
            ) {
                drawCircle(
                    color = Color.Black.copy(alpha = .34f),
                    radius = size.minDimension * .40f,
                    center = Offset(size.width / 2f, size.height / 2f + 6.dp.toPx())
                )
                for (index in 0..2) {
                    val orbit = 13.dp.toPx() + index * 5.dp.toPx()
                    val local = angle * (if (index % 2 == 0) 1f else -1.35f) + index * 2.1f
                    val center = Offset(
                        size.width / 2f + cos(local).toFloat() * orbit,
                        size.height / 2f + sin(local * .82f).toFloat() * orbit * .72f
                    )
                    val radius = size.minDimension * (0.24f + index * .025f) +
                        sin(local * 1.7f).toFloat() * 3.dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                palette[index].copy(alpha = .74f),
                                palette[(index + 1) % 3].copy(alpha = .28f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = .44f), Color.Transparent),
                        center = Offset(size.width * .38f, size.height * .32f),
                        radius = size.minDimension * .28f
                    ),
                    radius = size.minDimension * .28f,
                    center = Offset(size.width * .38f, size.height * .32f)
                )
                repeat(if (quality == EffectQuality.HIGH) 12 else 6) { index ->
                    val a = angle * (.45f + index * .013f) + index * 1.73f
                    val r = size.minDimension * (.30f + (index % 4) * .035f)
                    drawCircle(
                        color = palette[index % 3].copy(alpha = .38f),
                        radius = 1.2.dp.toPx() + (index % 3) * .35.dp.toPx(),
                        center = Offset(
                            size.width / 2f + cos(a).toFloat() * r,
                            size.height / 2f + sin(a).toFloat() * r
                        )
                    )
                }
            }
        }
        Text(heading, color = statusPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(detail, color = statusPalette.textMuted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
fun FluidRuleSavedBadge(visible: Boolean, active: Boolean, quality: EffectQuality) {
    if (!visible) return
    val moving = active && quality.allowMotion()
    val phase = if (moving) {
        val transition = rememberInfiniteTransition(label = "save-fluid")
        transition.animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(2500, easing = LinearEasing)),
            label = "save-fluid-phase"
        ).value
    } else .25f
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .testTag("fluid-save-badge")
    ) {
        val colors = listOf(Color(0xFF65E7B0), Color(0xFF65C8FF), Color(0xFFA8F3D8))
        for (index in 0..2) {
            val center = Offset(
                size.width * (.20f + index * .30f) + sin(phase * 2f * PI.toFloat() + index).toFloat() * 8.dp.toPx(),
                size.height * .52f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(colors[index].copy(alpha = .70f), Color.Transparent),
                    center = center,
                    radius = 18.dp.toPx()
                ),
                radius = 18.dp.toPx(),
                center = center
            )
        }
    }
}
