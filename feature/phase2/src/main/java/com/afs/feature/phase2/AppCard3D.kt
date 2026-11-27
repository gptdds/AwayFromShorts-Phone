package com.afs.feature.phase2

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
fun LegacyAppCard3D(
    app: AppView,
    now: Long,
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    var lifted by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(Offset(.5f, .5f)) }
    val moving = active && quality.allowMotion()
    val scale by animateFloatAsState(
        if (pressed && moving) Phase2Motion.CARD_PRESS_SCALE else 1f,
        spring(Phase2Motion.SPRING_DAMPING, Phase2Motion.SPRING_STIFFNESS),
        label = "app-card-scale"
    )
    val lift by animateFloatAsState(
        if (lifted && moving) -5f else 0f,
        spring(.75f, 380f),
        label = "app-card-lift"
    )
    val rotX by animateFloatAsState(
        if (moving) (touched.y - .5f) * -Phase2Motion.CARD_TILT else 0f,
        spring(Phase2Motion.SPRING_DAMPING, Phase2Motion.SPRING_STIFFNESS),
        label = "app-card-rot-x"
    )
    val rotY by animateFloatAsState(
        if (moving) (touched.x - .5f) * Phase2Motion.CARD_TILT else 0f,
        spring(Phase2Motion.SPRING_DAMPING, Phase2Motion.SPRING_STIFFNESS),
        label = "app-card-rot-y"
    )
    val accent = if (app.blocked) Phase2Colors.ice else Phase2Colors.green
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
            detectTapGestures(
                onPress = {
                    lifted = true
                    tryAwaitRelease()
                    lifted = false
                },
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    expanded = !expanded
                },
                onTap = {
                    expanded = !expanded
                    onClick()
                }
            )
        }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 7.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = lift * density
                rotationX = rotX
                rotationY = rotY
                cameraDistance = 22f * density
            }
            .then(gesture)
            .semantics {
                contentDescription = "${app.label}，${app.status}，${app.allowedText}，${formatNext(app.nextChangeAt, now)}"
            }
            .testTag("phase2-app-card-legacy-${app.packageName}")
            .alpha(if (app.installed) 1f else .58f)
    ) {
        Canvas(Modifier.matchParentSize()) {
            val corner = CornerRadius(24.dp.toPx())
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                setShadowLayer(
                    if (quality == EffectQuality.HIGH) 22f else 12f,
                    0f,
                    if (lifted) 16f else 9f,
                    0xB0000000.toInt()
                )
            }
            drawIntoCanvas {
                it.nativeCanvas.drawRoundRect(
                    RectF(4f, 6f, size.width - 4f, size.height - 2f),
                    corner.x, corner.y, shadow
                )
            }
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(
                        Color(0xFF1A2A43),
                        Color(0xFF0F1928),
                        Color(0xFF0A1220)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                ),
                cornerRadius = corner
            )
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(Color.White.copy(alpha = .14f), Color.Transparent, Color.Black.copy(alpha = .24f)),
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height)
                ),
                cornerRadius = corner,
                style = Stroke(1.4f)
            )
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = .24f), Color.Transparent)
                ),
                start = Offset(size.width * .12f, 1.2f),
                end = Offset(size.width * .88f, 1.2f),
                strokeWidth = 1.2f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = .20f), Color.Transparent),
                    center = Offset(size.width * .9f, size.height * .1f),
                    radius = size.width * .5f
                ),
                radius = size.width * .5f,
                center = Offset(size.width * .9f, size.height * .1f)
            )
        }
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(54.dp)
                        .background(
                            Brush.radialGradient(listOf(Color(0xFF2A3D59), Color(0xFF0B1422))),
                            RoundedCornerShape(17.dp)
                        )
                        .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(17.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (app.icon != null) {
                        Image(
                            bitmap = app.icon,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                        )
                    } else {
                        Text(
                            app.label.take(1).uppercase(),
                            color = Phase2Colors.ink,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        app.label,
                        color = Phase2Colors.ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        app.packageName,
                        color = Phase2Colors.inkMuted.copy(alpha = .72f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier
                        .background(accent.copy(alpha = .14f), RoundedCornerShape(50))
                        .border(1.dp, accent.copy(alpha = .44f), RoundedCornerShape(50))
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                ) {
                    Text(app.status, color = accent, fontSize = 11.sp, maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoPill("放行", app.allowedText, Modifier.weight(1f))
                InfoPill("下一次", formatNext(app.nextChangeAt, now), Modifier.weight(1f))
            }
            AnimatedVisibility(
                expanded,
                enter = fadeIn() + slideInVertically { -it / 8 },
                exit = fadeOut() + slideOutVertically { -it / 10 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailLine("星期", daysText(app.daysMask))
                    DetailLine("模式", if (app.freezeMode) "时段内冻结" else "时段内放行")
                    DetailLine("规则", if (app.windows.isEmpty()) "无时段" else app.windows.joinToString("、") {
                        "${clock(it.start)}–${clock(if (it.end == 0) 1440 else it.end)}"
                    })
                    DetailLine("学习闸门", if (app.gate) "参与" else "豁免")
                    Text(
                        "长按只展开信息，不改变冻结状态。",
                        color = Phase2Colors.inkMuted.copy(alpha = .8f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.White.copy(alpha = .045f), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = Phase2Colors.inkMuted, fontSize = 10.sp)
        Text(value, color = Phase2Colors.ink, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Phase2Colors.inkMuted, fontSize = 11.sp)
        Text(value, color = Phase2Colors.ink.copy(alpha = .9f), fontSize = 11.sp)
    }
}
