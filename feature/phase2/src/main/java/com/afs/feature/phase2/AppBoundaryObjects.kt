package com.afs.feature.phase2

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun AppCard3D(
    app: AppView,
    now: Long,
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val direction = rememberPhase2ArtDirection()
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 6.dp)
            .testTag("phase2-app-card-${app.packageName}")
            .semantics {
                contentDescription =
                    "${app.label}，${app.status}，${app.allowedText}，${formatNext(app.nextChangeAt, now)}"
            }
            .alpha(if (app.installed) 1f else .62f)
    ) {
        when (direction) {
            Phase2ArtDirection.LIQUID_GLASS -> LiquidBoundaryObject(app, now, quality, active, onClick)
            Phase2ArtDirection.SPATIAL -> SpatialBoundaryObject(app, now, quality, active, onClick)
            Phase2ArtDirection.FLUID_GRADIENT -> FluidBoundaryObject(app, now, quality, active, onClick)
            Phase2ArtDirection.LEGACY -> LegacyAppCard3D(app, now, quality, active, onClick = onClick)
        }
    }
}

private data class BoundaryGestureState(
    val pressed: Boolean,
    val lifted: Boolean,
    val touched: Offset,
    val locked: Boolean,
    val expanded: Boolean
)

@Composable
private fun rememberBoundaryGesture(
    app: AppView,
    quality: EffectQuality,
    active: Boolean,
    onClick: () -> Unit
): Pair<Modifier, BoundaryGestureState> {
    val haptics = LocalHapticFeedback.current
    var pressed by remember(app.packageName) { mutableStateOf(false) }
    var lifted by remember(app.packageName) { mutableStateOf(false) }
    var touched by remember(app.packageName) { mutableStateOf(Offset(.5f, .5f)) }
    var locked by remember(app.packageName) { mutableStateOf(false) }
    var expanded by remember(app.packageName) { mutableStateOf(false) }
    val enabled = active && quality.allowMotion()
    val modifier = Modifier
        .pointerInput(app.packageName, enabled) {
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
        .pointerInput(app.packageName, enabled) {
            detectTapGestures(
                onPress = {
                    lifted = true
                    tryAwaitRelease()
                    lifted = false
                },
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    locked = !locked
                    expanded = true
                },
                onTap = {
                    expanded = !expanded
                    onClick()
                }
            )
        }
    return modifier to BoundaryGestureState(pressed, lifted, touched, locked, expanded)
}

@Composable
private fun LiquidBoundaryObject(
    app: AppView,
    now: Long,
    quality: EffectQuality,
    active: Boolean,
    onClick: () -> Unit
) {
    val (gesture, state) = rememberBoundaryGesture(app, quality, active, onClick)
    val moving = active && quality.allowMotion()
    val compression by animateFloatAsState(
        if (state.pressed && moving) 1f else 0f,
        spring(.80f, 460f),
        label = "liquid-object-pressure"
    )
    val lift by animateFloatAsState(
        if (state.lifted && moving) -4f else 0f,
        spring(.78f, 360f),
        label = "liquid-object-lift"
    )
    val tiltY by animateFloatAsState(
        if (moving) (state.touched.x - .5f) * 5f else 0f,
        spring(.80f, 420f),
        label = "liquid-object-tilt"
    )
    val ratio = remember(app) { Phase2ArtDirection.LIQUID_GLASS.allowedRatio(app) }
    val accent = if (app.blocked) LiquidInk.teal else Color(0xFF2E7C5C)

    Column(gesture) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(184.dp)
                .graphicsLayer {
                    translationY = lift * density
                    rotationY = tiltY
                    scaleX = 1f + compression * .006f
                    scaleY = 1f - compression * .016f
                    cameraDistance = 22f * density
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val corner = CornerRadius(w * .075f)
                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    setShadowLayer(
                        if (quality == EffectQuality.HIGH) 26f else 14f,
                        0f,
                        if (state.lifted) 18f else 10f,
                        0x4211252D
                    )
                }
                drawIntoCanvas {
                    it.nativeCanvas.drawRoundRect(
                        RectF(w * .035f, h * .09f, w * .965f, h * .94f),
                        corner.x,
                        corner.y,
                        shadow
                    )
                }
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .88f),
                            Color(0xB8EAF2F5),
                            Color(0x9DC8DCE2)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    ),
                    cornerRadius = corner
                )
                drawRoundRect(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.White.copy(alpha = .92f),
                            Color.White.copy(alpha = .18f),
                            Color(0x80145B66),
                            Color.White.copy(alpha = .76f)
                        )
                    ),
                    cornerRadius = corner,
                    style = Stroke(2.2f)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = .52f),
                    topLeft = Offset(w * .025f, h * .055f),
                    size = Size(w * .95f, h * .82f),
                    cornerRadius = CornerRadius(w * .065f),
                    style = Stroke(1f)
                )
                clipPath(
                    Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = 0f,
                                top = 0f,
                                right = w,
                                bottom = h,
                                cornerRadius = corner
                            )
                        )
                    }
                ) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White.copy(alpha = .72f), Color.Transparent),
                            center = Offset(w * .06f, h * .02f),
                            radius = w * .72f
                        ),
                        center = Offset(w * .06f, h * .02f),
                        radius = w * .72f
                    )
                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = .58f), Color.Transparent)
                        ),
                        start = Offset(w * .08f, h * .15f),
                        end = Offset(w * .78f, h * .04f),
                        strokeWidth = 5f,
                        cap = StrokeCap.Round
                    )
                }

                val trackLeft = w * .055f
                val trackRight = w * .945f
                val trackY = h * .83f
                drawRoundRect(
                    color = Color(0x33214C57),
                    topLeft = Offset(trackLeft, trackY),
                    size = Size(trackRight - trackLeft, h * .036f),
                    cornerRadius = CornerRadius(h * .018f)
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = .40f),
                    topLeft = Offset(trackLeft + 1.5f, trackY + 1.5f),
                    size = Size(trackRight - trackLeft - 3f, h * .016f),
                    cornerRadius = CornerRadius(h * .009f)
                )
                val width = (trackRight - trackLeft) * ratio
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(LiquidInk.teal.copy(alpha = .76f), Color(0xFF8CDDDD), LiquidInk.amber.copy(alpha = .62f))
                    ),
                    topLeft = Offset(trackLeft, trackY),
                    size = Size(width.coerceAtLeast(h * .018f), h * .036f),
                    cornerRadius = CornerRadius(h * .018f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White, Color(0xFFB5F2EE), LiquidInk.teal),
                        center = Offset(trackLeft + width, trackY + h * .018f),
                        radius = h * .035f
                    ),
                    radius = h * .019f,
                    center = Offset(trackLeft + width, trackY + h * .018f)
                )
                if (state.locked) {
                    drawCircle(
                        color = LiquidInk.amber.copy(alpha = .44f),
                        radius = h * .055f,
                        center = Offset(trackLeft + width, trackY + h * .018f),
                        style = Stroke(2f)
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 19.dp, vertical = 17.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoundaryIcon(app, liquid = true, spatial = false, fluid = false)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            app.label,
                            color = LiquidInk.ink,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            app.packageName,
                            color = LiquidInk.muted.copy(alpha = .78f),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        app.status,
                        color = accent,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = .38f), RoundedCornerShape(50))
                            .border(1.dp, accent.copy(alpha = .26f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidData("放行", app.allowedText, Modifier.weight(1f))
                    LiquidData("下一次", formatNext(app.nextChangeAt, now), Modifier.weight(1f))
                }
            }
        }
        AppObjectDetails(
            direction = Phase2ArtDirection.LIQUID_GLASS,
            app = app,
            visible = state.expanded,
            locked = state.locked
        )
    }
}

@Composable
private fun SpatialBoundaryObject(
    app: AppView,
    now: Long,
    quality: EffectQuality,
    active: Boolean,
    onClick: () -> Unit
) {
    val (gesture, state) = rememberBoundaryGesture(app, quality, active, onClick)
    val moving = active && quality.allowMotion()
    val sink by animateFloatAsState(
        if (state.pressed && moving) 1f else 0f,
        spring(.74f, 360f),
        label = "spatial-object-sink"
    )
    val lift by animateFloatAsState(
        if (state.lifted && moving) -3f else 0f,
        spring(.76f, 300f),
        label = "spatial-object-lift"
    )
    val rotX by animateFloatAsState(
        if (moving) (state.touched.y - .5f) * -5f else 0f,
        spring(.78f, 280f),
        label = "spatial-object-rot-x"
    )
    val rotY by animateFloatAsState(
        if (moving) (state.touched.x - .5f) * 6f else -1.5f,
        spring(.78f, 280f),
        label = "spatial-object-rot-y"
    )
    val ratio = remember(app) { Phase2ArtDirection.SPATIAL.allowedRatio(app) }
    val accent = if (app.blocked) SpatialInk.oxide else SpatialInk.copper

    Column(gesture) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(188.dp)
                .graphicsLayer {
                    translationY = (lift + sink * 3f) * density
                    rotationX = rotX
                    rotationY = rotY
                    cameraDistance = 18f * density
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val left = w * .025f
                val right = w * .975f
                val top = h * .06f
                val bottom = h * .89f
                val bevel = w * .018f

                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    setShadowLayer(
                        if (quality == EffectQuality.HIGH) 24f else 15f,
                        w * .022f,
                        h * .055f,
                        0x4D2C2119
                    )
                }
                drawIntoCanvas {
                    it.nativeCanvas.drawRoundRect(
                        RectF(left, top, right, bottom),
                        bevel * 2f,
                        bevel * 2f,
                        shadow
                    )
                }
                val side = Path().apply {
                    moveTo(right, top + bevel)
                    lineTo(right + w * .012f, top + h * .035f)
                    lineTo(right + w * .012f, bottom + h * .01f)
                    lineTo(right, bottom)
                    close()
                }
                drawPath(side, color = Color(0xFF897C6B))
                val topFace = Path().apply {
                    moveTo(left, top)
                    lineTo(left + bevel, top - h * .022f)
                    lineTo(right + w * .012f, top - h * .022f)
                    lineTo(right, top + bevel)
                    close()
                }
                drawPath(
                    topFace,
                    brush = Brush.linearGradient(listOf(Color(0xFFF7F1E7), SpatialInk.stone))
                )
                val face = Path().apply {
                    moveTo(left, top)
                    lineTo(right, top + bevel)
                    lineTo(right, bottom)
                    lineTo(left, bottom)
                    close()
                }
                drawPath(
                    face,
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFE6DED2), SpatialInk.stone, Color(0xFFBFB4A4)),
                        start = Offset(left, top),
                        end = Offset(right, bottom)
                    )
                )
                for (index in 0..6) {
                    val y = top + (bottom - top) * index / 6f
                    drawLine(
                        color = SpatialInk.graphite.copy(alpha = .025f),
                        start = Offset(left + 2f, y),
                        end = Offset(right - 2f, y),
                        strokeWidth = 1f
                    )
                }
                drawLine(
                    color = Color.White.copy(alpha = .50f),
                    start = Offset(left + 2f, top + 2f),
                    end = Offset(left + 2f, bottom - 2f),
                    strokeWidth = 2f
                )
                drawLine(
                    color = SpatialInk.graphite.copy(alpha = .18f),
                    start = Offset(right - 2f, top + bevel),
                    end = Offset(right - 2f, bottom),
                    strokeWidth = 2f
                )

                val grooveLeft = w * .78f
                val grooveRight = w * .95f
                val grooveTop = h * .18f
                val grooveBottom = h * .73f
                for (index in 0..11) {
                    val x = grooveLeft + (grooveRight - grooveLeft) * index / 11f
                    val filled = index / 11f <= ratio
                    drawRoundRect(
                        color = if (filled) accent.copy(alpha = .72f) else SpatialInk.graphite.copy(alpha = .10f),
                        topLeft = Offset(x - w * .005f, grooveTop),
                        size = Size(w * .010f, grooveBottom - grooveTop),
                        cornerRadius = CornerRadius(w * .005f)
                    )
                }
                drawOval(
                    color = SpatialInk.shadow.copy(alpha = .58f),
                    topLeft = Offset(left - w * .02f, bottom - h * .018f),
                    size = Size(right - left + w * .07f, h * .065f)
                )
                if (state.locked) {
                    drawRoundRect(
                        color = SpatialInk.amber.copy(alpha = .72f),
                        topLeft = Offset(grooveLeft - w * .012f, grooveTop - h * .022f),
                        size = Size(grooveRight - grooveLeft + w * .024f, grooveBottom - grooveTop + h * .044f),
                        cornerRadius = CornerRadius(w * .018f),
                        style = Stroke(2f)
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 78.dp, top = 18.dp, bottom = 17.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoundaryIcon(app, liquid = false, spatial = true, fluid = false)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            app.label,
                            color = SpatialInk.ink,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            app.status,
                            color = accent,
                            fontSize = 10.sp,
                            letterSpacing = .4.sp
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SpatialData("边界", app.allowedText)
                    SpatialData("下次", formatNext(app.nextChangeAt, now))
                }
                Text(
                    app.packageName,
                    color = SpatialInk.muted.copy(alpha = .72f),
                    fontSize = 8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        AppObjectDetails(
            direction = Phase2ArtDirection.SPATIAL,
            app = app,
            visible = state.expanded,
            locked = state.locked
        )
    }
}

@Composable
private fun FluidBoundaryObject(
    app: AppView,
    now: Long,
    quality: EffectQuality,
    active: Boolean,
    onClick: () -> Unit
) {
    val (gesture, state) = rememberBoundaryGesture(app, quality, active, onClick)
    val moving = active && quality.allowMotion()
    val pressure by animateFloatAsState(
        if (state.pressed && moving) 1f else 0f,
        spring(.66f, 340f),
        label = "fluid-object-pressure"
    )
    val tiltX by animateFloatAsState(
        if (moving) (state.touched.y - .5f) * -3f else 0f,
        spring(.74f, 260f),
        label = "fluid-object-tilt-x"
    )
    val tiltY by animateFloatAsState(
        if (moving) (state.touched.x - .5f) * 4f else 0f,
        spring(.74f, 260f),
        label = "fluid-object-tilt-y"
    )
    val ratio = remember(app) { Phase2ArtDirection.FLUID_GRADIENT.allowedRatio(app) }
    val phase = if (moving) {
        val transition = rememberInfiniteTransition(label = "fluid-object-flow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (PI * 2).toFloat(),
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(
                    11_000,
                    easing = androidx.compose.animation.core.LinearEasing
                )
            ),
            label = "fluid-object-phase"
        ).value
    } else 0f
    val shape = remember {
        GenericShape { size, _ ->
            val w = size.width
            val h = size.height
            moveTo(w * .06f, h * .13f)
            cubicTo(w * .18f, h * .015f, w * .77f, h * .01f, w * .93f, h * .15f)
            cubicTo(w * 1.01f, h * .30f, w * .95f, h * .82f, w * .84f, h * .91f)
            cubicTo(w * .66f, h * 1.01f, w * .16f, h * .99f, w * .07f, h * .84f)
            cubicTo(w * -.015f, h * .67f, w * .00f, h * .28f, w * .06f, h * .13f)
            close()
        }
    }
    val accent = if (app.blocked) FluidInk.rose else FluidInk.mineral

    Column(gesture) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(184.dp)
                .graphicsLayer {
                    scaleX = 1f + pressure * .010f
                    scaleY = 1f - pressure * .020f
                    rotationX = tiltX
                    rotationY = tiltY
                    cameraDistance = 20f * density
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val body = Path().apply {
                    moveTo(w * .06f, h * .13f)
                    cubicTo(w * .18f, h * .015f, w * .77f, h * .01f, w * .93f, h * .15f)
                    cubicTo(w * 1.01f, h * .30f, w * .95f, h * .82f, w * .84f, h * .91f)
                    cubicTo(w * .66f, h * 1.01f, w * .16f, h * .99f, w * .07f, h * .84f)
                    cubicTo(w * -.015f, h * .67f, w * .00f, h * .28f, w * .06f, h * .13f)
                    close()
                }
                drawPath(
                    body,
                    color = FluidInk.shadow,
                    alpha = .60f
                )
                val shifted = Path().apply {
                    addPath(body, Offset(0f, -h * .012f))
                }
                drawPath(
                    shifted,
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFFFAF2E7),
                            Color(0xFFE8DDCB),
                            Color(0xFFD5C7B1)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    )
                )
                clipPath(shifted) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(FluidInk.apricot.copy(alpha = .20f), Color.Transparent),
                            center = Offset(w * .78f, h * .18f),
                            radius = w * .38f
                        ),
                        center = Offset(w * .78f, h * .18f),
                        radius = w * .38f
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(FluidInk.mineral.copy(alpha = .13f), Color.Transparent),
                            center = Offset(w * .14f, h * .72f),
                            radius = w * .46f
                        ),
                        center = Offset(w * .14f, h * .72f),
                        radius = w * .46f
                    )
                    for (index in 0 until 4) {
                        val x = w * (.28f + index * .16f) + sin(phase * .3f + index) * w * .010f
                        val y = h * (.75f + sin(phase * .2f + index * 1.8f) * .015f)
                        drawOval(
                            color = Color.White.copy(alpha = .22f + index * .03f),
                            topLeft = Offset(x, y),
                            size = Size(w * .13f, h * .07f)
                        )
                    }
                }
                drawPath(
                    shifted,
                    color = Color.White.copy(alpha = .34f),
                    style = Stroke(1.3f)
                )

                val levelLeft = w * .10f
                val levelRight = w * .90f
                val levelY = h * .84f
                drawRoundRect(
                    color = FluidInk.mineralDeep.copy(alpha = .10f),
                    topLeft = Offset(levelLeft, levelY),
                    size = Size(levelRight - levelLeft, h * .038f),
                    cornerRadius = CornerRadius(h * .019f)
                )
                val fillWidth = (levelRight - levelLeft) * ratio
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(FluidInk.mineral, FluidInk.teal, FluidInk.apricot)
                    ),
                    topLeft = Offset(levelLeft, levelY),
                    size = Size(fillWidth.coerceAtLeast(h * .02f), h * .038f),
                    cornerRadius = CornerRadius(h * .019f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White, FluidInk.cream, FluidInk.teal),
                        center = Offset(levelLeft + fillWidth, levelY + h * .019f),
                        radius = h * .042f
                    ),
                    radius = h * .022f,
                    center = Offset(levelLeft + fillWidth, levelY + h * .019f)
                )
                if (state.locked) {
                    drawCircle(
                        color = FluidInk.warning.copy(alpha = .52f),
                        radius = h * .052f,
                        center = Offset(levelLeft + fillWidth, levelY + h * .019f),
                        style = Stroke(2f)
                    )
                }
                if (pressure > .01f) {
                    val point = Offset(
                        w * (.15f + state.touched.x * .70f),
                        h * (.15f + state.touched.y * .58f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White.copy(alpha = .48f * pressure), Color.Transparent),
                            center = point,
                            radius = w * .16f
                        ),
                        center = point,
                        radius = w * .16f
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .padding(horizontal = 20.dp, vertical = 17.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BoundaryIcon(app, liquid = false, spatial = false, fluid = true)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            app.label,
                            color = FluidInk.ink,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            app.status,
                            color = accent,
                            fontSize = 10.sp
                        )
                    }
                    Box(
                        Modifier
                            .size(27.dp)
                            .background(accent.copy(alpha = .13f), CircleShape)
                            .border(1.dp, accent.copy(alpha = .28f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (state.locked) "锁" else "生",
                            color = accent,
                            fontSize = 9.sp
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluidData("放行", app.allowedText, Modifier.weight(1f))
                    FluidData("下一次", formatNext(app.nextChangeAt, now), Modifier.weight(1f))
                }
            }
        }
        AppObjectDetails(
            direction = Phase2ArtDirection.FLUID_GRADIENT,
            app = app,
            visible = state.expanded,
            locked = state.locked
        )
    }
}

@Composable
private fun BoundaryIcon(app: AppView, liquid: Boolean, spatial: Boolean, fluid: Boolean) {
    val size = if (spatial) 50.dp else 48.dp
    val shape = when {
        spatial -> RoundedCornerShape(10.dp)
        fluid -> RoundedCornerShape(18.dp, 10.dp, 20.dp, 12.dp)
        else -> RoundedCornerShape(16.dp)
    }
    val background = when {
        liquid -> Brush.radialGradient(listOf(Color.White.copy(alpha = .82f), Color(0x804E929C)))
        spatial -> Brush.linearGradient(listOf(Color(0xFFF6F0E7), SpatialInk.stoneDark))
        else -> Brush.linearGradient(listOf(FluidInk.cream, FluidInk.apricot.copy(alpha = .78f)))
    }
    Box(
        Modifier
            .size(size)
            .background(background, shape)
            .border(
                1.dp,
                when {
                    liquid -> Color.White.copy(alpha = .72f)
                    spatial -> SpatialInk.graphite.copy(alpha = .20f)
                    else -> FluidInk.mineralDeep.copy(alpha = .16f)
                },
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(if (spatial) 7.dp else 12.dp))
            )
        } else {
            Text(
                app.label.take(1).uppercase(),
                color = when {
                    liquid -> LiquidInk.ink
                    spatial -> SpatialInk.ink
                    else -> FluidInk.ink
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LiquidData(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.White.copy(alpha = .28f), RoundedCornerShape(13.dp))
            .border(1.dp, Color.White.copy(alpha = .42f), RoundedCornerShape(13.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = LiquidInk.muted, fontSize = 8.sp, letterSpacing = .5.sp)
        Text(
            value,
            color = LiquidInk.ink,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SpatialData(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = SpatialInk.muted, fontSize = 8.sp, letterSpacing = .6.sp)
        Text(
            value,
            color = SpatialInk.ink,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FluidData(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(FluidInk.cream.copy(alpha = .48f), RoundedCornerShape(14.dp, 9.dp, 14.dp, 9.dp))
            .border(1.dp, FluidInk.mineralDeep.copy(alpha = .10f), RoundedCornerShape(14.dp, 9.dp, 14.dp, 9.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = FluidInk.muted, fontSize = 8.sp, letterSpacing = .5.sp)
        Text(
            value,
            color = FluidInk.ink,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AppObjectDetails(
    direction: Phase2ArtDirection,
    app: AppView,
    visible: Boolean,
    locked: Boolean
) {
    val background = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> Color.White.copy(alpha = .34f)
        Phase2ArtDirection.SPATIAL -> Color(0xFFE7DFD3)
        Phase2ArtDirection.FLUID_GRADIENT -> Color(0xFFF4ECDF)
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
        enter = fadeIn() + slideInVertically { -it / 8 },
        exit = fadeOut() + slideOutVertically { -it / 10 }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 4.dp)
                .background(background, RoundedCornerShape(18.dp))
                .border(1.dp, accent.copy(alpha = .22f), RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppDetailRow("星期", daysText(app.daysMask), ink, muted)
            AppDetailRow("模式", if (app.freezeMode) "时段内冻结" else "时段内放行", ink, muted)
            AppDetailRow(
                "规则",
                if (app.windows.isEmpty()) "无时段" else app.windows.joinToString("、") {
                    "${clock(it.start)}–${clock(if (it.end == 0) 1440 else it.end)}"
                },
                ink,
                muted
            )
            AppDetailRow("学习闸门", if (app.gate) "参与" else "豁免", ink, muted)
            AppDetailRow("长按状态", if (locked) "边界已标记，不改变真实冻结" else "未标记", ink, muted)
        }
    }
}

@Composable
private fun AppDetailRow(label: String, value: String, ink: Color, muted: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = muted, fontSize = 10.sp)
        Text(
            value,
            color = ink.copy(alpha = .92f),
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
