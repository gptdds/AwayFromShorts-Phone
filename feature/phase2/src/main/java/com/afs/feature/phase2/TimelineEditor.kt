package com.afs.feature.phase2

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.guard.style.EffectQuality
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

@Composable
fun TimelineEditor(
    app: AppView,
    now: Long,
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
    saveError: String? = null,
    saveMessage: String? = null,
    onSave: ((TimelineDraft) -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    val direction = rememberPhase2ArtDirection()
    val shellShape = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> RoundedCornerShape(34.dp)
        Phase2ArtDirection.SPATIAL -> RoundedCornerShape(8.dp)
        Phase2ArtDirection.FLUID_GRADIENT -> RoundedCornerShape(30.dp, 12.dp, 30.dp, 12.dp)
        Phase2ArtDirection.LEGACY -> RoundedCornerShape(28.dp)
    }
    val shellBrush = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS ->
            Brush.linearGradient(listOf(Color(0xE6112B3C), Color(0xD90A1927), Color(0xE60A2230)))
        Phase2ArtDirection.SPATIAL ->
            Brush.linearGradient(listOf(Color(0xFF34322E), Color(0xFF23221F), Color(0xFF1D1C1A)))
        Phase2ArtDirection.FLUID_GRADIENT ->
            Brush.linearGradient(listOf(Color(0xFF153B35), Color(0xFF102B28), Color(0xFF0C2321)))
        Phase2ArtDirection.LEGACY ->
            Brush.linearGradient(listOf(Color(0xFF101C2E), Color(0xFF08111D)))
    }
    val shellLine = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> Color.White.copy(alpha = .28f)
        Phase2ArtDirection.SPATIAL -> SpatialInk.stoneDark.copy(alpha = .58f)
        Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.teal.copy(alpha = .28f)
        Phase2ArtDirection.LEGACY -> Phase2Colors.line.copy(alpha = .8f)
    }
    val initial = remember(app.packageName, app.daysMask, app.freezeMode, app.windows) {
        TimelineDraft(app.daysMask, app.freezeMode, app.windows)
    }
    var draft by remember(app.packageName, app.daysMask, app.freezeMode, app.windows) { mutableStateOf(initial) }
    var undo by remember(app.packageName) { mutableStateOf<List<TimelineDraft>>(emptyList()) }
    var selected by remember(app.packageName) { mutableIntStateOf(0) }
    var preview by remember(app.packageName) { mutableStateOf(true) }
    val dayIndex = currentDayIndex(now)
    val overlap = draft.overlaps()
    var savePulse by remember(saveMessage) { mutableStateOf(false) }
    LaunchedEffect(saveMessage) {
        if (saveMessage != null) {
            savePulse = true
            delay(2600)
            savePulse = false
        }
    }

    fun commit(next: TimelineDraft) {
        if (next == draft) return
        undo = (undo + draft).takeLast(12)
        draft = next
    }

    fun addWindow() {
        val candidates = listOf(
            MinuteWindow(9 * 60, 10 * 60),
            MinuteWindow(12 * 60, 13 * 60),
            MinuteWindow(15 * 60, 16 * 60),
            MinuteWindow(18 * 60, 19 * 60),
            MinuteWindow(21 * 60, 22 * 60)
        )
        val covered = IntArray(1440)
        draft.windows.forEach { window -> windowSegments(window).forEach { (from, to) -> for (m in from until to) covered[m]++ } }
        val free = candidates.firstOrNull { candidate ->
            windowSegments(candidate).all { (from, to) -> (from until to).none { covered[it] > 0 } }
        } ?: MinuteWindow(12 * 60, 13 * 60)
        commit(draft.addWindow(free.start, free.end))
        selected = draft.windows.size
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(shellBrush, shellShape)
            .border(1.dp, shellLine, shellShape)
            .padding(18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(Brush.radialGradient(listOf(Color(0xFF2A3D59), Color(0xFF0B1422))), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(app.label.take(1).uppercase(), color = Phase2Colors.ink, fontSize = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("时间轴 · ${app.label}", color = Phase2Colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (app.freezeMode) "时段内冻结，时段外放行" else "时段内放行，时段外冻结",
                    color = Phase2Colors.inkMuted,
                    fontSize = 11.sp
                )
            }
            Text(
                if (draft == initial) "已同步" else "未保存草稿",
                color = if (draft == initial) Phase2Colors.green else Phase2Colors.amber,
                fontSize = 10.sp
            )
        }

        Text("星期", color = Phase2Colors.inkMuted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            (0..6).forEach { index ->
                val on = selected(draft.daysMask, index)
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(
                            if (on) Phase2Colors.ice.copy(alpha = .16f) else Color.White.copy(alpha = .04f),
                            RoundedCornerShape(13.dp)
                        )
                        .border(
                            1.dp,
                            if (on) Phase2Colors.ice.copy(alpha = .55f) else Color.White.copy(alpha = .08f),
                            RoundedCornerShape(13.dp)
                        )
                        .clickable { commit(draft.toggleDay(index)) }
                        .semantics { contentDescription = "周${"一二三四五六日"[index]} ${if (on) "已选" else "未选"}" }
                        .testTag("phase2-day-$index"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${"一二三四五六日"[index]}", color = if (on) Phase2Colors.ice else Phase2Colors.inkMuted, fontSize = 12.sp)
                }
            }
        }

        TimelineRail(
            draft = draft,
            dayIndex = dayIndex,
            now = now,
            selected = selected,
            quality = quality,
            active = active,
            onSelect = { selected = it },
            onDragBegin = { undo = (undo + draft).takeLast(12) },
            onWindowChange = { index, start, end ->
                draft = draft.withWindow(index, start, end)
                selected = index
            },
            onTick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            onDragEnd = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            direction = direction,
            modifier = Modifier.fillMaxWidth().testTag("phase2-timeline")
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("添加时段", "phase2-add-window") { addWindow() }
            SmallAction("撤销", "phase2-undo", enabled = undo.isNotEmpty()) {
                if (undo.isNotEmpty()) {
                    draft = undo.last()
                    undo = undo.dropLast(1)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            SmallAction("重置", "phase2-reset", enabled = draft != initial) { commit(initial) }
            SmallAction(if (preview) "隐藏预览" else "显示预览", "phase2-toggle-preview") { preview = !preview }
        }

        if (overlap.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Phase2Colors.amber.copy(alpha = .10f), RoundedCornerShape(14.dp))
                    .border(1.dp, Phase2Colors.amber.copy(alpha = .36f), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "时段重叠 ${overlap.size} 分钟：预览会显示合并后的覆盖范围，请调整后再保存。",
                    color = Phase2Colors.amber,
                    fontSize = 11.sp
                )
            }
        }

        Text("时段", color = Phase2Colors.inkMuted, fontSize = 10.sp)
        draft.windows.forEachIndexed { index, window ->
            WindowRow(
                index = index,
                window = window,
                selected = selected == index,
                onSelect = { selected = index },
                onAllDay = { commit(draft.withWindow(index, 0, 0)) },
                onStart = { commit(draft.withWindow(index, it, window.end)) },
                onEnd = { commit(draft.withWindow(index, window.start, it)) },
                onDelete = {
                    commit(draft.removeWindow(index))
                    selected = index.coerceAtMost((draft.windows.size - 2).coerceAtLeast(0))
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
        }
        if (draft.windows.isEmpty()) {
            Text("没有时段；在当前模式下这代表全天冻结。", color = Phase2Colors.inkMuted, fontSize = 11.sp)
        }

        if (preview) {
            PreviewPanel(draft, dayIndex, now)
        }

        if (onSave == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Phase2Colors.amber.copy(alpha = .08f), RoundedCornerShape(14.dp))
                    .border(1.dp, Phase2Colors.amber.copy(alpha = .26f), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "预览模式只修改内存草稿，不写入真实规则。",
                    color = Phase2Colors.amber.copy(alpha = .92f),
                    fontSize = 10.sp
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Phase2Colors.ice.copy(alpha = .07f), RoundedCornerShape(14.dp))
                    .border(1.dp, Phase2Colors.ice.copy(alpha = .22f), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(
                    if (overlap.isEmpty()) "保存会同步这个冻结时段的全部应用；修改只在调整时段开放。"
                    else "时段重叠，调整后才能保存。",
                    color = if (overlap.isEmpty()) Phase2Colors.inkMuted else Phase2Colors.amber,
                    fontSize = 10.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(
                    if (saving) "保存中…" else "保存规则",
                    "phase2-save",
                    enabled = !saving && draft != initial && overlap.isEmpty()
                ) {
                    onSave(draft)
                }
            }
        }
        saveMessage?.let { Text(it, color = Phase2Colors.green, fontSize = 10.sp) }
        FluidRuleSavedBadge(savePulse, active, quality)
        saveError?.let { Text(it, color = Phase2Colors.red, fontSize = 10.sp) }
    }
}

@Composable
private fun TimelineRail(
    draft: TimelineDraft,
    dayIndex: Int,
    now: Long,
    selected: Int,
    quality: EffectQuality,
    active: Boolean,
    onSelect: (Int) -> Unit,
    onDragBegin: () -> Unit,
    onWindowChange: (Int, Int, Int) -> Unit,
    onTick: () -> Unit,
    onDragEnd: () -> Unit,
    direction: Phase2ArtDirection,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var dragMinute by remember { mutableIntStateOf(0) }
    var lastTick by remember { mutableIntStateOf(-1) }
    var touching by remember { mutableStateOf(false) }
    var touchX by remember { mutableFloatStateOf(.5f) }
    val haptics = LocalHapticFeedback.current
    val currentDraft by rememberUpdatedState(draft)
    val moving = active && quality.allowMotion()
    val phase = if (moving) {
        val transition = rememberInfiniteTransition(label = "timeline-flow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (Math.PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(tween(11_000, easing = LinearEasing)),
            label = "timeline-flow-phase"
        ).value
    } else 0f
    val railHeight = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> 154.dp
        Phase2ArtDirection.SPATIAL -> 168.dp
        Phase2ArtDirection.FLUID_GRADIENT -> 160.dp
        Phase2ArtDirection.LEGACY -> 144.dp
    }
    Box(modifier.height(railHeight)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val left = 18.dp.toPx()
                            val right = size.width - 18.dp.toPx()
                            val span = (right - left).coerceAtLeast(1f)
                            var best = -1
                            var bestStart = true
                            var bestDistance = 34.dp.toPx()
                            currentDraft.windows.forEachIndexed { index, window ->
                                if (window.allDay) return@forEachIndexed
                                val sx = left + span * window.start / 1440f
                                val ex = left + span * (if (window.end == 0) 1440 else window.end) / 1440f
                                val ds = abs(offset.x - sx)
                                val de = abs(offset.x - ex)
                                if (ds < bestDistance) {
                                    bestDistance = ds; best = index; bestStart = true
                                }
                                if (de < bestDistance) {
                                    bestDistance = de; best = index; bestStart = false
                                }
                            }
                            if (best >= 0) {
                                dragging = best to bestStart
                                touching = true
                                touchX = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                                onSelect(best)
                                onDragBegin()
                                lastTick = -1
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDrag = { change, _ ->
                            val hit = dragging ?: return@detectDragGestures
                            val left = 18.dp.toPx()
                            val right = size.width - 18.dp.toPx()
                            val span = (right - left).coerceAtLeast(1f)
                            val minute = ((change.position.x - left) / span * 1440f).roundToInt().coerceIn(0, 1440)
                            val snapped = ((minute + 2) / 5) * 5
                            val value = if (snapped >= 1440) 0 else snapped
                            val window = currentDraft.windows[hit.first]
                            val start = if (hit.second) value else window.start
                            val end = if (hit.second) window.end else value
                            val safeStart = if (!hit.second && start == end) norm(end - 5) else start
                            val safeEnd = if (hit.second && start == end) norm(start + 5) else end
                            dragMinute = value
                            touching = true
                            touchX = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            onWindowChange(hit.first, safeStart, safeEnd)
                            if (value != lastTick) {
                                lastTick = value
                                if (value % 5 == 0) onTick()
                            }
                        },
                        onDragEnd = {
                            if (dragging != null) onDragEnd()
                            dragging = null
                            touching = false
                        },
                        onDragCancel = {
                            dragging = null
                            touching = false
                        }
                    )
                }
        ) {
            val left = 18.dp.toPx()
            val right = size.width - 18.dp.toPx()
            val span = (right - left).coerceAtLeast(1f)
            fun x(minute: Int): Float = left + span * ((minute % 1440 + 1440) % 1440) / 1440f
            val panelLeft = left - 10.dp.toPx()
            val panelRight = right + 10.dp.toPx()
            val panelTop = 8.dp.toPx()
            val bandTop = 40.dp.toPx()
            val bandHeight = 57.dp.toPx()
            val labelY = bandTop + bandHeight + 22.dp.toPx()
            val panelBottom = labelY + 13.dp.toPx()
            val allow = draft.ranges(dayIndex, false)
            val freeze = draft.ranges(dayIndex, true)

            when (direction) {
                Phase2ArtDirection.LIQUID_GLASS -> {
                    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.BLACK
                        setShadowLayer(22f, 0f, 9f, 0x66000000)
                    }
                    drawIntoCanvas {
                        it.nativeCanvas.drawRoundRect(
                            RectF(panelLeft, panelTop, panelRight, panelBottom),
                            24.dp.toPx(),
                            24.dp.toPx(),
                            shadow
                        )
                    }
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(
                                Color(0xC9E8F3F6),
                                Color(0x9AC8DFE7),
                                Color(0x7CA7C5CF)
                            ),
                            start = Offset(panelLeft, panelTop),
                            end = Offset(panelRight, panelBottom)
                        ),
                        topLeft = Offset(panelLeft, panelTop),
                        size = Size(panelRight - panelLeft, panelBottom - panelTop),
                        cornerRadius = CornerRadius(24.dp.toPx())
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = .60f),
                        topLeft = Offset(panelLeft, panelTop),
                        size = Size(panelRight - panelLeft, panelBottom - panelTop),
                        cornerRadius = CornerRadius(24.dp.toPx()),
                        style = Stroke(1.4.dp.toPx())
                    )
                    drawRoundRect(
                        color = LiquidInk.tealDeep.copy(alpha = .10f),
                        topLeft = Offset(left, bandTop + 3.dp.toPx()),
                        size = Size(right - left, bandHeight - 6.dp.toPx()),
                        cornerRadius = CornerRadius(bandHeight / 2f)
                    )
                    allow.forEach { range ->
                        val from = range.start
                        val to = if (range.end == 0) 1440 else range.end
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(
                                    LiquidInk.amber.copy(alpha = .34f),
                                    Color(0xFFF3D8A7).copy(alpha = .18f)
                                )
                            ),
                            topLeft = Offset(x(from), bandTop + 5.dp.toPx()),
                            size = Size((x(to) - x(from)).coerceAtLeast(2f), bandHeight - 10.dp.toPx()),
                            cornerRadius = CornerRadius(bandHeight / 2f)
                        )
                    }
                    freeze.forEach { range ->
                        val from = range.start
                        val to = if (range.end == 0) 1440 else range.end
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(
                                    LiquidInk.teal.copy(alpha = .52f),
                                    LiquidInk.tealDeep.copy(alpha = .22f)
                                )
                            ),
                            topLeft = Offset(x(from), bandTop + 5.dp.toPx()),
                            size = Size((x(to) - x(from)).coerceAtLeast(2f), bandHeight - 10.dp.toPx()),
                            cornerRadius = CornerRadius(bandHeight / 2f)
                        )
                    }
                    drawLine(
                        color = Color.White.copy(alpha = .48f),
                        start = Offset(left + 8f, bandTop + 8.dp.toPx()),
                        end = Offset(right - 8f, bandTop + 5.dp.toPx()),
                        strokeWidth = 2.2f,
                        cap = StrokeCap.Round
                    )
                }

                Phase2ArtDirection.SPATIAL -> {
                    val stage = Path().apply {
                        moveTo(panelLeft + 5.dp.toPx(), panelTop + 4.dp.toPx())
                        lineTo(panelRight - 5.dp.toPx(), panelTop + 4.dp.toPx())
                        lineTo(panelRight + 7.dp.toPx(), panelBottom + 4.dp.toPx())
                        lineTo(panelLeft - 7.dp.toPx(), panelBottom + 4.dp.toPx())
                        close()
                    }
                    drawPath(stage, color = Color(0x550E0B08))
                    val stageTop = Path().apply {
                        moveTo(panelLeft + 6.dp.toPx(), panelTop)
                        lineTo(panelRight - 6.dp.toPx(), panelTop)
                        lineTo(panelRight + 5.dp.toPx(), panelBottom)
                        lineTo(panelLeft - 5.dp.toPx(), panelBottom)
                        close()
                    }
                    drawPath(
                        stageTop,
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFF524D45), Color(0xFF37342F), Color(0xFF272522))
                        )
                    )
                    drawLine(
                        color = SpatialInk.stoneLight.copy(alpha = .32f),
                        start = Offset(panelLeft + 8.dp.toPx(), panelTop + 1.5f),
                        end = Offset(panelRight - 8.dp.toPx(), panelTop + 1.5f),
                        strokeWidth = 1.5f
                    )
                    val deckLeft = left + 2.dp.toPx()
                    val deckRight = right - 2.dp.toPx()
                    drawRoundRect(
                        color = Color(0xFF211F1C),
                        topLeft = Offset(deckLeft, bandTop + 7.dp.toPx()),
                        size = Size(deckRight - deckLeft, bandHeight - 8.dp.toPx()),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                    allow.forEach { range ->
                        val from = range.start
                        val to = if (range.end == 0) 1440 else range.end
                        drawRoundRect(
                            color = SpatialInk.amber.copy(alpha = .22f),
                            topLeft = Offset(x(from), bandTop + 10.dp.toPx()),
                            size = Size((x(to) - x(from)).coerceAtLeast(2f), bandHeight - 14.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )
                    }
                    freeze.forEach { range ->
                        val from = range.start
                        val to = if (range.end == 0) 1440 else range.end
                        drawRoundRect(
                            color = SpatialInk.copper.copy(alpha = .28f),
                            topLeft = Offset(x(from), bandTop + 10.dp.toPx()),
                            size = Size((x(to) - x(from)).coerceAtLeast(2f), bandHeight - 14.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )
                    }
                    for (index in 0..12) {
                        val px = left + span * index / 12f
                        drawLine(
                            color = SpatialInk.stoneLight.copy(alpha = if (index % 3 == 0) .18f else .07f),
                            start = Offset(px, bandTop + 10.dp.toPx()),
                            end = Offset(px, bandTop + bandHeight - 4.dp.toPx()),
                            strokeWidth = if (index % 3 == 0) 1.1f else .7f
                        )
                    }
                }

                Phase2ArtDirection.FLUID_GRADIENT -> {
                    drawRoundRect(
                        color = FluidInk.cream.copy(alpha = .08f),
                        topLeft = Offset(panelLeft, panelTop),
                        size = Size(panelRight - panelLeft, panelBottom - panelTop),
                        cornerRadius = CornerRadius(26.dp.toPx())
                    )
                    drawRoundRect(
                        color = FluidInk.teal.copy(alpha = .18f),
                        topLeft = Offset(panelLeft, panelTop),
                        size = Size(panelRight - panelLeft, panelBottom - panelTop),
                        cornerRadius = CornerRadius(26.dp.toPx()),
                        style = Stroke(1.1.dp.toPx())
                    )
                    val stream = Path().apply {
                        moveTo(left - 4.dp.toPx(), bandTop + bandHeight * .50f)
                        cubicTo(
                            left + span * .18f,
                            bandTop - 5.dp.toPx(),
                            left + span * .34f,
                            bandTop + bandHeight + 4.dp.toPx(),
                            left + span * .52f,
                            bandTop + bandHeight * .48f
                        )
                        cubicTo(
                            left + span * .72f,
                            bandTop - 8.dp.toPx(),
                            left + span * .86f,
                            bandTop + bandHeight + 2.dp.toPx(),
                            right + 4.dp.toPx(),
                            bandTop + bandHeight * .50f
                        )
                        lineTo(right + 4.dp.toPx(), bandTop + bandHeight + 8.dp.toPx())
                        lineTo(left - 4.dp.toPx(), bandTop + bandHeight + 8.dp.toPx())
                        close()
                    }
                    drawPath(
                        stream,
                        brush = Brush.verticalGradient(
                            listOf(
                                FluidInk.teal.copy(alpha = .30f),
                                FluidInk.mineralDeep.copy(alpha = .52f)
                            )
                        )
                    )
                    allow.forEach { range ->
                        val from = range.start
                        val to = if (range.end == 0) 1440 else range.end
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(FluidInk.apricot.copy(alpha = .50f), FluidInk.rose.copy(alpha = .20f))
                            ),
                            topLeft = Offset(x(from), bandTop + 10.dp.toPx()),
                            size = Size((x(to) - x(from)).coerceAtLeast(2f), bandHeight - 16.dp.toPx()),
                            cornerRadius = CornerRadius(bandHeight / 2f)
                        )
                    }
                    freeze.forEach { range ->
                        val from = range.start
                        val to = if (range.end == 0) 1440 else range.end
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(FluidInk.teal.copy(alpha = .72f), FluidInk.mineral.copy(alpha = .34f))
                            ),
                            topLeft = Offset(x(from), bandTop + 10.dp.toPx()),
                            size = Size((x(to) - x(from)).coerceAtLeast(2f), bandHeight - 16.dp.toPx()),
                            cornerRadius = CornerRadius(bandHeight / 2f)
                        )
                    }
                    if (quality.allowParticles()) {
                        for (index in 0 until 18) {
                            val seed = ((index * 41) % 83) / 83f
                            val px = left + span * ((seed + phase * .03f) % 1f)
                            val py = bandTop + bandHeight * (.28f + .45f * seed) +
                                sin((phase * .8f + index).toDouble()).toFloat() * 5.dp.toPx()
                            drawCircle(
                                color = if (index % 3 == 0) FluidInk.apricot.copy(alpha = .34f)
                                else Color.White.copy(alpha = .16f),
                                radius = .8.dp.toPx() + seed * 1.6.dp.toPx(),
                                center = Offset(px, py)
                            )
                        }
                    }
                }

                Phase2ArtDirection.LEGACY -> {
                    drawRoundRect(
                        brush = Brush.linearGradient(listOf(Color(0xFF0B1524), Color(0xFF070D17))),
                        topLeft = Offset(panelLeft, panelTop),
                        size = Size(panelRight - panelLeft, panelBottom - panelTop),
                        cornerRadius = CornerRadius(20.dp.toPx())
                    )
                    allow.forEach { range ->
                        val from = range.start
                        val to = if (range.end == 0) 1440 else range.end
                        drawRoundRect(
                            color = Phase2Colors.amber.copy(alpha = .13f),
                            topLeft = Offset(x(from), bandTop),
                            size = Size((x(to) - x(from)).coerceAtLeast(1f), bandHeight),
                            cornerRadius = CornerRadius(8.dp.toPx())
                        )
                    }
                    freeze.forEach { range ->
                        val from = range.start
                        val to = if (range.end == 0) 1440 else range.end
                        drawRoundRect(
                            color = Phase2Colors.ice.copy(alpha = .16f),
                            topLeft = Offset(x(from), bandTop),
                            size = Size((x(to) - x(from)).coerceAtLeast(1f), bandHeight),
                            cornerRadius = CornerRadius(8.dp.toPx())
                        )
                    }
                }
            }

            val labelColor = when (direction) {
                Phase2ArtDirection.LIQUID_GLASS -> android.graphics.Color.argb(190, 20, 58, 68)
                Phase2ArtDirection.SPATIAL -> android.graphics.Color.argb(185, 237, 226, 211)
                Phase2ArtDirection.FLUID_GRADIENT -> android.graphics.Color.argb(190, 241, 234, 221)
                Phase2ArtDirection.LEGACY -> android.graphics.Color.argb(150, 220, 235, 255)
            }
            for (hour in 0..24) {
                val px = left + span * hour / 24f
                val major = hour % 3 == 0
                drawLine(
                    color = when (direction) {
                        Phase2ArtDirection.LIQUID_GLASS ->
                            Color(0xFF173F49).copy(alpha = if (major) .22f else .08f)
                        else -> Color.White.copy(alpha = if (major) .13f else .045f)
                    },
                    start = Offset(px, bandTop + 7.dp.toPx()),
                    end = Offset(px, bandTop + bandHeight - 6.dp.toPx()),
                    strokeWidth = if (major) 1.05f else .65f
                )
                if (major) {
                    val text = if (hour == 24) "24" else String.format("%02d", hour)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = labelColor
                        textSize = 9.sp.toPx()
                        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    }
                    drawIntoCanvas {
                        val width = paint.measureText(text)
                        it.nativeCanvas.drawText(text, px - width / 2f, labelY, paint)
                    }
                }
            }

            val accent = when (direction) {
                Phase2ArtDirection.LIQUID_GLASS -> if (draft.freezeMode) LiquidInk.teal else LiquidInk.amber
                Phase2ArtDirection.SPATIAL -> if (draft.freezeMode) SpatialInk.copper else SpatialInk.oxide
                Phase2ArtDirection.FLUID_GRADIENT -> if (draft.freezeMode) FluidInk.teal else FluidInk.apricot
                Phase2ArtDirection.LEGACY -> if (draft.freezeMode) Phase2Colors.ice else Phase2Colors.amber
            }
            draft.windows.forEachIndexed { index, window ->
                val isSelected = index == selected
                windowSegments(window).forEach { (from, to) ->
                    val x1 = x(from)
                    val x2 = if (to >= 1440) right else x(to)
                    val width = (x2 - x1).coerceAtLeast(2f)
                    when (direction) {
                        Phase2ArtDirection.LIQUID_GLASS -> {
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        accent.copy(alpha = if (isSelected) .80f else .58f),
                                        accent.copy(alpha = if (isSelected) .44f else .28f)
                                    )
                                ),
                                topLeft = Offset(x1, bandTop + 10.dp.toPx()),
                                size = Size(width, bandHeight - 20.dp.toPx()),
                                cornerRadius = CornerRadius((bandHeight - 20.dp.toPx()) / 2f)
                            )
                            drawRoundRect(
                                color = Color.White.copy(alpha = if (isSelected) .62f else .34f),
                                topLeft = Offset(x1, bandTop + 10.dp.toPx()),
                                size = Size(width, bandHeight - 20.dp.toPx()),
                                cornerRadius = CornerRadius((bandHeight - 20.dp.toPx()) / 2f),
                                style = Stroke(1.dp.toPx())
                            )
                            drawLine(
                                color = Color.White.copy(alpha = .58f),
                                start = Offset(x1 + 7.dp.toPx(), bandTop + 16.dp.toPx()),
                                end = Offset((x2 - 7.dp.toPx()).coerceAtLeast(x1 + 8.dp.toPx()), bandTop + 14.dp.toPx()),
                                strokeWidth = 1.5f,
                                cap = StrokeCap.Round
                            )
                        }
                        Phase2ArtDirection.SPATIAL -> {
                            val blockTop = bandTop + 12.dp.toPx()
                            val blockBottom = bandTop + bandHeight - 11.dp.toPx()
                            val lift = if (isSelected) 5.dp.toPx() else 3.dp.toPx()
                            drawRoundRect(
                                color = Color(0x66000000),
                                topLeft = Offset(x1 + 2.dp.toPx(), blockBottom - 2.dp.toPx()),
                                size = Size(width, 5.dp.toPx()),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(accent.copy(alpha = .62f), accent.copy(alpha = .28f))
                                ),
                                topLeft = Offset(x1, blockTop - lift),
                                size = Size(width, blockBottom - blockTop + lift)
                            )
                            drawRect(
                                color = Color.White.copy(alpha = .22f),
                                topLeft = Offset(x1, blockTop - lift),
                                size = Size(width, 3.dp.toPx())
                            )
                            drawLine(
                                color = SpatialInk.stoneLight.copy(alpha = .24f),
                                start = Offset(x1, blockTop - lift),
                                end = Offset(x1, blockBottom),
                                strokeWidth = 1f
                            )
                        }
                        Phase2ArtDirection.FLUID_GRADIENT -> {
                            drawRoundRect(
                                brush = Brush.linearGradient(
                                    listOf(
                                        accent.copy(alpha = if (isSelected) .84f else .62f),
                                        accent.copy(alpha = if (isSelected) .40f else .24f)
                                    )
                                ),
                                topLeft = Offset(x1, bandTop + 9.dp.toPx()),
                                size = Size(width, bandHeight - 18.dp.toPx()),
                                cornerRadius = CornerRadius(
                                    min(width / 2f, (bandHeight - 18.dp.toPx()) / 2f)
                                )
                            )
                            drawRoundRect(
                                color = Color.White.copy(alpha = .20f),
                                topLeft = Offset(x1 + width * .18f, bandTop + 15.dp.toPx()),
                                size = Size(width * .36f, 3.dp.toPx()),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )
                        }
                        Phase2ArtDirection.LEGACY -> {
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        accent.copy(alpha = if (isSelected) .55f else .32f),
                                        accent.copy(alpha = if (isSelected) .24f else .14f)
                                    )
                                ),
                                topLeft = Offset(x1, bandTop + 5.dp.toPx()),
                                size = Size(width, bandHeight - 10.dp.toPx()),
                                cornerRadius = CornerRadius(9.dp.toPx())
                            )
                            drawRoundRect(
                                color = Color.White.copy(alpha = if (isSelected) .24f else .10f),
                                topLeft = Offset(x1, bandTop + 5.dp.toPx()),
                                size = Size(width, bandHeight - 10.dp.toPx()),
                                cornerRadius = CornerRadius(9.dp.toPx()),
                                style = Stroke(1.dp.toPx())
                            )
                        }
                    }
                }
                if (!window.allDay) {
                    listOf(window.start to true, (if (window.end == 0) 1440 else window.end) to false).forEach { (minute, _) ->
                        val hx = if (minute == 1440) right else x(minute)
                        when (direction) {
                            Phase2ArtDirection.LIQUID_GLASS -> {
                                val radius = if (isSelected) 8.dp.toPx() else 6.5.dp.toPx()
                                val cy = bandTop + bandHeight / 2f
                                drawCircle(
                                    color = Color.Black.copy(alpha = .28f),
                                    center = Offset(hx + 1.5f, cy + 2.5f),
                                    radius = radius
                                )
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        listOf(Color.White, Color(0xFFC4F0ED), accent),
                                        center = Offset(hx - radius * .3f, cy - radius * .35f),
                                        radius = radius
                                    ),
                                    center = Offset(hx, cy),
                                    radius = radius
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = .60f),
                                    center = Offset(hx, cy),
                                    radius = radius,
                                    style = Stroke(1.dp.toPx())
                                )
                            }
                            Phase2ArtDirection.SPATIAL -> {
                                val half = if (isSelected) 4.5.dp.toPx() else 3.6.dp.toPx()
                                val top = bandTop + 6.dp.toPx()
                                drawRoundRect(
                                    color = Color.Black.copy(alpha = .42f),
                                    topLeft = Offset(hx - half + 2.dp.toPx(), top + 3.dp.toPx()),
                                    size = Size(half * 2f, bandHeight - 12.dp.toPx()),
                                    cornerRadius = CornerRadius(2.dp.toPx())
                                )
                                drawRoundRect(
                                    brush = Brush.horizontalGradient(
                                        listOf(Color(0xFFE8E0D5), accent, Color(0xFF9E9280))
                                    ),
                                    topLeft = Offset(hx - half, top),
                                    size = Size(half * 2f, bandHeight - 12.dp.toPx()),
                                    cornerRadius = CornerRadius(2.dp.toPx())
                                )
                            }
                            Phase2ArtDirection.FLUID_GRADIENT -> {
                                val radius = if (isSelected) 7.dp.toPx() else 5.6.dp.toPx()
                                val cy = bandTop + bandHeight / 2f
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        listOf(Color.White, accent, accent.copy(alpha = .60f)),
                                        center = Offset(hx - radius * .25f, cy - radius * .3f),
                                        radius = radius
                                    ),
                                    center = Offset(hx, cy),
                                    radius = radius
                                )
                                drawCircle(
                                    color = Color.White.copy(alpha = .34f),
                                    center = Offset(hx, cy),
                                    radius = radius,
                                    style = Stroke(1.dp.toPx())
                                )
                            }
                            Phase2ArtDirection.LEGACY -> {
                                val half = (if (isSelected) 5.5.dp else 4.5.dp).toPx()
                                val handleTop = bandTop + 5.dp.toPx()
                                val handleHeight = bandHeight - 10.dp.toPx()
                                drawRoundRect(
                                    color = Color.Black.copy(alpha = .42f),
                                    topLeft = Offset(hx - half + 1.dp.toPx(), handleTop + 2.dp.toPx()),
                                    size = Size(half * 2f, handleHeight),
                                    cornerRadius = CornerRadius(half)
                                )
                                drawRoundRect(
                                    brush = Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = .98f), accent, accent.copy(alpha = .82f))
                                    ),
                                    topLeft = Offset(hx - half, handleTop),
                                    size = Size(half * 2f, handleHeight),
                                    cornerRadius = CornerRadius(half)
                                )
                            }
                        }
                    }
                }
            }

            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val minuteNow = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
            val nx = left + span * minuteNow / 1440f
            when (direction) {
                Phase2ArtDirection.LIQUID_GLASS -> {
                    drawLine(
                        color = LiquidInk.amber.copy(alpha = .72f),
                        start = Offset(nx, bandTop - 2.dp.toPx()),
                        end = Offset(nx, bandTop + bandHeight + 1.dp.toPx()),
                        strokeWidth = 1.4f
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White, LiquidInk.amber),
                            center = Offset(nx, bandTop - 3.dp.toPx()),
                            radius = 4.2.dp.toPx()
                        ),
                        radius = 3.3.dp.toPx(),
                        center = Offset(nx, bandTop - 3.dp.toPx())
                    )
                }
                Phase2ArtDirection.SPATIAL -> {
                    drawLine(
                        color = SpatialInk.amber,
                        start = Offset(nx, bandTop - 4.dp.toPx()),
                        end = Offset(nx, bandTop + bandHeight + 2.dp.toPx()),
                        strokeWidth = 1.6f
                    )
                    drawRect(
                        color = SpatialInk.amber,
                        topLeft = Offset(nx - 3.dp.toPx(), bandTop - 7.dp.toPx()),
                        size = Size(6.dp.toPx(), 6.dp.toPx())
                    )
                }
                Phase2ArtDirection.FLUID_GRADIENT -> {
                    drawLine(
                        color = FluidInk.apricot.copy(alpha = .82f),
                        start = Offset(nx, bandTop - 3.dp.toPx()),
                        end = Offset(nx, bandTop + bandHeight + 3.dp.toPx()),
                        strokeWidth = 1.5f
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White, FluidInk.apricot, FluidInk.rose),
                            center = Offset(nx - 1.dp.toPx(), bandTop - 4.dp.toPx()),
                            radius = 5.2.dp.toPx()
                        ),
                        center = Offset(nx, bandTop - 3.dp.toPx()),
                        radius = 4.2.dp.toPx()
                    )
                }
                Phase2ArtDirection.LEGACY -> {
                    drawLine(
                        color = Phase2Colors.red.copy(alpha = .8f),
                        start = Offset(nx, bandTop - 3.dp.toPx()),
                        end = Offset(nx, bandTop + bandHeight + 3.dp.toPx()),
                        strokeWidth = 1.4f
                    )
                    drawCircle(
                        color = Phase2Colors.red,
                        radius = 3.dp.toPx(),
                        center = Offset(nx, bandTop - 3.dp.toPx())
                    )
                }
            }

            if (touching || dragging != null) {
                val bubbleAccent = when (direction) {
                    Phase2ArtDirection.LIQUID_GLASS -> LiquidInk.tealDeep
                    Phase2ArtDirection.SPATIAL -> SpatialInk.oxide
                    Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.mineral
                    Phase2ArtDirection.LEGACY -> Color(0xFF1B2C45)
                }
                val text = clock(abs(dragMinute) % 1440)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = 12.sp.toPx()
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                val bubbleWidth = paint.measureText(text) + 24.dp.toPx()
                val bx = (left + span * touchX).coerceIn(left + bubbleWidth / 2f, right - bubbleWidth / 2f)
                val by = bandTop - 17.dp.toPx()
                drawRoundRect(
                    color = bubbleAccent,
                    topLeft = Offset(bx - bubbleWidth / 2f, by - 19.dp.toPx()),
                    size = Size(bubbleWidth, 27.dp.toPx()),
                    cornerRadius = CornerRadius(if (direction == Phase2ArtDirection.SPATIAL) 3.dp.toPx() else 10.dp.toPx())
                )
                drawIntoCanvas {
                    val width = paint.measureText(text)
                    it.nativeCanvas.drawText(text, bx - width / 2f, by - 1.5.dp.toPx(), paint)
                }
            }
        }
    }
}

@Composable
private fun WindowRow(
    index: Int,
    window: MinuteWindow,
    selected: Boolean,
    onSelect: () -> Unit,
    onAllDay: () -> Unit,
    onStart: (Int) -> Unit,
    onEnd: (Int) -> Unit,
    onDelete: () -> Unit
) {
    val start = window.start
    val end = window.end
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) Phase2Colors.ice.copy(alpha = .07f) else Color.White.copy(alpha = .028f),
                RoundedCornerShape(16.dp)
            )
            .border(
                1.dp,
                if (selected) Phase2Colors.ice.copy(alpha = .32f) else Color.White.copy(alpha = .06f),
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("时段 ${index + 1}", color = Phase2Colors.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            Text(
                if (window.allDay) "全天" else "${clock(start)} – ${clock(if (end == 0) 1440 else end)}",
                color = Phase2Colors.ice,
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "全天",
                color = if (window.allDay) Phase2Colors.ice else Phase2Colors.inkMuted,
                fontSize = 10.sp,
                modifier = Modifier
                    .clickable(onClick = onAllDay)
                    .padding(6.dp)
                    .testTag("phase2-window-$index-allday")
            )
            Text(
                "删除",
                color = Phase2Colors.red,
                fontSize = 10.sp,
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(6.dp)
                    .testTag("phase2-window-$index-delete")
            )
        }
        StepLine(
            label = "开始",
            value = if (window.allDay) "00:00" else clock(start),
            onMinus = { onStart(norm(start - 15)) },
            onPlus = { onStart(norm(start + 15)) },
            tag = "phase2-window-$index-start"
        )
        StepLine(
            label = "结束",
            value = if (window.allDay) "24:00" else clock(if (end == 0) 1440 else end),
            onMinus = { onEnd(norm(if (end == 0) 1440 - 15 else end - 15)) },
            onPlus = { onEnd(norm(if (end == 0) 15 else end + 15)) },
            tag = "phase2-window-$index-end"
        )
    }
}

@Composable
private fun StepLine(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Phase2Colors.inkMuted, fontSize = 10.sp, modifier = Modifier.width(34.dp))
        StepButton("−", "$label 提前 15 分钟", tag = "$tag-minus", onClick = onMinus)
        Text(value, color = Phase2Colors.ink, fontSize = 12.sp, modifier = Modifier.width(58.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("＋", "$label 延后 15 分钟", tag = "$tag-plus", onClick = onPlus)
    }
}

@Composable
private fun StepButton(text: String, description: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .background(Color.White.copy(alpha = .06f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = .10f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Phase2Colors.ink, fontSize = 14.sp)
    }
}

@Composable
private fun SmallAction(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (enabled) Phase2Colors.ice.copy(alpha = .12f) else Color.White.copy(alpha = .035f),
                RoundedCornerShape(13.dp)
            )
            .border(
                1.dp,
                if (enabled) Phase2Colors.ice.copy(alpha = .30f) else Color.White.copy(alpha = .06f),
                RoundedCornerShape(13.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp)
            .testTag(tag)
    ) {
        Text(text, color = if (enabled) Phase2Colors.ink else Phase2Colors.inkMuted.copy(alpha = .5f), fontSize = 11.sp)
    }
}

@Composable
private fun PreviewPanel(draft: TimelineDraft, dayIndex: Int, now: Long) {
    val freezeMinutes = draft.freezeMinutes(dayIndex)
    val allowMinutes = 1440 - freezeMinutes
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("保存前预览", color = Phase2Colors.ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                "冻结 ${freezeMinutes / 60}h ${freezeMinutes % 60}m · 放行 ${allowMinutes / 60}h ${allowMinutes % 60}m",
                color = Phase2Colors.inkMuted,
                fontSize = 10.sp
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .testTag("phase2-preview-band")
        ) {
            val corner = CornerRadius(10.dp.toPx())
            drawRoundRect(color = Phase2Colors.amber.copy(alpha = .18f), cornerRadius = corner)
            draft.ranges(dayIndex, true).forEach { range ->
                val from = range.start
                val to = if (range.end == 0) 1440 else range.end
                drawRoundRect(
                    color = Phase2Colors.ice.copy(alpha = .42f),
                    topLeft = Offset(size.width * from / 1440f, 0f),
                    size = Size((size.width * (to - from) / 1440f).coerceAtLeast(1f), size.height),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
            drawRoundRect(color = Color.White.copy(alpha = .12f), cornerRadius = corner, style = Stroke(1.dp.toPx()))
        }
        Text(
            "${daysText(draft.daysMask)} · " + if (draft.freezeMode) "冰蓝为冻结，暖色为放行" else "冰蓝为冻结，暖色为放行",
            color = Phase2Colors.inkMuted,
            fontSize = 10.sp
        )
    }
}

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
