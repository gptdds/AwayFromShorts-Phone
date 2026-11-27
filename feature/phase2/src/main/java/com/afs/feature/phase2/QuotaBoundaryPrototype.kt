package com.afs.feature.phase2

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.guard.style.EffectQuality
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class QuotaGesture(
    val pressed: Boolean = false,
    val dragging: Boolean = false,
    val locked: Boolean = false,
    val anchor: Offset = Offset(.5f, .5f)
)

/**
 * Prototype B. The manipulator is deliberately not a slider: each art direction moves
 * a different material object and previews the limit while the finger is still down.
 */
@Composable
fun QuotaBoundaryPrototype(
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    onGestureEnergy: (energy: Float, anchor: Offset) -> Unit = { _, _ -> }
) {
    val direction = rememberPhase2ArtDirection()
    val haptics = LocalHapticFeedback.current
    var limit by remember { mutableIntStateOf(60) }
    var locked by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Offset(.62f, .48f)) }
    var releasePulse by remember { mutableIntStateOf(0) }
    val charge by animateFloatAsState(
        targetValue = if (pressed && !dragging && !locked) 1f else 0f,
        animationSpec = tween(if (pressed && !dragging) 470 else 150),
        label = "quota-lock-charge"
    )
    val settle by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(.72f, 330f),
        label = "quota-settle"
    )
    val ratio = ((limit - 15f) / 165f).coerceIn(0f, 1f)

    LaunchedEffect(anchor, pressed, dragging) {
        onGestureEnergy(
            when {
                dragging -> 1f
                pressed -> .42f
                else -> .08f
            },
            anchor
        )
    }
    LaunchedEffect(releasePulse) {
        if (releasePulse > 0) {
            delay(280)
            dragging = false
        }
    }
    LaunchedEffect(pressed, dragging, locked) {
        if (pressed && !dragging && !locked) {
            delay(470)
            if (pressed && !dragging && !locked) {
                locked = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    val gesture = Modifier
        .pointerInput(direction) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                pressed = true
                dragging = false
                anchor = Offset(
                    (down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                    (down.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                )
                val startLimit = limit
                var lastBucket = bucket(startLimit)
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    val delta = when (direction) {
                        Phase2ArtDirection.SPATIAL ->
                            down.position.y - change.position.y
                        else -> change.position.x - down.position.x
                    }
                    if (!locked && abs(delta) > viewConfiguration.touchSlop * 1.6f) {
                        dragging = true
                        val span = if (direction == Phase2ArtDirection.SPATIAL) {
                            size.height.coerceAtLeast(1)
                        } else {
                            size.width.coerceAtLeast(1)
                        }
                        val candidate = (startLimit + delta / span * 178f)
                            .roundToInt()
                            .coerceIn(15, 180)
                        val resisted = resistLimit(candidate)
                        if (resisted != limit) {
                            limit = resisted
                            anchor = Offset(
                                (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            )
                            val nextBucket = bucket(resisted)
                            if (nextBucket != lastBucket) {
                                lastBucket = nextBucket
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                    if (!change.pressed) break
                    change.consume()
                } while (true)
                pressed = false
                dragging = false
                releasePulse++
            }
        }
        .semantics {
            contentDescription =
                "单应用限额，当前 ${limit} 分钟，${quotaState(limit, locked)}，拖动调整，长按锁定"
        }
        .testTag("phase2-quota-prototype")

    Box(
        modifier
            .fillMaxSize()
            .then(gesture)
    ) {
        if (direction == Phase2ArtDirection.FLUID_GRADIENT) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                FluidInk.fieldWarm.copy(alpha = .72f),
                                FluidInk.cream.copy(alpha = .76f),
                                FluidInk.fieldWarm.copy(alpha = .58f)
                            )
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(.38f)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                FluidInk.cream.copy(alpha = .94f),
                                FluidInk.cream.copy(alpha = .74f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            QuotaIdentity(direction, limit, locked)
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "46",
                    color = inkFor(direction),
                    fontSize = 58.sp,
                    fontWeight = if (direction == Phase2ArtDirection.SPATIAL) FontWeight.Bold else FontWeight.Light,
                    letterSpacing = if (direction == Phase2ArtDirection.LIQUID_GLASS) 1.sp else 0.sp
                )
                Text(
                    " min",
                    color = mutedFor(direction),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 10.dp, start = 5.dp)
                )
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("LIMIT", color = mutedFor(direction), fontSize = 8.sp, letterSpacing = 1.6.sp)
                    Text(
                        "$limit",
                        color = accentFor(direction, limit, locked),
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Text(
                quotaState(limit, locked),
                color = accentFor(direction, limit, locked),
                fontSize = 11.sp,
                letterSpacing = .6.sp
            )
            Spacer(Modifier.height(16.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        translationY = settle * if (direction == Phase2ArtDirection.SPATIAL) 3f else 1.2f
                    }
            ) {
                when (direction) {
                    Phase2ArtDirection.LIQUID_GLASS -> LiquidQuotaManipulator(
                        ratio = ratio,
                        limit = limit,
                        pressed = pressed,
                        dragging = dragging,
                        locked = locked,
                        charge = charge,
                        anchor = anchor,
                        quality = quality,
                        active = active
                    )
                    Phase2ArtDirection.SPATIAL -> SpatialQuotaManipulator(
                        ratio = ratio,
                        limit = limit,
                        pressed = pressed,
                        dragging = dragging,
                        locked = locked,
                        charge = charge,
                        anchor = anchor,
                        quality = quality,
                        active = active
                    )
                    Phase2ArtDirection.FLUID_GRADIENT -> FluidQuotaManipulator(
                        ratio = ratio,
                        limit = limit,
                        pressed = pressed,
                        dragging = dragging,
                        locked = locked,
                        charge = charge,
                        anchor = anchor,
                        quality = quality,
                        active = active
                    )
                    Phase2ArtDirection.LEGACY -> LegacyQuotaManipulator(ratio, limit)
                }
            }

            QuotaFooter(
                direction = direction,
                limit = limit,
                locked = locked,
                charge = charge,
                onToggleLock = {
                    locked = !locked
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
        }
    }
}

@Composable
fun LiquidGlassPhase2Quota(
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    onGestureEnergy: (Float, Offset) -> Unit = { _, _ -> }
) {
    CompositionLocalProvider(LocalPhase2ArtDirection provides Phase2ArtDirection.LIQUID_GLASS) {
        QuotaBoundaryPrototype(
            quality = quality,
            active = active,
            modifier = modifier,
            onGestureEnergy = onGestureEnergy
        )
    }
}

@Composable
fun SpatialPhase2Quota(
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    onGestureEnergy: (Float, Offset) -> Unit = { _, _ -> }
) {
    CompositionLocalProvider(LocalPhase2ArtDirection provides Phase2ArtDirection.SPATIAL) {
        QuotaBoundaryPrototype(
            quality = quality,
            active = active,
            modifier = modifier,
            onGestureEnergy = onGestureEnergy
        )
    }
}

@Composable
fun FluidGradientPhase2Quota(
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    onGestureEnergy: (Float, Offset) -> Unit = { _, _ -> }
) {
    CompositionLocalProvider(LocalPhase2ArtDirection provides Phase2ArtDirection.FLUID_GRADIENT) {
        QuotaBoundaryPrototype(
            quality = quality,
            active = active,
            modifier = modifier,
            onGestureEnergy = onGestureEnergy
        )
    }
}

@Composable
private fun QuotaIdentity(
    direction: Phase2ArtDirection,
    limit: Int,
    locked: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        QuotaGlyph(direction, limit <= 60, locked)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Telegram",
                color = inkFor(direction),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "今日使用 · 46 min",
                color = mutedFor(direction),
                fontSize = 10.sp,
                letterSpacing = .5.sp
            )
        }
        Text(
            quotaState(limit, locked),
            color = accentFor(direction, limit, locked),
            fontSize = 10.sp,
            modifier = Modifier
                .background(accentFor(direction, limit, locked).copy(alpha = .10f), RoundedCornerShape(3.dp))
                .border(1.dp, accentFor(direction, limit, locked).copy(alpha = .28f), RoundedCornerShape(3.dp))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun QuotaGlyph(direction: Phase2ArtDirection, urgent: Boolean, locked: Boolean) {
    val shape = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> RoundedCornerShape(17.dp)
        Phase2ArtDirection.SPATIAL -> RoundedCornerShape(4.dp)
        Phase2ArtDirection.FLUID_GRADIENT -> RoundedCornerShape(22.dp, 10.dp, 19.dp, 12.dp)
        Phase2ArtDirection.LEGACY -> RoundedCornerShape(14.dp)
    }
    val brush = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS ->
            Brush.linearGradient(listOf(Color.White.copy(alpha = .88f), Color(0xFF8FC7CF)))
        Phase2ArtDirection.SPATIAL ->
            Brush.linearGradient(listOf(Color(0xFFF0E7DC), Color(0xFF8B8072)))
        Phase2ArtDirection.FLUID_GRADIENT ->
            Brush.linearGradient(listOf(Color(0xFFF6C08C), Color(0xFF2D8A70)))
        Phase2ArtDirection.LEGACY ->
            Brush.linearGradient(listOf(Phase2Colors.ice, Phase2Colors.iceDeep))
    }
    Box(
        Modifier
            .size(50.dp)
            .background(brush, shape)
            .border(1.dp, Color.White.copy(alpha = .50f), shape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(26.dp)) {
            val p = Path().apply {
                moveTo(size.width * .18f, size.height * .78f)
                lineTo(size.width * .54f, size.height * .14f)
                lineTo(size.width * .84f, size.height * .78f)
                close()
            }
            drawPath(
                p,
                color = when (direction) {
                    Phase2ArtDirection.SPATIAL -> Color(0xFF3A3630)
                    Phase2ArtDirection.FLUID_GRADIENT -> Color(0xFF173B32)
                    else -> LiquidInk.tealDeep
                }
            )
            if (locked || urgent) {
                drawCircle(
                    color = Color.White.copy(alpha = .78f),
                    radius = size.minDimension * .25f,
                    center = center,
                    style = Stroke(width = 1.3f)
                )
            }
        }
    }
}

@Composable
private fun LiquidQuotaManipulator(
    ratio: Float,
    limit: Int,
    pressed: Boolean,
    dragging: Boolean,
    locked: Boolean,
    charge: Float,
    anchor: Offset,
    quality: EffectQuality,
    active: Boolean
) {
    val moving = active && quality.allowMotion()
    val phase by liquidPhase(moving)
    Canvas(Modifier.fillMaxSize().testTag("phase2-quota-manipulator")) {
        val w = size.width
        val h = size.height
        val railLeft = w * .08f
        val railRight = w * .92f
        val railY = h * .54f
        val lensX = railLeft + (railRight - railLeft) * ratio
        val lensY = railY + if (dragging && moving) (anchor.y - .5f) * 18f else 0f
        val lensRadius = w * .105f

        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(Color(0xE8FFFFFF), Color(0x7AD8E9EA), Color(0x5AA6C5CB)),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            ),
            cornerRadius = CornerRadius(w * .08f)
        )
        drawRoundRect(
            brush = Brush.sweepGradient(
                listOf(
                    Color.White.copy(alpha = .92f),
                    Color.White.copy(alpha = .12f),
                    Color(0xFF5E9AA3).copy(alpha = .35f),
                    Color.White.copy(alpha = .78f)
                )
            ),
            cornerRadius = CornerRadius(w * .08f),
            style = Stroke(width = 1.6f)
        )

        for (index in 0 until 9) {
            val y = h * (.12f + index * .098f)
            val bend = (lensX - w * .5f) * .08f + sin(phase * PI.toFloat() * 2f + index) * 3f
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = .32f), Color.Transparent)
                ),
                start = Offset(w * .05f, y),
                end = Offset(w * .95f, y + bend),
                strokeWidth = 1f
            )
        }

        drawRoundRect(
            color = Color(0xFF234F58).copy(alpha = .16f),
            topLeft = Offset(railLeft, railY - h * .027f),
            size = Size(railRight - railLeft, h * .054f),
            cornerRadius = CornerRadius(h * .027f)
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(
                    LiquidInk.tealDeep.copy(alpha = .70f),
                    Color(0xFF63C9C9),
                    LiquidInk.amber.copy(alpha = .76f)
                )
            ),
            topLeft = Offset(railLeft + 2f, railY - h * .014f),
            size = Size((railRight - railLeft - 4f) * ratio + h * .028f, h * .028f),
            cornerRadius = CornerRadius(h * .014f)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = .58f),
            topLeft = Offset(railLeft, railY - h * .023f),
            size = Size(railRight - railLeft, h * .010f),
            cornerRadius = CornerRadius(h * .005f)
        )

        for (index in 0 until 6) {
            val t = index / 5f
            val x = railLeft + (railRight - railLeft) * t
            val major = index == 0 || index == 5
            drawLine(
                color = LiquidInk.tealDeep.copy(alpha = if (major) .42f else .18f),
                start = Offset(x, railY + h * .022f),
                end = Offset(x, railY + h * (if (major) .048f else .038f)),
                strokeWidth = if (major) 1.5f else .8f
            )
            if (index == 0 || index == 5) {
                drawQuotaText(
                    text = if (index == 0) "15" else "180",
                    x = x,
                    y = railY + h * .075f,
                    color = LiquidInk.muted.copy(alpha = .72f),
                    size = 8.sp.toPx()
                )
            }
        }

        drawCircle(
            color = Color(0xFF0A4A54).copy(alpha = .16f + charge * .16f),
            center = Offset(lensX + 2f, lensY + h * .02f),
            radius = lensRadius * (1.22f + charge * .15f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    Color.White.copy(alpha = .94f),
                    Color(0xFFD3EBEC).copy(alpha = .82f),
                    Color(0xFF6AAFB8).copy(alpha = .48f),
                    Color(0xFF28616B).copy(alpha = .20f)
                ),
                center = Offset(lensX - lensRadius * .32f, lensY - lensRadius * .38f),
                radius = lensRadius * 1.20f
            ),
            center = Offset(lensX, lensY),
            radius = lensRadius * (1f + settleScale(pressed, dragging) * .035f)
        )
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(
                    Color.White.copy(alpha = .84f),
                    Color(0xFF4F9CA7).copy(alpha = .22f),
                    Color.White.copy(alpha = .92f),
                    Color(0xFF1C5661).copy(alpha = .20f)
                ),
                center = Offset(lensX, lensY)
            ),
            center = Offset(lensX, lensY),
            radius = lensRadius * .92f,
            style = Stroke(width = 2.4f)
        )
        drawOval(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = .76f), Color.Transparent),
                center = Offset(lensX - lensRadius * .28f, lensY - lensRadius * .34f),
                radius = lensRadius * .58f
            ),
            topLeft = Offset(lensX - lensRadius * .62f, lensY - lensRadius * .60f),
            size = Size(lensRadius * .74f, lensRadius * .40f)
        )

        repeat(3) { ring ->
            if (dragging || pressed || charge > .02f) {
                drawCircle(
                    color = Color.White.copy(alpha = (.16f + ring * .05f) * (charge + settleScale(pressed, dragging))),
                    center = Offset(lensX, lensY),
                    radius = lensRadius * (1.18f + ring * .18f + phase * .08f),
                    style = Stroke(width = 1f)
                )
            }
        }
        if (charge > .02f) {
            drawArc(
                color = LiquidInk.teal.copy(alpha = .30f + charge * .42f),
                startAngle = -90f,
                sweepAngle = 360f * charge,
                useCenter = false,
                topLeft = Offset(lensX - lensRadius * 1.36f, lensY - lensRadius * 1.36f),
                size = Size(lensRadius * 2.72f, lensRadius * 2.72f),
                style = Stroke(width = 2.3f, cap = StrokeCap.Round)
            )
        }
        if (locked) {
            drawCircle(
                color = Color.White.copy(alpha = .62f),
                center = Offset(lensX, lensY),
                radius = lensRadius * .22f
            )
            drawQuotaText(
                text = "LOCK",
                x = lensX,
                y = lensY + 2.8.sp.toPx(),
                color = LiquidInk.tealDeep,
                size = 7.sp.toPx(),
                alignCenter = true
            )
        }
        drawQuotaText(
            text = "$limit",
            x = lensX,
            y = lensY - lensRadius * .52f,
            color = LiquidInk.tealDeep,
            size = 14.sp.toPx(),
            alignCenter = true
        )

        if (quality != EffectQuality.REDUCED_MOTION) {
            repeat(8) { index ->
                val t = index / 8f
                drawCircle(
                    color = Color.White.copy(alpha = .12f + t * .20f),
                    radius = .8f + t * 1.2f,
                    center = Offset(
                        lensX - lensRadius * (1.2f + t * .9f) - sin(phase * 8f + index) * 5f,
                        lensY + (t - .5f) * lensRadius * 1.3f
                    )
                )
            }
        }
    }
}

@Composable
private fun SpatialQuotaManipulator(
    ratio: Float,
    limit: Int,
    pressed: Boolean,
    dragging: Boolean,
    locked: Boolean,
    charge: Float,
    anchor: Offset,
    quality: EffectQuality,
    active: Boolean
) {
    val moving = active && quality.allowMotion()
    val phase by spatialPhase(moving)
    Canvas(Modifier.fillMaxSize().testTag("phase2-quota-manipulator")) {
        val w = size.width
        val h = size.height
        val left = w * .07f
        val right = w * .93f
        val top = h * .06f
        val bottom = h * .91f
        val markerY = bottom - (bottom - top) * ratio
        val lift = if (dragging) h * .07f else if (pressed) h * .018f else 0f
        val light = Offset(w * .18f, h * .02f)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(24f, w * .045f, h * .055f + lift * .28f, 0x56261E16)
        }
        drawIntoCanvas {
            it.nativeCanvas.drawRoundRect(
                RectF(left, top, right, bottom),
                w * .025f,
                w * .025f,
                shadow
            )
        }

        val slab = Path().apply {
            moveTo(left, top)
            lineTo(right - w * .04f, top + h * .03f)
            lineTo(right, bottom)
            lineTo(left + w * .025f, bottom - h * .012f)
            close()
        }
        drawPath(
            slab,
            brush = Brush.linearGradient(
                listOf(
                    Color(0xFFE7DDD0),
                    Color(0xFFC0B3A3),
                    Color(0xFF948878)
                ),
                start = Offset(left, top),
                end = Offset(right, bottom)
            )
        )
        drawPath(
            slab,
            color = Color.White.copy(alpha = .28f),
            style = Stroke(width = 1.2f)
        )
        drawLine(
            color = Color(0xFFF9F1E5).copy(alpha = .48f),
            start = Offset(left + 2f, top + 2f),
            end = Offset(right - w * .06f, top + h * .035f),
            strokeWidth = 2f
        )
        drawLine(
            color = Color(0xFF4A433B).copy(alpha = .24f),
            start = Offset(left + w * .015f, bottom - h * .01f),
            end = Offset(right + w * .02f, bottom + h * .015f),
            strokeWidth = 2f
        )

        for (index in 0..12) {
            val t = index / 12f
            val y = top + (bottom - top) * t
            val major = index % 3 == 0
            drawLine(
                color = Color(0xFF3A342E).copy(alpha = if (major) .25f else .10f),
                start = Offset(left + w * .06f, y),
                end = Offset(left + w * (if (major) .19f else .14f), y),
                strokeWidth = if (major) 1.4f else .8f
            )
            if (index == 0 || index == 6 || index == 12) {
                drawQuotaText(
                    text = when (index) {
                        0 -> "180"
                        6 -> "90"
                        else -> "15"
                    },
                    x = left + w * .045f,
                    y = y + 2.5.sp.toPx(),
                    color = SpatialInk.muted.copy(alpha = .78f),
                    size = 8.sp.toPx()
                )
            }
        }

        val grooveLeft = w * .38f
        val grooveRight = w * .83f
        drawRoundRect(
            color = Color(0xFF4E463D).copy(alpha = .46f),
            topLeft = Offset(grooveLeft, top + h * .045f),
            size = Size(grooveRight - grooveLeft, bottom - top - h * .09f),
            cornerRadius = CornerRadius(w * .014f)
        )
        drawRoundRect(
            color = Color(0xFFF3E9DC).copy(alpha = .32f),
            topLeft = Offset(grooveLeft + 2f, top + h * .045f + 2f),
            size = Size(grooveRight - grooveLeft - 4f, bottom - top - h * .095f),
            cornerRadius = CornerRadius(w * .012f)
        )

        val blockWidth = (grooveRight - grooveLeft) * .72f
        val blockHeight = h * .092f
        val blockLeft = grooveLeft + (grooveRight - grooveLeft - blockWidth) * .5f
        val blockTop = markerY - blockHeight * .5f - lift

        drawOval(
            brush = Brush.radialGradient(
                listOf(
                    Color(0xFF221B15).copy(alpha = .48f + if (dragging) .16f else 0f),
                    Color.Transparent
                ),
                center = Offset(blockLeft + blockWidth * .50f, markerY + h * .036f + lift * .4f),
                radius = blockWidth * .62f
            ),
            topLeft = Offset(blockLeft - blockWidth * .10f, markerY + h * .002f + lift * .3f),
            size = Size(blockWidth * 1.2f, h * .072f)
        )
        val side = Path().apply {
            moveTo(blockLeft + blockWidth, blockTop + h * .006f)
            lineTo(blockLeft + blockWidth + w * .025f, blockTop + h * .02f)
            lineTo(blockLeft + blockWidth + w * .025f, blockTop + blockHeight + h * .012f)
            lineTo(blockLeft + blockWidth, blockTop + blockHeight)
            close()
        }
        drawPath(side, color = Color(0xFF756A5D))
        val topFace = Path().apply {
            moveTo(blockLeft, blockTop)
            lineTo(blockLeft + w * .022f, blockTop - h * .014f)
            lineTo(blockLeft + blockWidth + w * .025f, blockTop + h * .004f)
            lineTo(blockLeft + blockWidth, blockTop + h * .018f)
            close()
        }
        drawPath(
            topFace,
            brush = Brush.linearGradient(
                listOf(Color(0xFFFBF3E8), Color(0xFFD5C9B9)),
                start = Offset(blockLeft, blockTop),
                end = Offset(blockLeft + blockWidth, blockTop)
            )
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(Color(0xFFE7DBCD), Color(0xFFB3A594), Color(0xFF8E8171)),
                start = Offset(blockLeft, blockTop),
                end = Offset(blockLeft + blockWidth, blockTop + blockHeight)
            ),
            topLeft = Offset(blockLeft, blockTop),
            size = Size(blockWidth, blockHeight),
            cornerRadius = CornerRadius(w * .009f)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = .38f),
            topLeft = Offset(blockLeft + 1f, blockTop + 1f),
            size = Size(blockWidth - 2f, blockHeight - 2f),
            cornerRadius = CornerRadius(w * .009f),
            style = Stroke(width = 1f)
        )
        drawLine(
            color = SpatialInk.oxide.copy(alpha = .78f),
            start = Offset(blockLeft + blockWidth * .08f, blockTop + blockHeight * .74f),
            end = Offset(blockLeft + blockWidth * .92f, blockTop + blockHeight * .74f),
            strokeWidth = 2f
        )
        drawQuotaText(
            text = "$limit",
            x = blockLeft + blockWidth * .5f,
            y = blockTop + blockHeight * .50f,
            color = SpatialInk.ink,
            size = 13.sp.toPx(),
            alignCenter = true
        )
        drawQuotaText(
            text = "MIN",
            x = blockLeft + blockWidth * .5f,
            y = blockTop + blockHeight * .84f,
            color = SpatialInk.muted,
            size = 5.5.sp.toPx(),
            alignCenter = true
        )

        if (charge > .02f) {
            paintLockCharge(
                center = Offset(blockLeft + blockWidth * .5f, blockTop - h * .07f),
                radius = w * .058f,
                progress = charge,
                color = SpatialInk.oxide
            )
        }
        if (locked) {
            drawCircle(
                color = SpatialInk.oxide.copy(alpha = .84f),
                radius = w * .022f,
                center = Offset(blockLeft + blockWidth * .88f, blockTop + blockHeight * .20f)
            )
        }

        if (quality != EffectQuality.REDUCED_MOTION) {
            repeat(9) { index ->
                val seed = index / 9f
                val x = w * ((seed * 4.9f + phase * .12f) % 1f)
                val y = top + (bottom - top) * ((seed * 7.1f + phase * .22f) % 1f)
                drawLine(
                    color = Color(0xFFFFF9EC).copy(alpha = .11f + seed * .10f),
                    start = Offset(x, y),
                    end = Offset(x + w * .02f, y + h * .012f),
                    strokeWidth = .7f
                )
            }
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = .23f), Color.Transparent),
                center = light,
                radius = w * .60f
            ),
            radius = w * .60f,
            center = light
        )
    }
}

@Composable
private fun FluidQuotaManipulator(
    ratio: Float,
    limit: Int,
    pressed: Boolean,
    dragging: Boolean,
    locked: Boolean,
    charge: Float,
    anchor: Offset,
    quality: EffectQuality,
    active: Boolean
) {
    val moving = active && quality.allowMotion()
    val phase by fluidPhase(moving)
    Canvas(Modifier.fillMaxSize().testTag("phase2-quota-manipulator")) {
        val w = size.width
        val h = size.height
        val left = w * .08f
        val right = w * .92f
        val midY = h * .54f
        val x = left + (right - left) * ratio
        val stretch = if (dragging) .42f else if (pressed) .16f else 0f
        val wave = phase * PI.toFloat() * 2f

        drawRoundRect(
            color = FluidInk.cream.copy(alpha = .46f),
            cornerRadius = CornerRadius(w * .09f)
        )
        drawRoundRect(
            color = FluidInk.mineralDeep.copy(alpha = .11f),
            cornerRadius = CornerRadius(w * .09f),
            style = Stroke(width = 1f)
        )

        val trough = Path().apply {
            moveTo(left - w * .02f, midY)
            cubicTo(
                left + w * .02f, midY - h * .035f,
                right - w * .03f, midY - h * .030f,
                right + w * .02f, midY
            )
            cubicTo(
                right - w * .03f, midY + h * .042f,
                left + w * .02f, midY + h * .046f,
                left - w * .02f, midY
            )
            close()
        }
        drawPath(
            trough,
            brush = Brush.horizontalGradient(
                listOf(
                    Color(0xFF194E42).copy(alpha = .44f),
                    Color(0xFF2B7D68).copy(alpha = .36f),
                    Color(0xFFB8735F).copy(alpha = .36f)
                )
            )
        )

        val body = Path()
        val rx = w * (.16f + stretch * .06f)
        val ry = h * .15f
        for (index in 0..32) {
            val angle = index / 32f * PI.toFloat() * 2f
            val wobble = 1f +
                sin(angle * 3f + wave) * .06f +
                cos(angle * 5f - wave * 1.2f) * .035f
            val px = x + cos(angle) * rx * wobble * (.72f + stretch * .28f)
            val py = midY + sin(angle) * ry * wobble
            if (index == 0) body.moveTo(px, py) else body.lineTo(px, py)
        }
        body.close()

        val tail = Path().apply {
            moveTo(x - rx * .62f, midY)
            cubicTo(
                x - rx * (1.20f + stretch), midY - ry * .32f,
                x - rx * (1.50f + stretch * 1.6f), midY + ry * .42f,
                x - rx * (2.10f + stretch * 1.6f), midY + sin(wave) * 8f
            )
            cubicTo(
                x - rx * 1.35f, midY + ry * .16f,
                x - rx * .92f, midY + ry * .25f,
                x - rx * .42f, midY + ry * .30f
            )
            close()
        }
        drawPath(
            tail,
            brush = Brush.radialGradient(
                listOf(
                    FluidInk.teal.copy(alpha = .72f),
                    FluidInk.mineral.copy(alpha = .46f),
                    Color.Transparent
                ),
                center = Offset(x - rx, midY),
                radius = rx * 1.9f
            )
        )
        drawPath(
            body,
            brush = Brush.radialGradient(
                listOf(
                    Color(0xFF58B594).copy(alpha = .96f),
                    Color(0xFF267760).copy(alpha = .96f),
                    Color(0xFF133E35).copy(alpha = .98f)
                ),
                center = Offset(x - rx * .26f, midY - ry * .36f),
                radius = rx * 1.22f
            )
        )
        drawPath(
            body,
            color = Color.White.copy(alpha = .28f),
            style = Stroke(width = 1.4f)
        )
        drawOval(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = .50f), Color.Transparent),
                center = Offset(x - rx * .28f, midY - ry * .48f),
                radius = rx * .42f
            ),
            topLeft = Offset(x - rx * .58f, midY - ry * .62f),
            size = Size(rx * .70f, ry * .40f)
        )
        drawArc(
            color = Color.White.copy(alpha = .26f),
            startAngle = -160f + sin(wave) * 5f,
            sweepAngle = 128f,
            useCenter = false,
            topLeft = Offset(x - rx * .74f, midY - ry * .76f),
            size = Size(rx * 1.48f, ry * 1.50f),
            style = Stroke(width = 2.1f, cap = StrokeCap.Round)
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(FluidInk.apricot.copy(alpha = .78f), FluidInk.rose.copy(alpha = .40f), Color.Transparent),
                center = Offset(x + rx * .48f, midY + ry * .18f),
                radius = rx * .46f
            ),
            radius = rx * .46f,
            center = Offset(x + rx * .48f, midY + ry * .18f)
        )
        drawOval(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = .34f), Color.Transparent),
                center = Offset(x, midY - ry * .10f),
                radius = rx * .52f
            ),
            topLeft = Offset(x - rx * .46f, midY - ry * .42f),
            size = Size(rx * .92f, ry * .52f)
        )
        drawQuotaText(
            text = "$limit",
            x = x,
            y = midY + 4.sp.toPx(),
            color = Color.White,
            size = 15.sp.toPx(),
            alignCenter = true
        )
        drawQuotaText(
            text = "MIN",
            x = x,
            y = midY + 12.sp.toPx(),
            color = Color.White.copy(alpha = .62f),
            size = 5.sp.toPx(),
            alignCenter = true
        )

        if (charge > .02f) {
            drawArc(
                color = FluidInk.apricot.copy(alpha = .36f + charge * .46f),
                startAngle = -90f,
                sweepAngle = 360f * charge,
                useCenter = false,
                topLeft = Offset(x - rx * .90f, midY - ry * .96f),
                size = Size(rx * 1.80f, ry * 1.92f),
                style = Stroke(width = 2.3f, cap = StrokeCap.Round)
            )
        }
        if (locked) {
            drawCircle(
                color = FluidInk.cream.copy(alpha = .86f),
                center = Offset(x + rx * .54f, midY - ry * .48f),
                radius = 9f
            )
            drawCircle(
                color = FluidInk.mineralDeep,
                center = Offset(x + rx * .54f, midY - ry * .48f),
                radius = 5f,
                style = Stroke(width = 1.4f)
            )
        }
        repeat(if (quality == EffectQuality.REDUCED_MOTION) 0 else 10) { index ->
            val seed = index / 10f
            val angle = seed * PI.toFloat() * 2f + wave * .6f
            val orbit = rx * (.90f + seed * .50f)
            val px = x + cos(angle) * orbit
            val py = midY + sin(angle) * ry * .84f
            drawLine(
                color = Color.White.copy(alpha = .12f + seed * .16f),
                start = Offset(px, py),
                end = Offset(
                    px - sin(angle) * (5f + seed * 8f),
                    py + cos(angle) * (3f + seed * 5f)
                ),
                strokeWidth = .8f + seed,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun LegacyQuotaManipulator(ratio: Float, limit: Int) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = .035f), RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("15", color = Phase2Colors.inkMuted, fontSize = 9.sp)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(24.dp)
                    .background(Color.White.copy(alpha = .06f), RoundedCornerShape(50))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ratio)
                        .background(Phase2Colors.ice.copy(alpha = .55f), RoundedCornerShape(50))
                )
            }
            Spacer(Modifier.width(10.dp))
            Text("$limit", color = Phase2Colors.ink, fontSize = 12.sp)
        }
    }
}

@Composable
private fun QuotaFooter(
    direction: Phase2ArtDirection,
    limit: Int,
    locked: Boolean,
    charge: Float,
    onToggleLock: () -> Unit
) {
    val accent = accentFor(direction, limit, locked)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "15",
                color = mutedFor(direction),
                fontSize = 8.sp
            )
            Text(
                if (locked) "边界已锁定" else "长按锁定",
                color = accent,
                fontSize = 9.sp,
                letterSpacing = .7.sp,
                modifier = Modifier.clickableTest(onToggleLock, "phase2-quota-lock")
            )
            Text(
                "180",
                color = mutedFor(direction),
                fontSize = 8.sp
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(mutedFor(direction).copy(alpha = .18f), RoundedCornerShape(50))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(charge.coerceIn(0f, 1f))
                    .background(accent, RoundedCornerShape(50))
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(accent.copy(alpha = if (locked) .18f else .09f), RoundedCornerShape(50))
                    .border(1.dp, accent.copy(alpha = .32f), RoundedCornerShape(50))
                    .clickableTest(onToggleLock, "phase2-quota-primary"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (locked) "解" else "锁",
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (locked) "已完成" else "释放后保存当前边界",
                color = mutedFor(direction),
                fontSize = 9.sp
            )
        }
    }
}

private fun Modifier.clickableTest(onClick: () -> Unit, tag: String): Modifier =
    this
        .testTag(tag)
        .pointerInput(tag) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var up = false
                while (!up) {
                    val event = awaitPointerEvent()
                    up = event.changes.none { it.pressed }
                }
                if (up) onClick()
            }
        }

private fun quotaState(limit: Int, locked: Boolean): String = when {
    locked -> "边界已锁定"
    limit <= 46 -> "已到达限额"
    limit <= 60 -> "接近限额"
    else -> "正常"
}

private fun resistLimit(value: Int): Int = when {
    value < 30 -> 30 - ((30 - value) * .58f).roundToInt()
    value > 120 -> 120 + ((value - 120) * .68f).roundToInt()
    else -> value
}.coerceIn(15, 180)

private fun bucket(value: Int): Int = when {
    value < 30 -> 0
    value < 60 -> 1
    value < 90 -> 2
    value < 120 -> 3
    value < 180 -> 4
    else -> 5
}

private fun settleScale(pressed: Boolean, dragging: Boolean): Float = when {
    dragging -> 1f
    pressed -> .55f
    else -> 0f
}

private fun inkFor(direction: Phase2ArtDirection): Color = when (direction) {
    Phase2ArtDirection.LIQUID_GLASS -> LiquidInk.ink
    Phase2ArtDirection.SPATIAL -> SpatialInk.ink
    Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.ink
    Phase2ArtDirection.LEGACY -> Phase2Colors.ink
}

private fun mutedFor(direction: Phase2ArtDirection): Color = when (direction) {
    Phase2ArtDirection.LIQUID_GLASS -> LiquidInk.muted
    Phase2ArtDirection.SPATIAL -> SpatialInk.muted
    Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.muted
    Phase2ArtDirection.LEGACY -> Phase2Colors.inkMuted
}

private fun accentFor(direction: Phase2ArtDirection, limit: Int, locked: Boolean): Color {
    val urgent = limit <= 60
    return when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> when {
            locked -> LiquidInk.amber
            urgent -> LiquidInk.danger
            else -> LiquidInk.teal
        }
        Phase2ArtDirection.SPATIAL -> when {
            locked -> SpatialInk.amber
            urgent -> SpatialInk.oxide
            else -> SpatialInk.copper
        }
        Phase2ArtDirection.FLUID_GRADIENT -> when {
            locked -> FluidInk.warning
            urgent -> FluidInk.rose
            else -> FluidInk.mineral
        }
        Phase2ArtDirection.LEGACY -> when {
            urgent -> Phase2Colors.amber
            else -> Phase2Colors.ice
        }
    }
}

@Composable
private fun liquidPhase(moving: Boolean): State<Float> {
    if (!moving) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = "liquid-quota")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(
                    12_000,
                    easing = androidx.compose.animation.core.LinearEasing
                )
            ),
            label = "liquid-quota-phase"
        )
}

@Composable
private fun spatialPhase(moving: Boolean): State<Float> {
    if (!moving) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = "spatial-quota")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(
                    16_000,
                    easing = androidx.compose.animation.core.LinearEasing
                )
            ),
            label = "spatial-quota-phase"
        )
}

@Composable
private fun fluidPhase(moving: Boolean): State<Float> {
    if (!moving) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = "fluid-quota")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(
                    11_000,
                    easing = androidx.compose.animation.core.LinearEasing
                )
            ),
            label = "fluid-quota-phase"
        )
}

private fun DrawScope.drawQuotaText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    size: Float,
    alignCenter: Boolean = false
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgbCompat()
        textSize = size
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = if (alignCenter) Paint.Align.CENTER else Paint.Align.LEFT
    }
    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
}

private fun DrawScope.paintLockCharge(
    center: Offset,
    radius: Float,
    progress: Float,
    color: Color
) {
    drawCircle(
        color = color.copy(alpha = .12f),
        center = center,
        radius = radius
    )
    drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = 360f * progress.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = 2f, cap = StrokeCap.Round)
    )
    drawCircle(
        color = color.copy(alpha = .86f),
        center = center,
        radius = radius * .20f
    )
}

private fun Color.toArgbCompat(): Int =
    android.graphics.Color.argb(
        (alpha * 255f).roundToInt(),
        (red * 255f).roundToInt(),
        (green * 255f).roundToInt(),
        (blue * 255f).roundToInt()
    )
