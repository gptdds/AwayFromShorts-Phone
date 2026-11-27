package com.afs.style.fluidgradient

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.afs.guard.style.EffectQuality
import com.afs.style.api.*

class FluidComponents : ComponentContracts {
    override val id = "fluidgradient"
    override val navigationMotion = NavigationMotion.FLUID
    val resources = VisualResources()
    private val phase = mutableFloatStateOf(0f)
    val animationTime get() = phase.floatValue
    private val gesture = UniverseGesture()
    private var pageStarted = 0L
    override fun destinationChanged() { pageStarted = SystemClock.uptimeMillis() }
    var activeShaders = 0; private set
    override fun pointer(action: Int, x: Float, y: Float) = gesture.accept(action, x, y)
    override fun resume() = resources.resume()
    override fun pause() { gesture.clear(); resources.pause() }
    override fun release() { gesture.clear(); resources.release() }
    @Composable override fun SceneHeader(modifier: Modifier) { Spacer(modifier.height(210.dp)) }

    @Composable override fun Background(modifier: Modifier) {
        val environment = LocalVisualEnvironment.current
        val moving = environment.active && environment.quality.allowShaders() && environment.quality.allowMotion()
        rememberVisualTime(resources, moving, if (environment.quality == EffectQuality.HIGH) 33 else 66, phase)
        val image = rememberSceneImage(resources, "visual/fluid-universe/sculpture-v2.png", environment.active)
        val bitmap = image?.bitmap
        val gpu = rememberTextureMaterial(resources, bitmap, "visual/fluid-universe/volume.agsl",
            environment.active && environment.quality.allowShaders() && Build.VERSION.SDK_INT >= 33)
        DisposableEffect(gpu) {
            val lease = gpu?.let { material -> activeShaders++; resources.register { material.close(); activeShaders-- } }
            onDispose { lease?.close() }
        }
        Canvas(modifier) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            if (gpu != null) drawIntoCanvas {
                gpu.draw(it.nativeCanvas, size.width, size.height, size.width, size.height, 0f, 0f,
                    if (moving) phase.floatValue else 0f, gesture.position.x, gesture.position.y,
                    if (moving && gesture.held) 1f else 0f,
                    if (gesture.released == 0L) 10f else (SystemClock.uptimeMillis() - gesture.released) / 1000f,
                    if (environment.quality == EffectQuality.HIGH) 0f else 1f,
                    if (moving) (SystemClock.uptimeMillis() - pageStarted) / 1000f else 10f)
            } else {
                drawRect(Color(0xFF050916))
                bitmap?.let { drawImage(it.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
            }
        }
        FluidAtmosphere(modifier, phase, gesture, moving, environment.quality == EffectQuality.HIGH)
    }

    @Composable override fun Card(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
        Surface(modifier, shape = CutCornerShape(topStart = 3.dp, topEnd = 22.dp, bottomEnd = 3.dp, bottomStart = 22.dp),
            color = Color(0xEC09152C), tonalElevation = 0.dp) { Column(Modifier.padding(2.dp), content = content) }
    }
    @Composable override fun SearchField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier) {
        TextField(value, onValueChange, modifier = modifier, label = { Text(label) }, singleLine = true,
            shape = CutCornerShape(topEnd = 18.dp, bottomStart = 18.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xEF09263D), unfocusedContainerColor = Color(0xE3091A30),
                focusedIndicatorColor = Color(0xFF23DBCF), unfocusedIndicatorColor = Color.Transparent))
    }

    @Composable override fun Action(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, content: @Composable RowScope.() -> Unit) {
        val environment = LocalVisualEnvironment.current
        val interactions = remember { MutableInteractionSource() }
        val pressed by interactions.collectIsPressedAsState()
        val moving = environment.active && environment.quality.allowMotion()
        val impulse by animateFloatAsState(if (pressed) 1f else 0f,
            if (moving) spring(environment.motion.elasticDamping, environment.motion.springStiffness) else tween(0), label = "fluid-pressure")
        TextButton(onClick, modifier.graphicsLayer { scaleX = 1f + impulse * .025f; scaleY = 1f - impulse * .09f }
            .background(Color(0xE0153750), RoundedCornerShape(50)).drawWithContent {
                drawContent()
                if (impulse > 0f) drawCircle(Color(0xFF24E4CB).copy(alpha = impulse.coerceIn(0f, 1f) * .2f), size.maxDimension * impulse.coerceIn(0f, 1f), Offset(size.width * .5f, size.height * .5f))
            }, enabled = enabled, interactionSource = interactions, content = content)
    }
}
