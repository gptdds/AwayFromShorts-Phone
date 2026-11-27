package com.afs.style.api

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

enum class LabRole { PRIMARY, SECONDARY, CARD, FIELD, THUMB, TAG, NAVIGATION, DIALOG, TRACK, NOTICE }

data class LabPalette(
    val name: String,
    val description: String,
    val shaderPath: String,
    val texturePath: String,
    val accent: Color,
    val pressWidth: Float,
    val pressHeight: Float,
    val corner: Float,
    val damping: Float,
    val entranceScale: Float
)

interface ComponentSkin { val palette: LabPalette }

class ComponentLabEngine(val palette: LabPalette) {
    val resources = VisualResources()
    val time = mutableFloatStateOf(0f)
    var gpu by mutableStateOf<TextureMaterial?>(null)
    val shaderReady get() = gpu?.isClosed == false
    var animated by mutableStateOf(true)

    @Composable fun Provide(active: Boolean, animate: Boolean, content: @Composable () -> Unit) {
        DisposableEffect(this, active) {
            if (active) resources.resume() else resources.pause()
            onDispose { resources.pause() }
        }
        DisposableEffect(this) { onDispose { resources.release() } }
        val image = rememberSceneImage(resources, palette.texturePath, active)
        val material = rememberTextureMaterial(resources, image?.bitmap, palette.shaderPath, active && Build.VERSION.SDK_INT >= 33)
        DisposableEffect(this, material, active) {
            val lease = if (active && resources.resumed) material?.let { resources.register { it.close() } } else null
            onDispose { lease?.close() }
        }
        SideEffect { gpu = material; animated = active && animate }
        rememberVisualTime(resources, active && animate, 33, time)
        CompositionLocalProvider(LocalComponentLab provides this, content = content)
    }
}

val LocalComponentLab = staticCompositionLocalOf<ComponentLabEngine> { error("Missing component lab") }

@Composable
fun LabNode(modifier: Modifier = Modifier, role: LabRole = LabRole.SECONDARY, selected: Boolean = false,
            pulse: Int = 0,
            content: @Composable BoxScope.() -> Unit) {
    val engine = LocalComponentLab.current
    val nodeSelected = selected
    var pressed by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf(Offset(.5f, .5f)) }
    var released by remember { mutableStateOf(0L) }
    LaunchedEffect(pulse) { if (pulse > 0) { released = SystemClock.uptimeMillis(); finger = Offset(.25f + (pulse % 6) * .08f, .5f) } }
    val compression by animateFloatAsState(if (pressed) 1f else 0f,
        if (engine.animated) spring(engine.palette.damping, 380f) else tween(0), label = "component-pressure")
    val selection by animateFloatAsState(if (selected) 1f else 0f,
        if (engine.animated) spring(engine.palette.damping, 300f) else tween(0), label = "component-selection")
    val graphics = Modifier.graphicsLayer {
        scaleX = 1f + compression * engine.palette.pressWidth
        scaleY = 1f - compression * engine.palette.pressHeight
        rotationY = (finger.x - .5f) * compression * 3f
    }
    Box(modifier.then(graphics).semantics { this.selected = nodeSelected }.pointerInput(engine) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.pressed) {
                        finger = Offset((change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                        pressed = true
                    } else if (pressed) { pressed = false; released = SystemClock.uptimeMillis() }
                }
            }
        } finally { pressed = false }
    }) {
        Canvas(Modifier.matchParentSize()) {
            val material = engine.gpu
            if (material != null && !material.isClosed) drawIntoCanvas {
                material.draw(it.nativeCanvas, size.width, size.height, size.width, size.height, 0f, 0f,
                    engine.time.floatValue, finger.x, finger.y, compression,
                    if (engine.animated && released != 0L) (SystemClock.uptimeMillis() - released) / 1000f else 10f,
                    role.ordinal.toFloat(), selection)
            } else {
                val corner = androidx.compose.ui.geometry.CornerRadius(engine.palette.corner.dp.toPx())
                drawRoundRect(Brush.linearGradient(listOf(Color(0xFF33404B), Color(0xFF12191F), engine.palette.accent.copy(alpha = .25f))), cornerRadius = corner)
                drawRoundRect(Color.White.copy(alpha = .12f), cornerRadius = corner,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
        }
        content()
    }
}
