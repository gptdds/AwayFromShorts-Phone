package com.afs.style.spatial3d

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Optional real-scene extension, separate from graphicsLayer cards and from downloadable modules. */
interface SpatialScene : AutoCloseable {
    val estimatedBytes: Long
    fun stop()
}
interface SpatialSceneFactory {
    fun isCompatible(): Boolean
    /** Release partially decoded assets if cancellation interrupts load before returning a scene. */
    suspend fun load(maxBytes: Long): SpatialScene
}
sealed interface SceneState {
    data object Idle : SceneState
    data object Loading : SceneState
    data object Ready : SceneState
    data object Fallback : SceneState
}

/** Main-thread owner; no factory/engine is created until a visible page explicitly requests a scene. */
class LazySceneController(private val scope: CoroutineScope, private val factory: () -> SpatialSceneFactory?,
                          private val maxBytes: Long = 32L * 1024 * 1024) : AutoCloseable {
    private val mutableState = MutableStateFlow<SceneState>(SceneState.Idle)
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var scene: SpatialScene? = null
    private var generation = 0
    fun request() {
        if (task?.isActive == true || scene != null) return
        val ticket = ++generation
        mutableState.value = SceneState.Loading
        task = scope.launch {
            var loaded: SpatialScene? = null
            try {
                val renderer = factory()
                if (renderer == null || !renderer.isCompatible()) { mutableState.value = SceneState.Fallback; return@launch }
                // Factory owns background IO/decoding; engine GPU attachment stays on its required thread.
                loaded = renderer.load(maxBytes)
                ensureActive()
                check(ticket == generation && loaded.estimatedBytes in 0..maxBytes) { "Scene exceeds its resource budget" }
                scene = loaded; loaded = null; mutableState.value = SceneState.Ready
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (ticket == generation) mutableState.value = SceneState.Fallback }
            catch (unavailable: LinkageError) { if (ticket == generation) mutableState.value = SceneState.Fallback }
            finally { loaded?.let(::dispose) }
        }
    }
    private fun dispose(value: SpatialScene) {
        runCatching { value.stop() }
        runCatching { value.close() }.onFailure { android.util.Log.w("AFSStyle", "Scene cleanup failed", it) }
    }
    /** Used when leaving a scene or its style: stop rendering before releasing its GPU handles. */
    override fun close() {
        generation++; task?.cancel(); task = null
        val old = scene; scene = null
        try { old?.let(::dispose) } finally { mutableState.value = SceneState.Idle }
    }
}
