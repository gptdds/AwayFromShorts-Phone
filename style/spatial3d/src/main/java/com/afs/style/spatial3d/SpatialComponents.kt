package com.afs.style.spatial3d

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import com.afs.guard.style.EffectQuality
import com.afs.style.api.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Event-driven layers; ordinary cards never instantiate a scene engine or ambient render loop. */
class SpatialComponents : ComponentContracts {
    override val id = "spatial3d"
    override val navigationMotion = NavigationMotion.DEPTH
    val resources = VisualResources()
    override fun resume() = resources.resume()
    override fun pause() = resources.pause()
    override fun release() = resources.release()

    @Composable override fun Background(modifier: Modifier) {
        val env = LocalVisualEnvironment.current
        Canvas(modifier) {
            drawRect(Color(env.tokens.bg))
            drawRect(Brush.radialGradient(listOf(Color(env.tokens.accent).copy(alpha = .07f), Color.Transparent),
                Offset(size.width * .9f, size.height * .1f), size.maxDimension))
        }
    }

    @Composable override fun Card(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
        val env = LocalVisualEnvironment.current
        val moving = env.active && resources.resumed && env.quality.allowMotion() && env.quality != EffectQuality.LOW
        var target by remember { mutableStateOf(Offset.Zero) }
        val x = remember { Animatable(0f) }; val y = remember { Animatable(0f) }
        val epoch = resources.epoch
        LaunchedEffect(target, moving, epoch) {
            if (!moving) { x.snapTo(0f); y.snapTo(0f); return@LaunchedEffect }
            val job = currentCoroutineContext().job
            val lease = resources.register { job.cancel() }
            try {
                coroutineScope {
                    launch { x.animateTo(target.x, spring(env.motion.springDamping, env.motion.springStiffness)) }
                    launch { y.animateTo(target.y, spring(env.motion.springDamping, env.motion.springStiffness)) }
                }
            } finally { lease.close() }
        }
        val shape = RoundedCornerShape(20.dp)
        val amount = if (env.quality == EffectQuality.HIGH) 5f else 2.5f
        val gestures = if (moving) Modifier.pointerInput(Unit) {
            try {
                awaitPointerEventScope {
                    while (true) {
                        // Observe only: lists retain scroll gestures and children retain clicks.
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val pointer = event.changes.firstOrNull { it.pressed && !it.isConsumed }
                        target = if (pointer == null) Offset.Zero else Offset(
                            (pointer.position.x / size.width.coerceAtLeast(1) * 2 - 1).coerceIn(-1f, 1f),
                            (pointer.position.y / size.height.coerceAtLeast(1) * 2 - 1).coerceIn(-1f, 1f))
                    }
                }
            } finally { target = Offset.Zero }
        } else Modifier
        Box(modifier.padding(horizontal = 3.dp, vertical = 6.dp)) {
            // A second, quieter surface supplies depth without another composited scene.
            Surface(Modifier.matchParentSize().graphicsLayer { translationY = 4.dp.toPx(); scaleX = .97f },
                shape = shape, color = Color(env.tokens.surface2)) {}
            Surface(Modifier.fillMaxWidth().then(gestures).graphicsLayer {
                rotationX = if (moving) -y.value * amount else 0f
                rotationY = if (moving) x.value * amount else 0f
                cameraDistance = 18f * density
                shadowElevation = (if (env.quality == EffectQuality.HIGH) 7.dp else 2.dp).toPx()
                this.shape = shape; clip = false
            }, shape = shape, color = Color(env.tokens.surface), border = BorderStroke(1.dp, Color(env.tokens.line))) {
                Column(Modifier.graphicsLayer {
                    translationX = if (moving) x.value * 2.dp.toPx() else 0f
                    translationY = if (moving) y.value * 2.dp.toPx() else 0f
                }, content = content)
            }
        }
    }

    @Composable override fun Action(onClick: () -> Unit, modifier: Modifier, enabled: Boolean,
                                    content: @Composable RowScope.() -> Unit) {
        val env = LocalVisualEnvironment.current
        val interactions = remember { MutableInteractionSource() }
        val pressed by interactions.collectIsPressedAsState()
        // Bounded interaction feedback only; M3 handles accessibility and click semantics.
        TextButton(onClick, modifier.graphicsLayer {
            translationY = if (pressed && env.active && env.quality.allowMotion()) 1.dp.toPx() else 0f
        }, enabled = enabled, interactionSource = interactions, content = content)
    }
}
