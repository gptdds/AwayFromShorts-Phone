package com.afs.style.liquidglass

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.afs.guard.style.EffectQuality
import com.afs.style.api.*

class LiquidGlassComponents : ComponentContracts {
    override val id = "liquidglass"
    override val navigationMotion = NavigationMotion.GLASS
    val resources = VisualResources()
    private val phase = mutableFloatStateOf(0f)
    val animationTime get() = phase.floatValue
    private val gesture = UniverseGesture()
    private var pageStarted = 0L
    override fun destinationChanged() { pageStarted = SystemClock.uptimeMillis() }
    private val viewport = mutableStateOf(IntSize.Zero)
    private var image by mutableStateOf<SceneImage?>(null)
    var activeShaders = 0; private set
    override fun pointer(action: Int, x: Float, y: Float) = gesture.accept(action, x, y)
    override fun resume() = resources.resume()
    override fun pause() { gesture.clear(); resources.pause() }
    override fun release() { gesture.clear(); resources.release(); image = null }

    @Composable private fun material(): TextureMaterial? {
        val environment = LocalVisualEnvironment.current
        val bitmap = image?.bitmap
        val gpu = rememberTextureMaterial(resources, bitmap, "visual/liquid-universe/refraction.agsl",
            environment.active && environment.quality.allowShaders() && Build.VERSION.SDK_INT >= 33)
        DisposableEffect(gpu) {
            val lease = gpu?.let { shader -> activeShaders++; resources.register { shader.close(); activeShaders-- } }
            onDispose { lease?.close() }
        }
        return gpu
    }

    @Composable override fun SceneHeader(modifier: Modifier) { Spacer(modifier.height(210.dp)) }
    @Composable override fun Background(modifier: Modifier) {
        val environment = LocalVisualEnvironment.current
        val loaded = rememberSceneImage(resources, "visual/liquid-universe/sculpture-v2.png", environment.active)
        SideEffect { image = loaded }
        rememberVisualTime(resources, environment.active && environment.quality.allowShaders() && environment.quality.allowMotion(),
            if (environment.quality == EffectQuality.HIGH) 33 else 66, phase)
        val gpu = material()
        Canvas(modifier.onSizeChanged { viewport.value = it }) {
            if (gpu != null) drawIntoCanvas {
                gpu.draw(it.nativeCanvas, size.width, size.height, size.width, size.height, 0f, 0f,
                    phase.floatValue, gesture.position.x, gesture.position.y, 0f, 10f, 0f,
                    if (environment.active && environment.quality.allowMotion()) (SystemClock.uptimeMillis() - pageStarted) / 1000f else 10f)
            } else {
                drawRect(Color(0xFF080C18))
                loaded?.bitmap?.let { drawImage(it.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
            }
        }
    }

    @Composable private fun Lens(modifier: Modifier, kind: Float, content: @Composable BoxScope.() -> Unit) {
        val environment = LocalVisualEnvironment.current
        val gpu = material()
        var bounds by remember { mutableStateOf(Rect.Zero) }
        val finger = Offset(gesture.position.x * viewport.value.width, gesture.position.y * viewport.value.height)
        val pressed = gesture.held && bounds.contains(finger)
        val moving = environment.active && environment.quality.allowMotion()
        val pressure by animateFloatAsState(if (pressed && moving) 1f else 0f,
            if (moving) spring(environment.motion.elasticDamping, environment.motion.springStiffness) else tween(0), label = "glass-lens-depth")
        Box(modifier.onGloballyPositioned { bounds = it.boundsInRoot() }.graphicsLayer {
            scaleX = 1f + pressure * .012f; scaleY = 1f - pressure * .055f
            rotationY = if (pressed && moving) (gesture.position.x - .5f) * 3f else 0f
            shadowElevation = (if (kind < 1.5f) 12.dp else 4.dp).toPx()
            shape = RoundedCornerShape(if (kind < 1.5f) 30.dp else 50.dp)
            clip = true
        }) {
            Canvas(Modifier.matchParentSize()) {
                if (gpu != null) drawIntoCanvas {
                    gpu.draw(it.nativeCanvas, size.width, size.height, viewport.value.width.toFloat(), viewport.value.height.toFloat(),
                        bounds.left, bounds.top, phase.floatValue, gesture.position.x, gesture.position.y, pressure,
                        if (gesture.released == 0L) 10f else (SystemClock.uptimeMillis() - gesture.released) / 1000f, kind,
                        if (moving) (SystemClock.uptimeMillis() - pageStarted) / 1000f else 10f)
                } else drawRect(Color(if (kind < 1.5f) 0xE0152132 else 0xF0222D3B))
            }
            content()
        }
    }
    @Composable override fun Card(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
        Lens(modifier, 1f) { Column(content = content) }
    }
    @Composable override fun SearchField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier) {
        Lens(Modifier.fillMaxWidth(), 2f) {
            TextField(value, onValueChange, modifier = modifier, label = { Text(label) }, singleLine = true,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        }
    }
    @Composable override fun Action(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, content: @Composable RowScope.() -> Unit) {
        Lens(modifier, 2f) { TextButton(onClick, enabled = enabled, content = content) }
    }
}
