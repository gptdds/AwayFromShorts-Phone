package com.afs.feature.phase2

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.guard.style.EffectQuality
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Phase2PrototypeKind {
    FOCUS,
    QUOTA,
    DAILY;

    companion object {
        fun from(value: String?): Phase2PrototypeKind = when (value?.uppercase()) {
            "QUOTA", "LIMIT", "B" -> QUOTA
            "DAILY", "OVERVIEW", "C" -> DAILY
            else -> FOCUS
        }
    }
}

/**
 * Full-screen review stage for the three required prototypes. It intentionally has no
 * product page header, tab bar or settings chrome.
 */
@Composable
fun Phase2ArtReviewStage(
    snapshot: Phase2Snapshot?,
    plugin: Phase2VisualPlugin,
    quality: EffectQuality,
    active: Boolean,
    initialPrototype: Phase2PrototypeKind = Phase2PrototypeKind.FOCUS,
    initialFocusState: String = "RUNNING",
    stressDaily: Boolean = false,
    clean: Boolean = false,
    modifier: Modifier = Modifier,
    onCycleDirection: () -> Unit = {}
) {
    var prototype by rememberSaveable { mutableStateOf(initialPrototype) }
    var anchor by remember { mutableStateOf(Offset(.52f, .44f)) }
    var energy by remember { mutableFloatStateOf(.08f) }
    var chromeVisible by remember(clean) { mutableStateOf(!clean) }
    var focusMode by remember { mutableStateOf(Phase2FocusMode.from(initialFocusState)) }
    var remainingSeconds by remember {
        mutableIntStateOf(if (focusMode == Phase2FocusMode.COMPLETE) 0 else 47 * 60 + 18)
    }

    LaunchedEffect(clean) {
        if (clean) return@LaunchedEffect
        delay(2400)
        chromeVisible = false
    }
    LaunchedEffect(focusMode) {
        while (focusMode == Phase2FocusMode.RUNNING && active) {
            delay(1000)
            if (remainingSeconds > 0) {
                remainingSeconds--
            } else {
                focusMode = Phase2FocusMode.COMPLETE
            }
        }
    }

    val focus = remember(snapshot, focusMode, remainingSeconds) {
        val managed = snapshot?.focus?.managedApps?.coerceAtLeast(23) ?: 29
        val blocked = when (focusMode) {
            Phase2FocusMode.IDLE -> 0
            Phase2FocusMode.RUNNING -> snapshot?.focus?.blockedApps?.coerceAtLeast(25) ?: 25
            Phase2FocusMode.PAUSED -> snapshot?.focus?.blockedApps?.coerceAtLeast(25) ?: 25
            Phase2FocusMode.COMPLETE -> 0
        }
        FocusView(
            remainingMs = remainingSeconds * 1000L,
            progress = if (focusMode == Phase2FocusMode.COMPLETE) 1f
            else (1f - remainingSeconds / (80 * 60f)).coerceIn(0f, 1f),
            nextChangeAt = snapshot?.focus?.nextChangeAt ?: 0L,
            lastChangeAt = snapshot?.focus?.lastChangeAt ?: 0L,
            blockedApps = blocked,
            managedApps = managed,
            status = when (focusMode) {
                Phase2FocusMode.IDLE -> "准备开始"
                Phase2FocusMode.RUNNING -> "专注中"
                Phase2FocusMode.PAUSED -> "已暂停"
                Phase2FocusMode.COMPLETE -> "已完成"
            },
            detail = if (blocked > 0) "当前边界仍然有效。" else "当前没有应用被冻结。"
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag("phase2-art-review-stage")
    ) {
        plugin.Backdrop(
            active = active,
            quality = quality,
            energy = energy,
            anchor = anchor,
            modifier = Modifier.fillMaxSize()
        )

        Crossfade(
            targetState = prototype,
            animationSpec = tween(420),
            label = "prototype-crossfade"
        ) { current ->
            when (current) {
                Phase2PrototypeKind.FOCUS -> plugin.Focus(
                    focus = focus,
                    mode = focusMode,
                    quality = quality,
                    active = active,
                    onMode = { next: Phase2FocusMode ->
                        when (next) {
                            Phase2FocusMode.COMPLETE -> remainingSeconds = 0
                            Phase2FocusMode.RUNNING -> {
                                if (remainingSeconds == 0) {
                                    remainingSeconds = 47 * 60 + 18
                                }
                            }
                            else -> Unit
                        }
                        focusMode = next
                    },
                    onGesture = { e, a -> energy = e; anchor = a },
                    modifier = Modifier.fillMaxSize()
                )
                Phase2PrototypeKind.QUOTA -> plugin.Quota(
                    quality = quality,
                    active = active,
                    modifier = Modifier.fillMaxSize(),
                    onGestureEnergy = { e, a -> energy = e; anchor = a }
                )
                Phase2PrototypeKind.DAILY -> plugin.Daily(
                    quality = quality,
                    active = active,
                    modifier = Modifier.fillMaxSize(),
                    stress = stressDaily,
                    onGestureEnergy = { e, a -> energy = e; anchor = a }
                )
            }
        }

        if (!clean) {
            ReviewChrome(
                plugin = plugin,
                prototype = prototype,
                visible = chromeVisible,
                onReveal = { chromeVisible = true },
                onPrototype = { prototype = it },
                onDirection = onCycleDirection,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun FocusPrototype(
    direction: Phase2ArtDirection,
    focus: FocusView,
    mode: Phase2FocusMode,
    quality: EffectQuality,
    active: Boolean,
    onMode: (Phase2FocusMode) -> Unit,
    onGesture: (Float, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var pressedControl by remember { mutableIntStateOf(-1) }
    LaunchedEffect(pressedControl) {
        onGesture(if (pressedControl >= 0) .35f else .08f, Offset(.5f, .48f))
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "专注",
                    color = inkFor(direction),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = if (direction == Phase2ArtDirection.SPATIAL) .8.sp else .2.sp
                )
                Text(
                    "${focus.managedApps} 个应用 · 边界有效",
                    color = mutedFor(direction),
                    fontSize = 8.sp,
                    letterSpacing = .5.sp
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                when (mode) {
                    Phase2FocusMode.IDLE -> "READY"
                    Phase2FocusMode.RUNNING -> "ACTIVE"
                    Phase2FocusMode.PAUSED -> "HELD"
                    Phase2FocusMode.COMPLETE -> "DONE"
                },
                color = focusAccent(direction, mode),
                fontSize = 8.sp,
                letterSpacing = 1.5.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        FocusRing3D(
            focus = focus,
            quality = quality,
            active = active,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FocusControl(
                direction = direction,
                kind = ControlKind.START,
                selected = mode == Phase2FocusMode.IDLE || mode == Phase2FocusMode.RUNNING,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMode(Phase2FocusMode.RUNNING)
                },
                onPress = { pressedControl = 0 },
                onRelease = { pressedControl = -1 }
            )
            Spacer(Modifier.width(19.dp))
            FocusControl(
                direction = direction,
                kind = ControlKind.PAUSE,
                selected = mode == Phase2FocusMode.PAUSED,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onMode(if (mode == Phase2FocusMode.PAUSED) Phase2FocusMode.RUNNING else Phase2FocusMode.PAUSED)
                },
                onPress = { pressedControl = 1 },
                onRelease = { pressedControl = -1 }
            )
            Spacer(Modifier.width(19.dp))
            FocusControl(
                direction = direction,
                kind = ControlKind.END,
                selected = mode == Phase2FocusMode.COMPLETE,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMode(Phase2FocusMode.COMPLETE)
                },
                onPress = { pressedControl = 2 },
                onRelease = { pressedControl = -1 }
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("边界", color = mutedFor(direction), fontSize = 7.sp, letterSpacing = 1.sp)
            Text(
                if (focus.blockedApps > 0) "${focus.blockedApps} 个冻结" else "无冻结",
                color = inkFor(direction).copy(alpha = .72f),
                fontSize = 8.sp
            )
        }
    }
}

@Composable
fun LiquidGlassPhase2Focus(
    focus: FocusView,
    mode: Phase2FocusMode,
    quality: EffectQuality,
    active: Boolean,
    onMode: (Phase2FocusMode) -> Unit,
    onGesture: (Float, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalPhase2ArtDirection provides Phase2ArtDirection.LIQUID_GLASS) {
        FocusPrototype(
            direction = Phase2ArtDirection.LIQUID_GLASS,
            focus = focus,
            mode = mode,
            quality = quality,
            active = active,
            onMode = onMode,
            onGesture = onGesture,
            modifier = modifier
        )
    }
}

@Composable
fun SpatialPhase2Focus(
    focus: FocusView,
    mode: Phase2FocusMode,
    quality: EffectQuality,
    active: Boolean,
    onMode: (Phase2FocusMode) -> Unit,
    onGesture: (Float, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalPhase2ArtDirection provides Phase2ArtDirection.SPATIAL) {
        FocusPrototype(
            direction = Phase2ArtDirection.SPATIAL,
            focus = focus,
            mode = mode,
            quality = quality,
            active = active,
            onMode = onMode,
            onGesture = onGesture,
            modifier = modifier
        )
    }
}

@Composable
fun FluidGradientPhase2Focus(
    focus: FocusView,
    mode: Phase2FocusMode,
    quality: EffectQuality,
    active: Boolean,
    onMode: (Phase2FocusMode) -> Unit,
    onGesture: (Float, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalPhase2ArtDirection provides Phase2ArtDirection.FLUID_GRADIENT) {
        FocusPrototype(
            direction = Phase2ArtDirection.FLUID_GRADIENT,
            focus = focus,
            mode = mode,
            quality = quality,
            active = active,
            onMode = onMode,
            onGesture = onGesture,
            modifier = modifier
        )
    }
}

private enum class ControlKind {
    START,
    PAUSE,
    END
}

@Composable
private fun FocusControl(
    direction: Phase2ArtDirection,
    kind: ControlKind,
    selected: Boolean,
    onClick: () -> Unit,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) onPress() else onRelease()
    }
    val sink by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(.74f, 430f),
        label = "focus-control-sink"
    )
    val shape = when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> CircleShape
        Phase2ArtDirection.SPATIAL -> RoundedCornerShape(7.dp)
        Phase2ArtDirection.FLUID_GRADIENT -> RoundedCornerShape(19.dp, 14.dp, 20.dp, 13.dp)
        Phase2ArtDirection.LEGACY -> RoundedCornerShape(50)
    }
    val accent = when (kind) {
        ControlKind.START -> when (direction) {
            Phase2ArtDirection.SPATIAL -> SpatialInk.copper
            Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.mineral
            else -> LiquidInk.tealDeep
        }
        ControlKind.PAUSE -> when (direction) {
            Phase2ArtDirection.SPATIAL -> SpatialInk.amber
            Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.warning
            else -> LiquidInk.amber
        }
        ControlKind.END -> when (direction) {
            Phase2ArtDirection.SPATIAL -> SpatialInk.oxide
            Phase2ArtDirection.FLUID_GRADIENT -> FluidInk.rose
            else -> LiquidInk.danger
        }
    }
    Box(
        Modifier
            .size(52.dp)
            .graphicsLayer {
                translationY = sink * if (direction == Phase2ArtDirection.SPATIAL) 5f else 2f
                scaleX = 1f - sink * .025f
                scaleY = 1f - sink * .055f
            }
            .background(
                when (direction) {
                    Phase2ArtDirection.LIQUID_GLASS ->
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = if (selected) .90f else .64f), accent.copy(alpha = .22f))
                        )
                    Phase2ArtDirection.SPATIAL ->
                        Brush.linearGradient(listOf(Color(0xFFF2E9DD), Color(0xFFA89A89)))
                    Phase2ArtDirection.FLUID_GRADIENT ->
                        Brush.linearGradient(listOf(FluidInk.cream.copy(alpha = .88f), accent.copy(alpha = .34f)))
                    Phase2ArtDirection.LEGACY ->
                        Brush.radialGradient(listOf(Phase2Colors.surfaceHigh, Phase2Colors.background))
                },
                shape
            )
            .border(
                1.dp,
                if (selected) accent.copy(alpha = .72f) else Color.White.copy(alpha = .32f),
                shape
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics {
                contentDescription = when (kind) {
                    ControlKind.START -> "开始专注"
                    ControlKind.PAUSE -> "暂停专注"
                    ControlKind.END -> "结束专注"
                }
            }
            .testTag(
                when (kind) {
                    ControlKind.START -> "phase2-focus-start"
                    ControlKind.PAUSE -> "phase2-focus-pause"
                    ControlKind.END -> "phase2-focus-end"
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(25.dp)) {
            when (kind) {
                ControlKind.START -> {
                    val path = Path().apply {
                        moveTo(size.width * .32f, size.height * .20f)
                        lineTo(size.width * .78f, size.height * .50f)
                        lineTo(size.width * .32f, size.height * .80f)
                        close()
                    }
                    drawPath(path, color = accent)
                }
                ControlKind.PAUSE -> {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(size.width * .27f, size.height * .22f),
                        size = Size(size.width * .15f, size.height * .56f),
                        cornerRadius = CornerRadius(1.5f)
                    )
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(size.width * .58f, size.height * .22f),
                        size = Size(size.width * .15f, size.height * .56f),
                        cornerRadius = CornerRadius(1.5f)
                    )
                }
                ControlKind.END -> {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(size.width * .27f, size.height * .27f),
                        size = Size(size.width * .46f, size.height * .46f),
                        cornerRadius = CornerRadius(2f)
                    )
                }
            }
            if (selected) {
                drawCircle(
                    color = accent.copy(alpha = .30f),
                    radius = size.minDimension * .66f,
                    center = center,
                    style = Stroke(width = 1f)
                )
            }
        }
    }
}

@Composable
private fun ReviewChrome(
    plugin: Phase2VisualPlugin,
    prototype: Phase2PrototypeKind,
    visible: Boolean,
    onReveal: () -> Unit,
    onPrototype: (Phase2PrototypeKind) -> Unit,
    onDirection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(46.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onReveal
                )
        )
        if (visible) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 15.dp)
                    .background(Color.Black.copy(alpha = .18f), RoundedCornerShape(50))
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Phase2PrototypeKind.entries.forEach { kind ->
                    val active = kind == prototype
                    Box(
                        Modifier
                            .height(5.dp)
                            .width(if (active) 18.dp else 5.dp)
                            .background(
                                if (active) plugin.indicatorColor else plugin.indicatorColor.copy(alpha = .34f),
                                RoundedCornerShape(50)
                            )
                            .clickable { onPrototype(kind) }
                            .testTag("phase2-prototype-${kind.name.lowercase()}")
                    )
                }
            }
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .background(Color.Black.copy(alpha = .12f), RoundedCornerShape(50))
                    .clickable(onClick = onDirection)
                    .padding(horizontal = 9.dp, vertical = 7.dp)
                    .testTag("phase2-cycle-direction"),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .background(
                            plugin.indicatorColor.copy(alpha = .94f),
                            when (plugin.indicatorShape) {
                                Phase2IndicatorShape.SOFT_SQUARE -> RoundedCornerShape(2.dp)
                                Phase2IndicatorShape.SQUARE -> RoundedCornerShape(0.dp)
                                Phase2IndicatorShape.DROP -> CircleShape
                            }
                        )
                )
            }
        }
    }
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

private fun focusAccent(direction: Phase2ArtDirection, mode: Phase2FocusMode): Color {
    val paused = mode == Phase2FocusMode.PAUSED
    return when (direction) {
        Phase2ArtDirection.LIQUID_GLASS -> if (paused) LiquidInk.amber else LiquidInk.tealDeep
        Phase2ArtDirection.SPATIAL -> if (paused) SpatialInk.amber else SpatialInk.copper
        Phase2ArtDirection.FLUID_GRADIENT -> if (paused) FluidInk.warning else FluidInk.mineral
        Phase2ArtDirection.LEGACY -> if (paused) Phase2Colors.amber else Phase2Colors.ice
    }
}
