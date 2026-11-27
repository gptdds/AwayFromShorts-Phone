package com.afs.feature.phase2

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.guard.style.EffectQuality
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun FocusRing3D(
    focus: FocusView,
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {}
) {
    val direction = rememberPhase2ArtDirection()
    Box(
        modifier
            .fillMaxWidth()
            .testTag("phase2-focus-ring")
            .semantics {
                contentDescription =
                    "专注主控台，${focus.status}，剩余 ${formatDuration(focus.remainingMs)}，长按查看状态详情"
            }
    ) {
        when (direction) {
            Phase2ArtDirection.LIQUID_GLASS -> LiquidFocusConsole(focus, quality, active, onLongPress)
            Phase2ArtDirection.SPATIAL -> SpatialFocusConsole(focus, quality, active, onLongPress)
            Phase2ArtDirection.FLUID_GRADIENT -> FluidFocusConsole(focus, quality, active, onLongPress)
            Phase2ArtDirection.LEGACY -> LegacyFocusRing3D(focus, quality, active, onLongPress = onLongPress)
        }
    }
}

@Composable
private fun LiquidFocusConsole(
    focus: FocusView,
    quality: EffectQuality,
    active: Boolean,
    onLongPress: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(Offset(.5f, .5f)) }
    var detail by remember { mutableStateOf(false) }
    val moving = active && quality.allowMotion()
    val pressure by animateFloatAsState(
        if (pressed && moving) 1f else 0f,
        spring(.78f, 430f),
        label = "liquid-focus-pressure"
    )
    val tiltX by animateFloatAsState(
        if (moving) (touched.y - .5f) * -4.5f else 0f,
        spring(.78f, 420f),
        label = "liquid-focus-tilt-x"
    )
    val tiltY by animateFloatAsState(
        if (moving) (touched.x - .5f) * 5.5f else 0f,
        spring(.78f, 420f),
        label = "liquid-focus-tilt-y"
    )
    val progress by animateFloatAsState(
        focus.progress.coerceIn(0f, 1f),
        spring(.84f, 170f),
        label = "liquid-focus-progress"
    )
    val phase = if (moving && quality == EffectQuality.HIGH) {
        val transition = rememberInfiniteTransition(label = "liquid-focus-light")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(tween(15_000, easing = LinearEasing)),
            label = "liquid-focus-light-angle"
        ).value
    } else 0f

    val gesture = Modifier
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.pressed) {
                        pressed = true
                        touched = Offset(
                            (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                    } else {
                        pressed = false
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                detail = !detail
                onLongPress()
            })
        }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(392.dp)
                .then(gesture)
                .graphicsLayer {
                    rotationX = tiltX
                    rotationY = tiltY
                    scaleX = 1f + pressure * .012f
                    scaleY = 1f - pressure * .018f
                    cameraDistance = 22f * density
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val center = Offset(w * .50f, h * .46f)
                val lensRadius = min(w * .36f, h * .36f)
                val rim = min(w, h) * .082f

                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFF3F7F8), LiquidInk.field, LiquidInk.fieldDeep),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    ),
                    cornerRadius = CornerRadius(w * .09f)
                )
                for (index in 0..4) {
                    val y = h * (.08f + index * .2f)
                    drawLine(
                        color = Color.White.copy(alpha = .20f + index * .025f),
                        start = Offset(w * .04f, y),
                        end = Offset(w * .96f, y - h * .055f),
                        strokeWidth = 1f
                    )
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = .82f), Color.Transparent),
                        center = Offset(w * .22f, h * .16f),
                        radius = w * .72f
                    ),
                    center = Offset(w * .22f, h * .16f),
                    radius = w * .72f
                )
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(Color(0x660B3442), Color.Transparent),
                        center = Offset(center.x + w * .06f, center.y + h * .19f),
                        radius = lensRadius * 1.45f
                    ),
                    topLeft = Offset(center.x - lensRadius * 1.38f, center.y + h * .05f),
                    size = Size(lensRadius * 2.76f, lensRadius * .55f)
                )

                val glassShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.TRANSPARENT
                    setShadowLayer(
                        if (quality == EffectQuality.HIGH) 38f else 24f,
                        0f,
                        18f,
                        0x4A0A2730
                    )
                }
                drawIntoCanvas {
                    it.nativeCanvas.drawCircle(center.x, center.y, lensRadius + rim * .35f, glassShadow)
                }

                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = .68f),
                            Color(0x88D9EEF3),
                            Color(0x382E7180),
                            Color.Transparent
                        ),
                        center = Offset(center.x - lensRadius * .22f, center.y - lensRadius * .28f),
                        radius = lensRadius * 1.18f
                    ),
                    center = center,
                    radius = lensRadius + rim
                )
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color(0x24FFFFFF),
                            Color(0xE8FFFFFF),
                            Color(0x4AFFFFFF),
                            Color(0x2212313A),
                            Color(0xD6FFFFFF),
                            Color(0x2FFFFFFF)
                        ),
                        center = center
                    ),
                    center = center,
                    radius = lensRadius + rim * .55f,
                    style = Stroke(rim * 1.12f)
                )
                drawCircle(
                    color = Color(0x7AFFFFFF),
                    center = center,
                    radius = lensRadius + rim * .07f,
                    style = Stroke(rim * .22f)
                )
                drawCircle(
                    color = Color(0x3D0E4754),
                    center = center,
                    radius = lensRadius - rim * .58f,
                    style = Stroke(rim * .32f)
                )

                val start = -90f
                val sweep = 360f * progress
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color(0xFF85DDE3),
                            LiquidInk.teal,
                            Color(0xFF0A5D68),
                            Color(0xFFB6ECE8),
                            LiquidInk.teal
                        ),
                        center = center
                    ),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - lensRadius, center.y - lensRadius),
                    size = Size(lensRadius * 2f, lensRadius * 2f),
                    style = Stroke(rim * .58f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color.White.copy(alpha = .64f),
                    startAngle = start + 8f,
                    sweepAngle = (sweep - 16f).coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = Offset(
                        center.x - lensRadius + rim * .12f,
                        center.y - lensRadius + rim * .12f
                    ),
                    size = Size(
                        lensRadius * 2f - rim * .24f,
                        lensRadius * 2f - rim * .24f
                    ),
                    style = Stroke(rim * .10f, cap = StrokeCap.Round)
                )
                val markerAngle = Math.toRadians((start + sweep).toDouble())
                val marker = Offset(
                    center.x + cos(markerAngle).toFloat() * lensRadius,
                    center.y + sin(markerAngle).toFloat() * lensRadius
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White, Color(0xFFA5F0EB), LiquidInk.tealDeep),
                        center = marker,
                        radius = rim * .58f
                    ),
                    center = marker,
                    radius = rim * .42f
                )
                drawCircle(Color.White.copy(alpha = .75f), rim * .13f, marker)

                val bandRotation = Math.toRadians((touched.x * 120f - 60f + phase * 10f).toDouble())
                val bandDx = cos(bandRotation).toFloat() * (lensRadius + rim) * .88f
                val bandDy = sin(bandRotation).toFloat() * (lensRadius + rim) * .88f
                drawLine(
                    brush = Brush.linearGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = .44f),
                            Color(0xFF9AE4E9).copy(alpha = .25f),
                            Color.Transparent
                        )
                    ),
                    start = Offset(center.x - bandDx, center.y - bandDy),
                    end = Offset(center.x + bandDx, center.y + bandDy),
                    strokeWidth = rim * .16f,
                    cap = StrokeCap.Round
                )

                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            Color(0xFFF8FCFD),
                            Color(0xD9E6F2F5),
                            Color(0x9AC4DDE4),
                            Color(0x5E7EADB9)
                        ),
                        center = Offset(center.x - lensRadius * .2f, center.y - lensRadius * .24f),
                        radius = lensRadius * .9f
                    ),
                    center = center,
                    radius = lensRadius - rim * .62f
                )
                drawCircle(
                    color = Color.White.copy(alpha = .65f),
                    center = center,
                    radius = lensRadius - rim * .72f,
                    style = Stroke(1.7f)
                )
                drawCircle(
                    color = Color(0xFF17343E).copy(alpha = .08f),
                    center = Offset(center.x + lensRadius * .08f, center.y + lensRadius * .1f),
                    radius = lensRadius - rim * .82f,
                    style = Stroke(rim * .05f)
                )

                if (moving) {
                    for (index in 0 until 24) {
                        val seed = ((index * 37) % 101) / 101f
                        val angle = phase * (.20f + seed * .18f) + seed * PI.toFloat() * 2f
                        val orbit = lensRadius * (1.02f + seed * .34f)
                        val point = Offset(
                            center.x + cos(angle) * orbit,
                            center.y + sin(angle) * orbit * .82f
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = .16f + seed * .22f),
                            radius = .9f + seed * 1.9f,
                            center = point
                        )
                    }
                }

                if (pressure > .01f) {
                    drawCircle(
                        color = LiquidInk.teal.copy(alpha = .20f * pressure),
                        center = Offset(center.x + (touched.x - .5f) * lensRadius, center.y + (touched.y - .5f) * lensRadius),
                        radius = lensRadius * (.08f + pressure * .07f)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = .46f * pressure),
                        center = center,
                        radius = lensRadius * (1f + pressure * .035f),
                        style = Stroke(rim * .07f)
                    )
                }

                drawRoundRect(
                    color = Color.White.copy(alpha = .44f),
                    topLeft = Offset(w * .04f, h * .03f),
                    size = Size(w * .92f, h * .94f),
                    cornerRadius = CornerRadius(w * .085f),
                    style = Stroke(1.2f)
                )
            }

            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    formatDuration(focus.remainingMs),
                    color = LiquidInk.ink,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = .4.sp
                )
                Text(
                    "距下一次计划切换",
                    color = LiquidInk.muted,
                    fontSize = 11.sp,
                    letterSpacing = .6.sp
                )
                Spacer(Modifier.height(11.dp))
                Text(
                    focus.status,
                    color = if (focus.blockedApps > 0) LiquidInk.tealDeep else Color(0xFF276C4D),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = .42f), RoundedCornerShape(50))
                        .border(
                            1.dp,
                            if (focus.blockedApps > 0) LiquidInk.teal.copy(alpha = .34f)
                            else Color(0xFF3B8C66).copy(alpha = .34f),
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 15.dp, vertical = 6.dp)
                )
            }

            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp, bottom = 23.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiquidMicroLabel("受管", "${focus.managedApps}")
                LiquidMicroLabel("冻结", "${focus.blockedApps}")
                LiquidMicroLabel("压力", "${(focus.progress.coerceIn(0f, 1f) * 100).toInt()}%")
            }
        }
        FocusDetailPanel(direction = Phase2ArtDirection.LIQUID_GLASS, focus = focus, visible = detail)
    }
}

@Composable
private fun LiquidMicroLabel(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, color = LiquidInk.muted, fontSize = 8.sp, letterSpacing = .8.sp)
        Text(value, color = LiquidInk.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SpatialFocusConsole(
    focus: FocusView,
    quality: EffectQuality,
    active: Boolean,
    onLongPress: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(Offset(.5f, .5f)) }
    var detail by remember { mutableStateOf(false) }
    val moving = active && quality.allowMotion()
    val sink by animateFloatAsState(
        if (pressed && moving) 1f else 0f,
        spring(.72f, 340f),
        label = "spatial-focus-sink"
    )
    val tiltX by animateFloatAsState(
        if (moving) (touched.y - .5f) * -7f else 0f,
        spring(.76f, 300f),
        label = "spatial-focus-tilt-x"
    )
    val tiltY by animateFloatAsState(
        if (moving) (touched.x - .5f) * 9f else -2f,
        spring(.76f, 300f),
        label = "spatial-focus-tilt-y"
    )
    val progress by animateFloatAsState(
        focus.progress.coerceIn(0f, 1f),
        spring(.88f, 190f),
        label = "spatial-focus-progress"
    )

    val gesture = Modifier
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.pressed) {
                        pressed = true
                        touched = Offset(
                            (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                    } else {
                        pressed = false
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                detail = !detail
                onLongPress()
            })
        }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(392.dp)
                .then(gesture)
                .graphicsLayer {
                    rotationX = tiltX
                    rotationY = tiltY
                    translationY = sink * 3.dp.toPx()
                    cameraDistance = 18f * density
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val back = Offset(w * .5f, h * .41f)

                drawRect(
                    brush = Brush.linearGradient(
                        listOf(SpatialInk.stoneLight, SpatialInk.field, SpatialInk.fieldDeep),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    )
                )
                drawOval(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = .92f), Color.Transparent),
                        center = Offset(w * .2f, h * .08f),
                        radius = w * .82f
                    ),
                    topLeft = Offset(-w * .2f, -h * .2f),
                    size = Size(w * 1.3f, h * .8f)
                )

                val floor = Path().apply {
                    moveTo(w * .06f, h * .78f)
                    lineTo(w * .94f, h * .78f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    floor,
                    brush = Brush.verticalGradient(
                        listOf(SpatialInk.fieldDeep, Color(0xFFB9AE9D)),
                        startY = h * .78f,
                        endY = h
                    )
                )
                for (index in 1..5) {
                    val fraction = index / 6f
                    val y = h * (.78f + fraction * .22f)
                    drawLine(
                        color = SpatialInk.graphite.copy(alpha = .08f + fraction * .04f),
                        start = Offset(w * (.06f - fraction * .05f), y),
                        end = Offset(w * (.94f + fraction * .05f), y),
                        strokeWidth = 1f
                    )
                }
                drawLine(
                    color = SpatialInk.oxide.copy(alpha = .20f),
                    start = Offset(w * .5f, h * .77f),
                    end = Offset(w * .5f, h),
                    strokeWidth = 1.3f
                )

                val slabLeft = w * .17f
                val slabRight = w * .83f
                val slabTop = h * .15f
                val slabBottom = h * .79f
                val bevel = w * .025f

                val cast = Path().apply {
                    moveTo(slabLeft + w * .035f, slabBottom - h * .01f)
                    lineTo(slabRight + w * .08f, slabBottom - h * .04f)
                    lineTo(slabRight + w * .13f, h * .94f)
                    lineTo(slabLeft - w * .02f, h * .96f)
                    close()
                }
                drawPath(cast, color = SpatialInk.shadow)
                drawOval(
                    color = SpatialInk.shadow.copy(alpha = .34f),
                    topLeft = Offset(slabLeft - w * .04f, h * .75f),
                    size = Size(slabRight - slabLeft + w * .08f, h * .08f)
                )

                val side = Path().apply {
                    moveTo(slabRight, slabTop + bevel)
                    lineTo(slabRight + w * .045f, slabTop + h * .035f)
                    lineTo(slabRight + w * .045f, slabBottom + h * .015f)
                    lineTo(slabRight, slabBottom)
                    close()
                }
                drawPath(
                    side,
                    brush = Brush.verticalGradient(listOf(SpatialInk.stoneDark, Color(0xFF8C8171)))
                )
                val top = Path().apply {
                    moveTo(slabLeft, slabTop)
                    lineTo(slabLeft + bevel, slabTop - h * .025f)
                    lineTo(slabRight + w * .045f, slabTop - h * .025f)
                    lineTo(slabRight, slabTop + bevel)
                    close()
                }
                drawPath(
                    top,
                    brush = Brush.linearGradient(listOf(Color(0xFFF7F2E9), SpatialInk.stone))
                )
                val face = Path().apply {
                    moveTo(slabLeft, slabTop)
                    lineTo(slabRight, slabTop + bevel)
                    lineTo(slabRight, slabBottom)
                    lineTo(slabLeft, slabBottom)
                    close()
                }
                drawPath(
                    face,
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFE9E2D7), SpatialInk.stone, Color(0xFFBDB2A0)),
                        start = Offset(slabLeft, slabTop),
                        end = Offset(slabRight, slabBottom)
                    )
                )
                for (index in 0..7) {
                    val y = slabTop + (slabBottom - slabTop) * index / 7f
                    drawLine(
                        color = SpatialInk.graphite.copy(alpha = .025f + (index % 2) * .015f),
                        start = Offset(slabLeft + 2f, y),
                        end = Offset(slabRight - 2f, y - bevel * .1f),
                        strokeWidth = 1f
                    )
                }
                drawLine(
                    color = Color.White.copy(alpha = .54f),
                    start = Offset(slabLeft + 1.5f, slabTop + 1.5f),
                    end = Offset(slabLeft + 1.5f, slabBottom - 2f),
                    strokeWidth = 2f
                )
                drawLine(
                    color = SpatialInk.graphite.copy(alpha = .16f),
                    start = Offset(slabRight - 1.5f, slabTop + bevel),
                    end = Offset(slabRight - 1.5f, slabBottom),
                    strokeWidth = 2f
                )

                val slotLeft = slabRight - w * .075f
                val slotTop = h * .27f
                val slotBottom = h * .69f
                drawRoundRect(
                    color = SpatialInk.graphite.copy(alpha = .22f),
                    topLeft = Offset(slotLeft, slotTop),
                    size = Size(w * .025f, slotBottom - slotTop),
                    cornerRadius = CornerRadius(w * .012f)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = .32f),
                    topLeft = Offset(slotLeft + 1.5f, slotTop + 1.5f),
                    size = Size(w * .018f, slotBottom - slotTop - 3f),
                    cornerRadius = CornerRadius(w * .009f)
                )
                val fillHeight = (slotBottom - slotTop) * progress
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(SpatialInk.amber, SpatialInk.oxide)
                    ),
                    topLeft = Offset(slotLeft + 3f, slotBottom - fillHeight),
                    size = Size(w * .014f, fillHeight),
                    cornerRadius = CornerRadius(w * .007f)
                )
                drawCircle(
                    color = Color(0xFFF2D19B),
                    radius = w * .010f,
                    center = Offset(slotLeft + w * .010f, slabBottom - fillHeight)
                )

                val horizontalTick = h * .51f
                for (index in 0..10) {
                    val x = slabLeft + (slabRight - slabLeft) * index / 10f
                    val major = index % 5 == 0
                    drawLine(
                        color = SpatialInk.graphite.copy(alpha = if (major) .22f else .09f),
                        start = Offset(x, horizontalTick),
                        end = Offset(x, horizontalTick + if (major) 14f else 7f),
                        strokeWidth = if (major) 1.4f else .8f
                    )
                }
                drawLine(
                    color = SpatialInk.oxide.copy(alpha = .52f),
                    start = Offset(slabLeft, horizontalTick),
                    end = Offset(slabRight, horizontalTick),
                    strokeWidth = 1f
                )

                drawRoundRect(
                    color = SpatialInk.graphite.copy(alpha = .18f + sink * .1f),
                    topLeft = Offset(slabLeft + 8f, slabBottom - 4f),
                    size = Size(slabRight - slabLeft + w * .04f, 7f),
                    cornerRadius = CornerRadius(4f)
                )
                if (pressed) {
                    drawCircle(
                        color = Color.White.copy(alpha = .28f),
                        center = Offset(
                            slabLeft + (slabRight - slabLeft) * touched.x,
                            slabTop + (slabBottom - slabTop) * touched.y
                        ),
                        radius = w * .085f
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(top = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "CURRENT BOUNDARY",
                    color = SpatialInk.oxide,
                    fontSize = 9.sp,
                    letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    formatDuration(focus.remainingMs),
                    color = SpatialInk.ink,
                    fontSize = 43.sp,
                    fontWeight = FontWeight.Light
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    focus.status,
                    color = SpatialInk.graphite,
                    fontSize = 12.sp,
                    letterSpacing = .5.sp
                )
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 38.dp, end = 38.dp, bottom = 21.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SpatialMetric("受管", focus.managedApps.toString())
                SpatialMetric("冻结", focus.blockedApps.toString())
                SpatialMetric("边界", "${(progress * 100).toInt()}%")
            }
        }
        FocusDetailPanel(direction = Phase2ArtDirection.SPATIAL, focus = focus, visible = detail)
    }
}

@Composable
private fun SpatialMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = SpatialInk.muted, fontSize = 8.sp, letterSpacing = .8.sp)
        Text(value, color = SpatialInk.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FluidFocusConsole(
    focus: FocusView,
    quality: EffectQuality,
    active: Boolean,
    onLongPress: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(Offset(.5f, .5f)) }
    var detail by remember { mutableStateOf(false) }
    val moving = active && quality.allowMotion()
    val pressure by animateFloatAsState(
        if (pressed && moving) 1f else 0f,
        spring(.66f, 330f),
        label = "fluid-focus-pressure"
    )
    val distortion by animateFloatAsState(
        if (moving) (touched.x - .5f) * 1.4f else 0f,
        spring(.72f, 260f),
        label = "fluid-focus-distortion"
    )
    val progress by animateFloatAsState(
        focus.progress.coerceIn(0f, 1f),
        spring(.70f, 150f),
        label = "fluid-focus-progress"
    )
    val phase = if (moving) {
        val transition = rememberInfiniteTransition(label = "fluid-focus-flow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
            label = "fluid-focus-flow-angle"
        ).value
    } else 0f

    val gesture = Modifier
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.pressed) {
                        pressed = true
                        touched = Offset(
                            (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        )
                    } else {
                        pressed = false
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                detail = !detail
                onLongPress()
            })
        }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(392.dp)
                .then(gesture)
                .graphicsLayer {
                    scaleX = 1f + pressure * .014f
                    scaleY = 1f - pressure * .022f
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val center = Offset(w * .50f + distortion * w * .025f, h * .47f)
                val baseRadius = min(w * .35f, h * .35f)

                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(FluidInk.fieldWarm, FluidInk.field, Color(0xFFE3D5C0)),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    ),
                    cornerRadius = CornerRadius(w * .10f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = .72f), Color.Transparent),
                        center = Offset(w * .15f, h * .08f),
                        radius = w * .78f
                    ),
                    center = Offset(w * .15f, h * .08f),
                    radius = w * .78f
                )

                fun blob(
                    radius: Float,
                    phaseOffset: Float,
                    squashX: Float,
                    squashY: Float,
                    lobes: FloatArray
                ): Path {
                    val path = Path()
                    val steps = 72
                    var previous = Offset.Unspecified
                    for (index in 0..steps) {
                        val angle = (index / steps.toFloat()) * PI.toFloat() * 2f
                        val lobe = 1f +
                            sin(angle * 3f + phase * .65f + phaseOffset) * lobes[0] +
                            sin(angle * 5f - phase * .48f + phaseOffset * 1.7f) * lobes[1] +
                            cos(angle * 2f + phase * .34f + distortion * 1.8f) * lobes[2]
                        val point = Offset(
                            center.x + cos(angle) * radius * squashX * lobe,
                            center.y + sin(angle) * radius * squashY * lobe
                        )
                        if (index == 0) {
                            path.moveTo(point.x, point.y)
                        } else {
                            val mid = Offset((previous.x + point.x) / 2f, (previous.y + point.y) / 2f)
                            path.quadraticBezierTo(previous.x, previous.y, mid.x, mid.y)
                        }
                        previous = point
                    }
                    path.close()
                    return path
                }

                val shadowBlob = blob(baseRadius * 1.04f, 2.2f, 1.08f, .74f, floatArrayOf(.055f, .035f, .045f))
                drawPath(
                    shadowBlob,
                    color = FluidInk.shadow,
                    alpha = .72f
                )
                val body = blob(baseRadius, .1f, 1.02f, .76f, floatArrayOf(.075f, .045f, .055f))
                drawPath(
                    body,
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFF3DB59A),
                            FluidInk.mineral,
                            FluidInk.mineralDeep
                        ),
                        start = Offset(center.x - baseRadius, center.y - baseRadius),
                        end = Offset(center.x + baseRadius, center.y + baseRadius)
                    )
                )
                clipPath(body) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(FluidInk.apricot.copy(alpha = .86f), Color.Transparent),
                            center = Offset(center.x + baseRadius * .54f, center.y - baseRadius * .20f),
                            radius = baseRadius * .72f
                        ),
                        center = Offset(center.x + baseRadius * .54f, center.y - baseRadius * .20f),
                        radius = baseRadius * .72f
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(FluidInk.rose.copy(alpha = .48f), Color.Transparent),
                            center = Offset(center.x - baseRadius * .50f, center.y + baseRadius * .36f),
                            radius = baseRadius * .58f
                        ),
                        center = Offset(center.x - baseRadius * .50f, center.y + baseRadius * .36f),
                        radius = baseRadius * .58f
                    )
                    for (index in 0 until 5) {
                        val angle = phase * (.22f + index * .035f) + index * 1.3f
                        drawCircle(
                            color = Color.White.copy(alpha = .055f + index * .012f),
                            center = Offset(
                                center.x + cos(angle) * baseRadius * .48f,
                                center.y + sin(angle) * baseRadius * .24f
                            ),
                            radius = baseRadius * (.20f + index * .035f)
                        )
                    }
                }
                drawPath(
                    body,
                    color = Color.White.copy(alpha = .15f),
                    style = Stroke(2.2f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(FluidInk.cream, Color(0xFFE8DDCB), Color(0x00E8DDCB)),
                        center = Offset(center.x - baseRadius * .18f, center.y - baseRadius * .22f),
                        radius = baseRadius * .67f
                    ),
                    center = Offset(center.x, center.y),
                    radius = baseRadius * .55f
                )
                drawOval(
                    color = Color.White.copy(alpha = .50f),
                    topLeft = Offset(
                        center.x - baseRadius * .48f,
                        center.y - baseRadius * .46f
                    ),
                    size = Size(baseRadius * .72f, baseRadius * .20f)
                )

                val arcRadius = baseRadius * 1.05f
                drawArc(
                    color = FluidInk.apricot.copy(alpha = .78f),
                    startAngle = -78f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius * .78f),
                    size = Size(arcRadius * 2f, arcRadius * 1.56f),
                    style = Stroke(baseRadius * .055f, cap = StrokeCap.Round)
                )
                drawArc(
                    color = Color.White.copy(alpha = .34f),
                    startAngle = -76f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(
                        center.x - arcRadius + baseRadius * .022f,
                        center.y - arcRadius * .78f + baseRadius * .022f
                    ),
                    size = Size(
                        arcRadius * 2f - baseRadius * .044f,
                        arcRadius * 1.56f - baseRadius * .044f
                    ),
                    style = Stroke(baseRadius * .012f, cap = StrokeCap.Round)
                )

                if (quality.allowParticles()) {
                    for (index in 0 until 34) {
                        val seed = ((index * 53) % 97) / 97f
                        val angle = phase * (.18f + seed * .22f) + seed * 6.2f
                        val radius = baseRadius * (1.12f + seed * .38f)
                        val point = Offset(
                            center.x + cos(angle) * radius,
                            center.y + sin(angle) * radius * .68f
                        )
                        drawCircle(
                            color = if (index % 3 == 0) FluidInk.apricot.copy(alpha = .42f)
                            else Color.White.copy(alpha = .28f + seed * .18f),
                            radius = .8f + seed * 2.2f,
                            center = point
                        )
                    }
                }

                if (pressure > .01f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White.copy(alpha = .48f * pressure), Color.Transparent),
                            center = Offset(
                                center.x + (touched.x - .5f) * baseRadius * .9f,
                                center.y + (touched.y - .5f) * baseRadius * .65f
                            ),
                            radius = baseRadius * (.16f + pressure * .12f)
                        ),
                        center = Offset(
                            center.x + (touched.x - .5f) * baseRadius * .9f,
                            center.y + (touched.y - .5f) * baseRadius * .65f
                        ),
                        radius = baseRadius * (.16f + pressure * .12f)
                    )
                }

                drawRoundRect(
                    color = Color.White.copy(alpha = .35f),
                    topLeft = Offset(w * .04f, h * .03f),
                    size = Size(w * .92f, h * .94f),
                    cornerRadius = CornerRadius(w * .095f),
                    style = Stroke(1.2f)
                )
            }

            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    formatDuration(focus.remainingMs),
                    color = FluidInk.ink,
                    fontSize = 41.sp,
                    fontWeight = FontWeight.Light
                )
                Text(
                    focus.status,
                    color = FluidInk.mineral,
                    fontSize = 11.sp,
                    letterSpacing = .7.sp
                )
            }

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 34.dp, end = 34.dp, bottom = 22.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FluidMetric("受管", focus.managedApps.toString(), FluidInk.mineral)
                FluidMetric("冻结", focus.blockedApps.toString(), FluidInk.rose)
                FluidMetric("体积", "${(progress * 100).toInt()}%", FluidInk.warning)
            }
        }
        FocusDetailPanel(direction = Phase2ArtDirection.FLUID_GRADIENT, focus = focus, visible = detail)
    }
}

@Composable
private fun FluidMetric(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = FluidInk.muted, fontSize = 8.sp, letterSpacing = .8.sp)
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FocusDetailPanel(
    direction: Phase2ArtDirection,
    focus: FocusView,
    visible: Boolean
) {
    val dark = direction == Phase2ArtDirection.FLUID_GRADIENT
    val background = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> Color.White.copy(alpha = .45f)
        Phase2ArtDirection.SPATIAL -> Color(0xFFE8E0D4)
        Phase2ArtDirection.FLUID_GRADIENT -> Color(0xFFF3EBDD)
        Phase2ArtDirection.LEGACY -> Color(0xFF13233A)
    }
    val ink = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> LiquidInk.ink
        Phase2ArtDirection.SPATIAL -> SpatialInk.ink
        Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.ink
        Phase2ArtDirection.LEGACY -> Color.White
    }
    val muted = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> LiquidInk.muted
        Phase2ArtDirection.SPATIAL -> SpatialInk.muted
        Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.muted
        Phase2ArtDirection.LEGACY -> Color(0xFF96A8C4)
    }
    val accent = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> LiquidInk.teal
        Phase2ArtDirection.SPATIAL -> SpatialInk.oxide
        Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.mineral
        Phase2ArtDirection.LEGACY -> Phase2Colors.ice
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 5 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 6 }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(background, RoundedCornerShape(22.dp))
                .border(1.dp, accent.copy(alpha = if (dark) .24f else .32f), RoundedCornerShape(22.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("状态详情", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            FocusDetailRow("受管应用", "${focus.managedApps} 个", ink, muted)
            FocusDetailRow("实际冻结", "${focus.blockedApps} 个", ink, muted)
            FocusDetailRow(
                "最近计划变化",
                if (focus.lastChangeAt > 0) formatClock(focus.lastChangeAt) else "暂无记录",
                ink,
                muted
            )
            FocusDetailRow(
                "下一次计划变化",
                if (focus.nextChangeAt > 0) formatClock(focus.nextChangeAt) else "暂无计划",
                ink,
                muted
            )
            Text(focus.detail, color = muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun FocusDetailRow(label: String, value: String, ink: Color, muted: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = muted, fontSize = 11.sp)
        Text(value, color = ink, fontSize = 11.sp)
    }
}
