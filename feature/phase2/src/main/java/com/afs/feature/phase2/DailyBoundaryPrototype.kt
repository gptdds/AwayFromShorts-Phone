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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.roundToInt
import kotlin.math.sin

private data class BoundaryItem(
    val name: String,
    val usedMinutes: Int,
    val limitMinutes: Int,
    val urgent: Boolean,
    val reached: Boolean
) {
    val ratio: Float get() = (usedMinutes.toFloat() / limitMinutes.coerceAtLeast(1)).coerceIn(0f, 1f)
}

private val prototypeBounds = listOf(
    BoundaryItem("哔哩哔哩", 102, 90, urgent = true, reached = true),
    BoundaryItem("Telegram", 46, 60, urgent = true, reached = false),
    BoundaryItem("Edge", 28, 60, urgent = false, reached = false),
    BoundaryItem("Notion", 18, 120, urgent = false, reached = false),
    BoundaryItem("抖音", 12, 30, urgent = true, reached = false)
)

private val stressBounds = listOf(
    "哔哩哔哩 · 学习与知识区超长频道名称",
    "Microsoft Edge · 工作资料与跨设备同步",
    "Telegram · 项目讨论与文件传输",
    "抖音 · 短视频与直播内容",
    "Notion · 学习计划与项目资料"
)

/**
 * Prototype C. Daily boundaries are ordered by urgency and draw from the same data
 * contract in all three art directions.
 */
@Composable
fun DailyBoundaryPrototype(
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    appLabels: List<String> = emptyList(),
    stress: Boolean = false,
    onGestureEnergy: (energy: Float, anchor: Offset) -> Unit = { _, _ -> }
) {
    val direction = rememberPhase2ArtDirection()
    val items = remember(appLabels, stress) {
        val labels = appLabels.distinct().filter { it.isNotBlank() }
        val source = if (stress) {
            List(50) { index ->
                val template = prototypeBounds[index % prototypeBounds.size]
                template.copy(
                    name = "${stressBounds[index % stressBounds.size]} ${index + 1}",
                    usedMinutes = (template.usedMinutes + index * 3).coerceAtMost(240),
                    limitMinutes = (template.limitMinutes + index % 4 * 15).coerceAtLeast(15)
                )
            }
        } else {
            prototypeBounds.mapIndexed { index, item ->
                if (labels.isEmpty()) item else item.copy(name = labels[index % labels.size])
            }
        }
        source.sortedWith(
            compareByDescending<BoundaryItem> { it.reached }
                .thenByDescending { it.ratio }
        )
    }
    var selected by remember { mutableIntStateOf(0) }
    var adjusting by remember { mutableStateOf(false) }
    var pressedRow by remember { mutableIntStateOf(-1) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(pressedRow, selected) {
        onGestureEnergy(
            if (pressedRow >= 0) .46f else .05f,
            Offset(.55f, .42f)
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag("phase2-daily-prototype")
    ) {
        if (direction == Phase2ArtDirection.FLUID_GRADIENT) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xFFF7EEDF).copy(alpha = .10f),
                            .18f to Color(0xFFF7EEDF).copy(alpha = .72f),
                            .62f to Color(0xFFF4EBDD).copy(alpha = .88f),
                            1f to Color(0xFFF3E9DA).copy(alpha = .78f)
                        )
                    )
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            DailyHeader(
                direction = direction,
                totalMinutes = items.sumOf { it.usedMinutes },
                itemCount = items.size,
                reachedCount = items.count { it.reached },
                adjusting = adjusting
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(
                    when (direction) {
                        Phase2ArtDirection.SPATIAL -> 5.dp
                        Phase2ArtDirection.FLUID_GRADIENT -> 6.dp
                        else -> 2.dp
                    }
                )
            ) {
                items.forEachIndexed { index, item ->
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    LaunchedEffect(pressed, index) {
                        if (pressed) {
                            pressedRow = index
                            selected = index
                        } else if (pressedRow == index) {
                            pressedRow = -1
                        }
                    }
                    val pressScale by animateFloatAsState(
                        targetValue = if (pressed) .985f else 1f,
                        animationSpec = spring(.76f, 430f),
                        label = "daily-row-scale-$index"
                    )
                    val topBias = when (direction) {
                        Phase2ArtDirection.SPATIAL -> if (item.reached) 10.dp else if (item.urgent) 4.dp else 0.dp
                        Phase2ArtDirection.FLUID_GRADIENT -> if (item.reached) 3.dp else 0.dp
                        else -> 0.dp
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = pressScale
                                scaleY = pressScale
                                translationY = if (pressed) 2f else 0f
                                translationX = when (direction) {
                                    Phase2ArtDirection.LIQUID_GLASS -> (index % 2) * 5f
                                    Phase2ArtDirection.FLUID_GRADIENT -> (index % 3) * 3f
                                    else -> 0f
                                }
                            }
                            .padding(top = topBias)
                            .clickable(
                                interactionSource = interaction,
                                indication = null
                            ) {
                                selected = index
                                adjusting = item.urgent || item.reached
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            .testTag("phase2-daily-row-$index")
                    ) {
                        when (direction) {
                            Phase2ArtDirection.LIQUID_GLASS ->
                                LiquidBoundaryRow(item, index, selected == index, pressed)
                            Phase2ArtDirection.SPATIAL ->
                                SpatialBoundaryRow(item, index, selected == index, pressed)
                            Phase2ArtDirection.FLUID_GRADIENT ->
                                FluidBoundaryRow(item, index, selected == index, pressed)
                            Phase2ArtDirection.LEGACY ->
                                LegacyBoundaryRow(item, selected == index)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(
                            accentFor(direction).copy(alpha = .11f),
                            RoundedCornerShape(
                                when (direction) {
                                    Phase2ArtDirection.SPATIAL -> 3.dp
                                    Phase2ArtDirection.FLUID_GRADIENT -> 22.dp
                                    else -> 16.dp
                                }
                            )
                        )
                        .border(
                            1.dp,
                            accentFor(direction).copy(alpha = .34f),
                            RoundedCornerShape(
                                when (direction) {
                                    Phase2ArtDirection.SPATIAL -> 3.dp
                                    Phase2ArtDirection.FLUID_GRADIENT -> 22.dp
                                    else -> 16.dp
                                }
                            )
                        )
                        .clickable {
                            adjusting = !adjusting
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        .testTag("phase2-daily-primary"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (adjusting) "收起边界" else "调整今日边界",
                        color = accentFor(direction),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("重置", color = mutedFor(direction), fontSize = 8.sp, letterSpacing = 1.sp)
                    Text(
                        "00:00",
                        color = inkFor(direction),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        if (adjusting) {
            DailyAdjustmentCue(
                direction = direction,
                item = items[selected.coerceIn(items.indices)],
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 20.dp)
            )
        }
    }
}

@Composable
fun LiquidGlassPhase2Daily(
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    appLabels: List<String> = emptyList(),
    stress: Boolean = false,
    onGestureEnergy: (Float, Offset) -> Unit = { _, _ -> }
) {
    CompositionLocalProvider(LocalPhase2ArtDirection provides Phase2ArtDirection.LIQUID_GLASS) {
        DailyBoundaryPrototype(
            quality = quality,
            active = active,
            modifier = modifier,
            appLabels = appLabels,
            stress = stress,
            onGestureEnergy = onGestureEnergy
        )
    }
}

@Composable
fun SpatialPhase2Daily(
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    appLabels: List<String> = emptyList(),
    stress: Boolean = false,
    onGestureEnergy: (Float, Offset) -> Unit = { _, _ -> }
) {
    CompositionLocalProvider(LocalPhase2ArtDirection provides Phase2ArtDirection.SPATIAL) {
        DailyBoundaryPrototype(
            quality = quality,
            active = active,
            modifier = modifier,
            appLabels = appLabels,
            stress = stress,
            onGestureEnergy = onGestureEnergy
        )
    }
}

@Composable
fun FluidGradientPhase2Daily(
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    appLabels: List<String> = emptyList(),
    stress: Boolean = false,
    onGestureEnergy: (Float, Offset) -> Unit = { _, _ -> }
) {
    CompositionLocalProvider(LocalPhase2ArtDirection provides Phase2ArtDirection.FLUID_GRADIENT) {
        DailyBoundaryPrototype(
            quality = quality,
            active = active,
            modifier = modifier,
            appLabels = appLabels,
            stress = stress,
            onGestureEnergy = onGestureEnergy
        )
    }
}

@Composable
private fun DailyHeader(
    direction: Phase2ArtDirection,
    totalMinutes: Int,
    itemCount: Int,
    reachedCount: Int,
    adjusting: Boolean
) {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 128.dp)
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = .74f),
                                Color(0xFFD6E6E7).copy(alpha = .54f),
                                Color(0xFFAAC6CB).copy(alpha = .40f)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(w, h)
                        ),
                        cornerRadius = CornerRadius(w * .075f)
                    )
                    drawRoundRect(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color.White.copy(alpha = .88f),
                                Color.White.copy(alpha = .08f),
                                Color(0xFF3A808B).copy(alpha = .28f),
                                Color.White.copy(alpha = .72f)
                            )
                        ),
                        cornerRadius = CornerRadius(w * .075f),
                        style = Stroke(width = 1.6f)
                    )
                    for (index in 0 until 4) {
                        drawLine(
                            color = Color.White.copy(alpha = .21f + index * .03f),
                            start = Offset(w * .05f, h * (.18f + index * .18f)),
                            end = Offset(w * .95f, h * (.12f + index * .18f)),
                            strokeWidth = .9f
                        )
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(0xFF77C9CB).copy(alpha = .20f), Color.Transparent),
                            center = Offset(w * .79f, h * .35f),
                            radius = w * .28f
                        ),
                        radius = w * .28f,
                        center = Offset(w * .79f, h * .35f)
                    )
                    drawLine(
                        color = LiquidInk.teal.copy(alpha = .46f),
                        start = Offset(w * .73f, h * .19f),
                        end = Offset(w * .73f, h * .82f),
                        strokeWidth = 1f
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("今日边界", color = LiquidInk.muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
                    Text(
                        "$hours:${minutes.toString().padStart(2, '0')}",
                        color = LiquidInk.ink,
                        fontSize = 39.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text("$itemCount 个应用 · $reachedCount 个已到达", color = LiquidInk.muted, fontSize = 9.sp)
                }
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 20.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text("剩余", color = LiquidInk.muted, fontSize = 8.sp, letterSpacing = 1.sp)
                    Text(
                        "0:34",
                        color = LiquidInk.tealDeep,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (adjusting) "编辑" else "稳定",
                        color = LiquidInk.tealDeep.copy(alpha = .76f),
                        fontSize = 8.sp
                    )
                }
            }
        }
        Phase2ArtDirection.SPATIAL -> {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp)
                    .graphicsLayer {
                        rotationX = 2.4f
                        cameraDistance = 18f * density
                    }
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.TRANSPARENT
                        setShadowLayer(21f, w * .035f, h * .08f, 0x4A221A12)
                    }
                    drawIntoCanvas {
                        it.nativeCanvas.drawRoundRect(
                            RectF(0f, h * .02f, w, h * .90f),
                            w * .026f,
                            w * .026f,
                            shadow
                        )
                    }
                    val side = Path().apply {
                        moveTo(w, h * .06f)
                        lineTo(w, h * .90f)
                        lineTo(w - w * .035f, h * .84f)
                        lineTo(w - w * .035f, h * .02f)
                        close()
                    }
                    drawPath(side, color = Color(0xFF6F6558))
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFFE6DDD1), Color(0xFFC2B5A4), Color(0xFF9D9080)),
                            start = Offset(0f, 0f),
                            end = Offset(w, h)
                        ),
                        topLeft = Offset(0f, 0f),
                        size = Size(w - w * .035f, h * .86f),
                        cornerRadius = CornerRadius(w * .022f)
                    )
                    drawLine(
                        color = Color.White.copy(alpha = .50f),
                        start = Offset(w * .02f, h * .025f),
                        end = Offset(w * .96f, h * .025f),
                        strokeWidth = 1.8f
                    )
                    drawLine(
                        color = Color(0xFF41392F).copy(alpha = .24f),
                        start = Offset(w * .04f, h * .84f),
                        end = Offset(w * .96f, h * .84f),
                        strokeWidth = 2f
                    )
                    val light = Offset(w * .13f, h * .02f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White.copy(alpha = .42f), Color.Transparent),
                            center = light,
                            radius = w * .58f
                        ),
                        radius = w * .58f,
                        center = light
                    )
                    drawRect(
                        color = SpatialInk.oxide.copy(alpha = .72f),
                        topLeft = Offset(w * .82f, h * .18f),
                        size = Size(w * .016f, h * .48f)
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("TODAY", color = SpatialInk.muted, fontSize = 8.sp, letterSpacing = 1.8.sp)
                    Text(
                        "$hours:${minutes.toString().padStart(2, '0')}",
                        color = SpatialInk.ink,
                        fontSize = 41.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("$itemCount 个边界 · $reachedCount 个到达", color = SpatialInk.muted, fontSize = 9.sp)
                }
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 26.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text("RESET", color = SpatialInk.muted, fontSize = 7.sp, letterSpacing = 1.4.sp)
                    Text("00:00", color = SpatialInk.oxide, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text("边界 $reachedCount / $itemCount", color = SpatialInk.ink.copy(alpha = .72f), fontSize = 8.sp)
                }
            }
        }
        Phase2ArtDirection.FLUID_GRADIENT -> {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp)
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    val body = Path().apply {
                        moveTo(w * .02f, h * .24f)
                        cubicTo(w * .18f, h * .02f, w * .70f, h * .03f, w * .95f, h * .18f)
                        cubicTo(w * 1.03f, h * .52f, w * .88f, h * .88f, w * .64f, h * .91f)
                        cubicTo(w * .34f, h * 1.02f, w * .05f, h * .78f, w * .01f, h * .58f)
                        close()
                    }
                    drawPath(
                        body,
                        brush = Brush.linearGradient(
                            listOf(
                                Color(0xFFF4C192).copy(alpha = .82f),
                                Color(0xFFE19576).copy(alpha = .55f),
                                Color(0xFF2A7D66).copy(alpha = .52f)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(w, h)
                        )
                    )
                    drawPath(
                        body,
                        color = Color.White.copy(alpha = .20f),
                        style = Stroke(width = 1.3f)
                    )
                    drawOval(
                        brush = Brush.radialGradient(
                            listOf(Color.White.copy(alpha = .36f), Color.Transparent),
                            center = Offset(w * .24f, h * .22f),
                            radius = w * .44f
                        ),
                        topLeft = Offset(w * .04f, h * .03f),
                        size = Size(w * .62f, h * .34f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(0xFF1B604F).copy(alpha = .24f), Color.Transparent),
                            center = Offset(w * .78f, h * .70f),
                            radius = w * .32f
                        ),
                        radius = w * .32f,
                        center = Offset(w * .78f, h * .70f)
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("今日边界", color = Color(0xFF3A5A4D), fontSize = 9.sp, letterSpacing = 1.1.sp)
                    Text(
                        "$hours:${minutes.toString().padStart(2, '0')}",
                        color = FluidInk.ink,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text("$itemCount 个应用 · $reachedCount 个已到达", color = FluidInk.muted, fontSize = 9.sp)
                }
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 22.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text("重置", color = FluidInk.muted, fontSize = 8.sp)
                    Text("00:00", color = FluidInk.mineralDeep, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(7.dp))
                    Box(
                        Modifier
                            .background(FluidInk.cream.copy(alpha = .58f), RoundedCornerShape(50))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text("稳定", color = FluidInk.mineral, fontSize = 8.sp)
                    }
                }
            }
        }
        Phase2ArtDirection.LEGACY -> {
            Column {
                Text("今日边界", color = Phase2Colors.inkMuted, fontSize = 10.sp)
                Text(
                    "$hours:${minutes.toString().padStart(2, '0')}",
                    color = Phase2Colors.ink,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Light
                )
            }
        }
    }
}

@Composable
private fun LiquidBoundaryRow(
    item: BoundaryItem,
    index: Int,
    selected: Boolean,
    pressed: Boolean
) {
    val accent = when {
        item.reached -> LiquidInk.danger
        item.urgent -> LiquidInk.amber
        else -> LiquidInk.teal
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (item.reached) 82.dp else 68.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val inset = w * .035f + (index % 2) * w * .012f
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                setShadowLayer(13f, 0f, if (pressed) 5f else 8f, 0x2611252D)
            }
            drawIntoCanvas {
                it.nativeCanvas.drawRoundRect(
                    RectF(inset, 4f, w - inset, h - 4f),
                    w * .035f,
                    w * .035f,
                    shadow
                )
            }
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (selected) .82f else .58f),
                        Color(0xFFD3E4E6).copy(alpha = if (selected) .64f else .40f),
                        Color(0xFFAEC6CB).copy(alpha = .34f)
                    ),
                    start = Offset(inset, 0f),
                    end = Offset(w - inset, h)
                ),
                topLeft = Offset(inset, 2f),
                size = Size(w - inset * 2f, h - 4f),
                cornerRadius = CornerRadius(w * .035f)
            )
            drawRoundRect(
                color = Color.White.copy(alpha = .54f),
                topLeft = Offset(inset + 1f, 3f),
                size = Size(w - inset * 2f - 2f, h - 6f),
                cornerRadius = CornerRadius(w * .034f),
                style = Stroke(width = 1f)
            )
            drawLine(
                color = accent.copy(alpha = if (selected) .72f else .38f),
                start = Offset(inset + w * .02f, h * .18f),
                end = Offset(inset + w * .02f, h * .82f),
                strokeWidth = if (selected) 2.6f else 1.5f,
                cap = StrokeCap.Round
            )
            val trackLeft = inset + w * .68f
            val trackRight = w - inset - w * .05f
            val trackY = h - h * .21f
            drawRoundRect(
                color = Color(0xFF315661).copy(alpha = .14f),
                topLeft = Offset(trackLeft, trackY),
                size = Size(trackRight - trackLeft, h * .045f),
                cornerRadius = CornerRadius(h * .025f)
            )
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(accent.copy(alpha = .72f), Color(0xFF8CD8D7).copy(alpha = .58f))
                ),
                topLeft = Offset(trackLeft, trackY),
                size = Size((trackRight - trackLeft) * item.ratio, h * .045f),
                cornerRadius = CornerRadius(h * .025f)
            )
            drawQuotaText(
                text = "${item.usedMinutes}",
                x = trackRight - 1f,
                y = trackY - 4f,
                color = accent,
                size = 7.sp.toPx(),
                alignCenter = true
            )
        }
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BoundaryGlyph(item.name, 36.dp, Phase2ArtDirection.LIQUID_GLASS, accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    color = LiquidInk.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${minutesText(item.usedMinutes)} / ${minutesText(item.limitMinutes)}",
                    color = LiquidInk.muted,
                    fontSize = 9.sp
                )
            }
            Text(
                when {
                    item.reached -> "到达"
                    item.urgent -> "接近"
                    else -> "正常"
                },
                color = accent,
                fontSize = 9.sp
            )
            Spacer(Modifier.width(8.dp))
            BoundaryArrow(accent)
        }
    }
}

@Composable
private fun SpatialBoundaryRow(
    item: BoundaryItem,
    index: Int,
    selected: Boolean,
    pressed: Boolean
) {
    val accent = when {
        item.reached -> SpatialInk.oxide
        item.urgent -> SpatialInk.amber
        else -> SpatialInk.copper
    }
    val depth = if (item.reached) 10.dp else if (item.urgent) 5.dp else 0.dp
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (item.reached) 82.dp else 66.dp)
            .graphicsLayer {
                translationX = depth.toPx() * .36f
                translationY = if (pressed) 2f else 0f
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val left = w * .035f
            val right = w * .965f
            val top = h * .08f
            val bottom = h * .84f
            val sideDeep = if (item.reached) w * .032f else w * .020f

            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(16f, w * .035f + depth.toPx(), h * .07f, 0x46271E17)
            }
            drawIntoCanvas {
                it.nativeCanvas.drawRoundRect(
                    RectF(left, top, right, bottom),
                    4f,
                    4f,
                    shadow
                )
            }
            val side = Path().apply {
                moveTo(right, top + h * .02f)
                lineTo(right + sideDeep, top + h * .09f)
                lineTo(right + sideDeep, bottom + h * .02f)
                lineTo(right, bottom)
                close()
            }
            drawPath(side, color = Color(0xFF75695C))
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(
                        if (selected) Color(0xFFF0E8DD) else Color(0xFFDED4C7),
                        Color(0xFFB8AB9A),
                        Color(0xFF948878)
                    ),
                    start = Offset(left, top),
                    end = Offset(right, bottom)
                ),
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
            drawLine(
                color = Color.White.copy(alpha = .42f),
                start = Offset(left + 2f, top + 1.5f),
                end = Offset(right - 2f, top + 1.5f),
                strokeWidth = 1.2f
            )
            drawLine(
                color = Color(0xFF3D352D).copy(alpha = if (selected) .30f else .16f),
                start = Offset(left + 2f, bottom - 1.5f),
                end = Offset(right, bottom - 1.5f),
                strokeWidth = 1.2f
            )
            drawRect(
                color = accent.copy(alpha = if (selected) .88f else .58f),
                topLeft = Offset(left + w * .035f, top + h * .20f),
                size = Size(w * .009f, h * .47f)
            )
            val trackLeft = w * .42f
            val trackRight = w * .86f
            val trackY = bottom - h * .17f
            drawRect(
                color = Color(0xFF3E372F).copy(alpha = .26f),
                topLeft = Offset(trackLeft, trackY),
                size = Size(trackRight - trackLeft, 4f)
            )
            drawRect(
                color = accent,
                topLeft = Offset(trackLeft, trackY),
                size = Size((trackRight - trackLeft) * item.ratio, 4f)
            )
        }
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 24.dp, end = 27.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BoundaryGlyph(item.name, 36.dp, Phase2ArtDirection.SPATIAL, accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    color = SpatialInk.ink,
                    fontSize = 13.sp,
                    fontWeight = if (item.reached) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${minutesText(item.usedMinutes)} / ${minutesText(item.limitMinutes)}",
                    color = SpatialInk.muted,
                    fontSize = 9.sp
                )
            }
            Text(
                if (item.reached) "OUT" else if (item.urgent) "NEAR" else "OK",
                color = accent,
                fontSize = 8.sp,
                letterSpacing = .8.sp
            )
            Spacer(Modifier.width(8.dp))
            BoundaryArrow(accent)
        }
    }
}

@Composable
private fun FluidBoundaryRow(
    item: BoundaryItem,
    index: Int,
    selected: Boolean,
    pressed: Boolean
) {
    val accent = when {
        item.reached -> FluidInk.danger
        item.urgent -> FluidInk.warning
        else -> FluidInk.mineral
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (item.reached) 80.dp else 66.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val body = Path().apply {
                moveTo(w * .015f, h * .25f)
                cubicTo(w * .18f, h * .03f, w * .78f, h * .07f, w * .97f, h * .17f)
                cubicTo(w * 1.03f, h * .50f, w * .92f, h * .86f, w * .70f, h * .86f)
                cubicTo(w * .38f, h * .98f, w * .11f, h * .82f, w * .02f, h * .62f)
                close()
            }
            drawPath(
                body,
                brush = Brush.linearGradient(
                    listOf(
                        if (selected) Color(0xFFFBF2E5).copy(alpha = .99f) else Color(0xFFF7EDDF).copy(alpha = .95f),
                        Color(0xFFE8DDCD).copy(alpha = .96f),
                        Color(0xFFD8CCBA).copy(alpha = .94f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                )
            )
            drawPath(
                body,
                color = Color.White.copy(alpha = if (pressed) .44f else .26f),
                style = Stroke(width = 1.2f)
            )
            drawOval(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = .30f), Color.Transparent),
                    center = Offset(w * .20f, h * .24f),
                    radius = w * .30f
                ),
                topLeft = Offset(w * .02f, h * .10f),
                size = Size(w * .48f, h * .38f)
            )
            val vein = Path().apply {
                moveTo(w * .06f, h * .72f)
                cubicTo(w * .28f, h * .48f, w * .58f, h * .88f, w * .94f, h * .58f)
            }
            drawPath(
                vein,
                color = accent.copy(alpha = if (selected) .74f else .44f),
                style = Stroke(width = if (selected) 2.2f else 1.4f, cap = StrokeCap.Round)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = .60f), Color.Transparent),
                    center = Offset(w * .91f, h * .58f),
                    radius = w * .055f
                ),
                center = Offset(w * .91f, h * .58f),
                radius = w * .055f
            )
        }
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 23.dp, end = 25.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BoundaryGlyph(item.name, 36.dp, Phase2ArtDirection.FLUID_GRADIENT, accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    color = FluidInk.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${minutesText(item.usedMinutes)} / ${minutesText(item.limitMinutes)}",
                    color = FluidInk.muted,
                    fontSize = 9.sp
                )
            }
            Text(
                when {
                    item.reached -> "到达"
                    item.urgent -> "接近"
                    else -> "正常"
                },
                color = accent,
                fontSize = 9.sp
            )
            Spacer(Modifier.width(8.dp))
            BoundaryArrow(accent)
        }
    }
}

@Composable
private fun LegacyBoundaryRow(item: BoundaryItem, selected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) Phase2Colors.ice.copy(alpha = .08f) else Color.White.copy(alpha = .03f),
                RoundedCornerShape(16.dp)
            )
            .border(
                1.dp,
                if (selected) Phase2Colors.ice.copy(alpha = .30f) else Color.White.copy(alpha = .06f),
                RoundedCornerShape(16.dp)
            )
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BoundaryGlyph(item.name, 34.dp, Phase2ArtDirection.LEGACY, Phase2Colors.ice)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Phase2Colors.ink, fontSize = 13.sp)
            Text(
                "${minutesText(item.usedMinutes)} / ${minutesText(item.limitMinutes)}",
                color = Phase2Colors.inkMuted,
                fontSize = 9.sp
            )
        }
        Text(
            if (item.reached) "到达" else if (item.urgent) "接近" else "正常",
            color = if (item.reached) Phase2Colors.red else Phase2Colors.inkMuted,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun DailyAdjustmentCue(
    direction: Phase2ArtDirection,
    item: BoundaryItem,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(
                when (direction) {
                    Phase2ArtDirection.LIQUID_GLASS -> Color.White.copy(alpha = .54f)
                    Phase2ArtDirection.SPATIAL -> Color(0xFFE1D7C9)
                    Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.cream.copy(alpha = .78f)
                    Phase2ArtDirection.LEGACY -> Phase2Colors.surface
                },
                RoundedCornerShape(
                    if (direction == Phase2ArtDirection.SPATIAL) 3.dp else 14.dp
                )
            )
            .border(
                1.dp,
                accentFor(direction).copy(alpha = .30f),
                RoundedCornerShape(if (direction == Phase2ArtDirection.SPATIAL) 3.dp else 14.dp)
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text("边界", color = mutedFor(direction), fontSize = 7.sp, letterSpacing = .8.sp)
        Text(
            "${minutesText(item.limitMinutes)}",
            color = accentFor(direction),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BoundaryGlyph(
    name: String,
    sizeDp: androidx.compose.ui.unit.Dp,
    direction: Phase2ArtDirection,
    accent: Color
) {
    val shape = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> RoundedCornerShape(sizeDp * .34f)
        Phase2ArtDirection.SPATIAL -> RoundedCornerShape(3.dp)
        Phase2ArtDirection.FLUID_GRADIENT -> RoundedCornerShape(sizeDp * .44f, sizeDp * .20f, sizeDp * .40f, sizeDp * .24f)
        Phase2ArtDirection.LEGACY -> RoundedCornerShape(11.dp)
    }
    Box(
        Modifier
            .size(sizeDp)
            .background(
                when (direction) {
                    Phase2ArtDirection.LIQUID_GLASS ->
                        Brush.radialGradient(listOf(Color.White.copy(alpha = .84f), accent.copy(alpha = .30f)))
                    Phase2ArtDirection.SPATIAL ->
                        Brush.linearGradient(listOf(Color(0xFFF3EBE0), Color(0xFF928575)))
                    Phase2ArtDirection.FLUID_GRADIENT ->
                        Brush.linearGradient(listOf(Color(0xFFF5C393), accent.copy(alpha = .62f)))
                    Phase2ArtDirection.LEGACY ->
                        Brush.radialGradient(listOf(Phase2Colors.surfaceHigh, Phase2Colors.background))
                },
                shape
            )
            .border(
                1.dp,
                if (direction == Phase2ArtDirection.SPATIAL) Color.White.copy(alpha = .28f)
                else Color.White.copy(alpha = .46f),
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(1),
            color = when (direction) {
                Phase2ArtDirection.LIQUID_GLASS -> LiquidInk.ink
                Phase2ArtDirection.SPATIAL -> SpatialInk.ink
                Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.ink
                Phase2ArtDirection.LEGACY -> Phase2Colors.ink
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BoundaryArrow(accent: Color) {
    Canvas(Modifier.size(20.dp)) {
        val path = Path().apply {
            moveTo(size.width * .31f, size.height * .22f)
            lineTo(size.width * .68f, size.height * .50f)
            lineTo(size.width * .31f, size.height * .78f)
        }
        drawPath(
            path,
            color = accent.copy(alpha = .82f),
            style = Stroke(width = 1.5f, cap = StrokeCap.Round)
        )
    }
}

private fun minutesText(minutes: Int): String =
    "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"

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

private fun accentFor(direction: Phase2ArtDirection): Color = when (direction) {
    Phase2ArtDirection.LIQUID_GLASS -> LiquidInk.tealDeep
    Phase2ArtDirection.SPATIAL -> SpatialInk.oxide
    Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.mineral
    Phase2ArtDirection.LEGACY -> Phase2Colors.ice
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
        this.color = android.graphics.Color.argb(
            (color.alpha * 255f).roundToInt(),
            (color.red * 255f).roundToInt(),
            (color.green * 255f).roundToInt(),
            (color.blue * 255f).roundToInt()
        )
        textSize = size
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = if (alignCenter) Paint.Align.CENTER else Paint.Align.LEFT
    }
    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
}
