package com.afs.style.api

import androidx.compose.runtime.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job

/** Main-thread visual leases. Pause immediately cancels work even if Compose's frame clock stops. */
class VisualResources {
    var epoch by mutableIntStateOf(0); private set
    var resumed by mutableStateOf(false); private set
    private var released = false
    private val handles = LinkedHashSet<Lease>()
    val activeCount get() = handles.size
    inner class Lease(private val cleanup: () -> Unit) : AutoCloseable {
        private var closed = false
        override fun close() {
            if (closed) return
            closed = true; handles.remove(this); cleanup()
        }
    }
    fun register(cleanup: () -> Unit): Lease {
        check(!released) { "Renderer was released" }
        return Lease(cleanup).also { handles.add(it) }
    }
    fun resume() { check(!released); if (!resumed) { resumed = true; epoch++ } }
    fun pause() {
        if (resumed) { resumed = false; epoch++ }
        handles.toList().forEach { lease ->
            runCatching { lease.close() }.onFailure { android.util.Log.w("AFSStyle", "Visual resource cleanup failed", it) }
        }
    }
    fun release() { pause(); released = true }
}

/** Draw-only clock: consumers read State in draw/graphicsLayer, not in page composition. */
@Composable
fun rememberVisualTime(resources: VisualResources, enabled: Boolean, frameMillis: Long = 33L,
                       output: MutableFloatState? = null): State<Float> {
    val time = remember(resources, output) { output ?: mutableFloatStateOf(0f) }
    val epoch = resources.epoch
    LaunchedEffect(resources, enabled, epoch, frameMillis) {
        if (!enabled || !resources.resumed) return@LaunchedEffect
        val job = currentCoroutineContext().job
        val lease = resources.register { job.cancel() }
        try {
            val start = withFrameNanos { it }
            while (isActive) {
                withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
                delay(frameMillis)
            }
        } finally { lease.close() }
    }
    return time
}
