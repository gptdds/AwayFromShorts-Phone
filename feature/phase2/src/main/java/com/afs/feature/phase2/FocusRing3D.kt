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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun LegacyFocusRing3D(
    focus: FocusView,
    quality: EffectQuality,
    active: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {}
) {
    var pressed by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(Offset(.5f, .5f)) }
    var detail by remember { mutableStateOf(false) }
    val moving = active && quality.allowMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && moving) Phase2Motion.PRESS_SCALE else 1f,
        animationSpec = spring(Phase2Motion.SPRING_DAMPING, Phase2Motion.SPRING_STIFFNESS),
        label = "focus-ring-scale"
    )
    val tiltX by animateFloatAsState(
        targetValue = if (pressed && moving) (touched.y - .5f) * -Phase2Motion.RING_TILT else 0f,
        animationSpec = spring(Phase2Motion.SPRING_DAMPING, Phase2Motion.SPRING_STIFFNESS),
        label = "focus-ring-tilt-x"
    )
    val tiltY by animateFloatAsState(
        targetValue = if (pressed && moving) (touched.x - .5f) * Phase2Motion.RING_TILT else 0f,
        animationSpec = spring(Phase2Motion.SPRING_DAMPING, Phase2Motion.SPRING_STIFFNESS),
        label = "focus-ring-tilt-y"
    )
    val progress by animateFloatAsState(
        targetValue = focus.progress.coerceIn(0f, 1f),
        animationSpec = spring(.82f, 160f),
        label = "focus-ring-progress"
    )
    val specular = if (quality == EffectQuality.HIGH && active) {
        val transition = rememberInfiniteTransition(label = "focus-specular")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing)),
            label = "focus-specular-angle"
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

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(gesture)
                .semantics {
                    contentDescription = "专注环，${focus.status}，剩余 ${formatDuration(focus.remainingMs)}，长按查看状态详情"
                }
                .testTag("phase2-focus-ring-legacy"),
            contentAlignment = Alignment.Center
        ) {
            ParticleField(active = active && quality.allowParticles(), quality = quality, modifier = Modifier.matchParentSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        rotationX = tiltX
                        rotationY = tiltY
                        cameraDistance = 24f * density
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val outer = min(cx, cy) * .92f
                    val ring = outer * .17f
                    val rect = RectF(cx - outer, cy - outer, cx + outer, cy + outer)
                    val innerRect = RectF(cx - outer + ring, cy - outer + ring, cx + outer - ring, cy + outer - ring)

                    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = ring
                        color = android.graphics.Color.BLACK
                        setShadowLayer(30f, 0f, 18f, 0xB0000000.toInt())
                    }
                    drawIntoCanvas { it.nativeCanvas.drawArc(rect, 0f, 360f, false, shadow) }

                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color(0xFF1A2B47), Color(0xFF080E1A)),
                            center = Offset(cx - outer * .25f, cy - outer * .35f),
                            radius = outer * 1.1f
                        ),
                        radius = outer + ring * .1f,
                        center = Offset(cx, cy)
                    )

                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color(0xFF0A1424), Color(0xFF254C71), Phase2Colors.iceDeep,
                                Phase2Colors.ice, Phase2Colors.iceDeep, Color(0xFF16334F), Color(0xFF0A1424)
                            ),
                            center = Offset(cx, cy)
                        ),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width(), rect.height()),
                        style = Stroke(ring, cap = StrokeCap.Round)
                    )

                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = .55f), Color.Transparent, Color.Black.copy(alpha = .55f)),
                            center = Offset(cx, cy)
                        ),
                        startAngle = specular - 40f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(rect.left + ring * .34f, rect.top + ring * .34f),
                        size = Size(rect.width() - ring * .68f, rect.height() - ring * .68f),
                        style = Stroke(ring * .28f, cap = StrokeCap.Round),
                        alpha = if (moving) .9f else .25f
                    )

                    drawArc(
                        color = Color.Black.copy(alpha = .45f),
                        startAngle = 30f,
                        sweepAngle = 210f,
                        useCenter = false,
                        topLeft = Offset(rect.left + ring * .64f, rect.top + ring * .64f),
                        size = Size(rect.width() - ring * 1.28f, rect.height() - ring * 1.28f),
                        style = Stroke(ring * .18f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color.White.copy(alpha = .30f),
                        startAngle = 210f,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(rect.left + ring * .1f, rect.top + ring * .1f),
                        size = Size(rect.width() - ring * .2f, rect.height() - ring * .2f),
                        style = Stroke(ring * .12f, cap = StrokeCap.Round)
                    )

                    val sweep = 360f * progress
                    if (quality.allowParticles()) {
                        drawArc(
                            color = Phase2Colors.cyan.copy(alpha = .14f),
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(rect.left - ring * .1f, rect.top - ring * .1f),
                            size = Size(rect.width() + ring * .2f, rect.height() + ring * .2f),
                            style = Stroke(ring * 1.35f, cap = StrokeCap.Round)
                        )
                    }
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(Phase2Colors.iceDeep, Phase2Colors.cyan, Color.White, Phase2Colors.ice),
                            center = Offset(cx, cy)
                        ),
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(rect.width(), rect.height()),
                        style = Stroke(ring * .62f, cap = StrokeCap.Round)
                    )

                    for (tick in 0 until 60) {
                        val angle = Math.toRadians((tick * 6 - 90).toDouble())
                        val major = tick % 5 == 0
                        val r1 = outer - ring * .62f
                        val r2 = outer - ring * (if (major) .78f else .72f)
                        drawLine(
                            color = if (major) Color.White.copy(alpha = .52f) else Color.White.copy(alpha = .16f),
                            start = Offset(cx + cos(angle).toFloat() * r1, cy + sin(angle).toFloat() * r1),
                            end = Offset(cx + cos(angle).toFloat() * r2, cy + sin(angle).toFloat() * r2),
                            strokeWidth = if (major) 2.2f else 1.1f,
                            cap = StrokeCap.Round
                        )
                    }

                    val discRect = RectF(
                        cx - innerRect.width() / 2f + ring * .18f,
                        cy - innerRect.height() / 2f + ring * .18f,
                        cx + innerRect.width() / 2f - ring * .18f,
                        cy + innerRect.height() / 2f - ring * .18f
                    )
                    val discShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.BLACK
                        setShadowLayer(18f, 0f, 8f, 0x99000000.toInt())
                    }
                    drawIntoCanvas { it.nativeCanvas.drawOval(discRect, discShadow) }
                    drawOval(
                        brush = Brush.radialGradient(
                            listOf(Color(0xFF162842), Color(0xFF09111E)),
                            center = Offset(cx - innerRect.width() * .18f, cy - innerRect.height() * .2f),
                            radius = innerRect.width() * .7f
                        ),
                        topLeft = Offset(discRect.left, discRect.top),
                        size = Size(discRect.width(), discRect.height())
                    )
                    drawArc(
                        color = Color.White.copy(alpha = .14f),
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(discRect.left, discRect.top),
                        size = Size(discRect.width(), discRect.height()),
                        style = Stroke(1.4f)
                    )
                }

                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        formatDuration(focus.remainingMs),
                        color = Phase2Colors.ink,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.sp
                    )
                    Text("距下次计划切换", color = Phase2Colors.inkMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .background(
                                if (focus.blockedApps > 0) Phase2Colors.ice.copy(alpha = .14f) else Phase2Colors.green.copy(alpha = .14f),
                                RoundedCornerShape(50)
                            )
                            .border(
                                1.dp,
                                if (focus.blockedApps > 0) Phase2Colors.ice.copy(alpha = .45f) else Phase2Colors.green.copy(alpha = .45f),
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(focus.status, color = if (focus.blockedApps > 0) Phase2Colors.ice else Phase2Colors.green, fontSize = 12.sp)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = detail,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 5 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 6 }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF13233A), Color(0xFF0A1322))),
                        RoundedCornerShape(22.dp)
                    )
                    .border(1.dp, Phase2Colors.line.copy(alpha = .7f), RoundedCornerShape(22.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("状态详情", color = Phase2Colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                DetailRow("受管应用", "${focus.managedApps} 个")
                DetailRow("实际冻结", "${focus.blockedApps} 个")
                DetailRow("最近计划变化", if (focus.lastChangeAt > 0) formatClock(focus.lastChangeAt) else "暂无记录")
                DetailRow("下一次计划变化", if (focus.nextChangeAt > 0) formatClock(focus.nextChangeAt) else "暂无计划")
                Text(focus.detail, color = Phase2Colors.inkMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Phase2Colors.inkMuted, fontSize = 12.sp)
        Text(value, color = Phase2Colors.ink, fontSize = 12.sp)
    }
}

@Composable
private fun ParticleField(active: Boolean, quality: EffectQuality, modifier: Modifier) {
    val count = when {
        quality == EffectQuality.HIGH -> 46
        quality == EffectQuality.MEDIUM -> 24
        else -> 0
    }
    if (!active || count == 0) return
    val transition = rememberInfiniteTransition(label = "focus-particles")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(22000, easing = LinearEasing)),
        label = "focus-particles-time"
    )
    Canvas(modifier.semantics { contentDescription = "" }) {
        for (i in 0 until count) {
            val seed = ((i * 7919) % 1000) / 1000f
            val speed = .18f + seed * .32f
            val x = ((seed * 3.7f + time * speed) % 1f) * size.width
            val y = (((i * 37) % 100) / 100f * size.height + sin((time * 6.28f + seed * 9f)).toFloat() * 18f)
            val radius = 1.2f + (i % 4) * .7f
            drawCircle(
                color = Phase2Colors.ice.copy(alpha = .10f + (i % 3) * .06f),
                radius = radius * 2.8f,
                center = Offset(x, y)
            )
            drawCircle(
                color = Color.White.copy(alpha = .22f + (i % 5) * .06f),
                radius = radius,
                center = Offset(x, y)
            )
        }
    }
}
