package com.afs.feature.componentlab

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.style.api.*
import kotlin.math.abs

internal val Ink = Color(0xFFF1F6FB)
internal val Muted = Color(0xFF9CA9B8)

@Composable internal fun LabButton(label: String, modifier: Modifier = Modifier, role: LabRole = LabRole.SECONDARY,
                                  selected: Boolean = false, tag: String = label, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    LabNode(modifier.defaultMinSize(minHeight = 52.dp).testTag(tag).clickable(interactions, indication = null,
        role = Role.Button, onClick = onClick), role, selected) {
        Text(label, Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 12.dp),
            color = Ink, fontSize = 15.sp, fontWeight = if (role == LabRole.PRIMARY) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable internal fun LabInput(value: String, onChange: (String) -> Unit, hint: String, tag: String, multiline: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    var pulse by remember { mutableIntStateOf(0) }
    val engine = LocalComponentLab.current
    LabNode(Modifier.fillMaxWidth().height(if (multiline) 104.dp else 58.dp), LabRole.FIELD, focused, pulse) {
        BasicTextField(value, { pulse++; onChange(it) }, Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)
            .testTag(tag).onFocusChanged { focused = it.isFocused }, singleLine = !multiline,
            textStyle = TextStyle(color = Ink, fontSize = 16.sp), cursorBrush = SolidColor(engine.palette.accent),
            decorationBox = { inner -> Box { if (value.isBlank()) Text(hint, color = Muted, fontSize = 16.sp); inner() } })
    }
}

@Composable internal fun LabSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val engine = LocalComponentLab.current
    val target = if (checked) 1f else 0f
    val position by animateFloatAsState(target, spring(engine.palette.damping, 340f), label = "toggle-travel")
    LabNode(Modifier.width(76.dp).height(42.dp).testTag("reminder-switch").semantics {
        role = Role.Switch; toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
    }.clickable { onChange(!checked) }, LabRole.TRACK, checked) {
        LabNode(Modifier.padding(4.dp).width(34.dp).height(34.dp).graphicsLayer {
            translationX = 34.dp.toPx() * position
            scaleX = 1f + abs(position - target) * .45f
            scaleY = 1f - abs(position - target) * .12f
        }, LabRole.THUMB, checked) {}
    }
}

@Composable internal fun LabSlider(value: Float, onChange: (Float) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    val current by rememberUpdatedState(value)
    val drag = rememberDraggableState { delta -> onChange((current + delta / width).coerceIn(0f, 1f)) }
    Box(Modifier.fillMaxWidth().height(52.dp).onSizeChanged { width = it.width }.testTag("duration-slider")
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f); setProgress { onChange(it.coerceIn(0f, 1f)); true } }
        .pointerInput(width) { detectTapGestures { onChange((it.x / width).coerceIn(0f, 1f)) } }
        .draggable(drag, Orientation.Horizontal)) {
        LabNode(Modifier.fillMaxWidth().height(14.dp).align(Alignment.Center), LabRole.TRACK) {}
        LabNode(Modifier.fillMaxWidth(value.coerceAtLeast(.03f)).height(14.dp).align(Alignment.CenterStart), LabRole.PRIMARY, true) {}
        LabNode(Modifier.size(36.dp).align(Alignment.CenterStart).graphicsLayer { translationX = (width - 36.dp.toPx()) * value }, LabRole.THUMB, true) {}
    }
}

@Composable internal fun LabProgress(value: Float, loading: Boolean) {
    val engine = LocalComponentLab.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.weight(1f).height(18.dp).clipToBounds().semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }) {
            LabNode(Modifier.fillMaxSize(), LabRole.TRACK) {}
            LabNode(Modifier.fillMaxWidth(value.coerceAtLeast(.03f)).fillMaxHeight(), LabRole.PRIMARY, true) {}
        }
        Canvas(Modifier.size(30.dp).semantics { contentDescription = if (loading) "正在加载" else "加载已暂停" }) {
            val angle = if (loading) engine.time.floatValue * 100f else 0f
            drawArc(engine.palette.accent.copy(alpha = .14f), 0f, 360f, false, style = Stroke(3.dp.toPx()))
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, engine.palette.accent)), angle, 260f, false,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
