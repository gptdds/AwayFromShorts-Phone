package com.afs.guard

import com.afs.style.spatial3d.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LazySceneTest {
    private class Scene(override val estimatedBytes: Long) : SpatialScene {
        var stopped = false; var closed = false
        override fun stop() { stopped = true }
        override fun close() { check(stopped); closed = true }
    }
    @Test fun factoryIsLazyAndOversizedOrMissingScenesFallback() = runBlocking {
        var factories = 0
        val large = Scene(1025)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = LazySceneController(scope, {
            factories++; object : SpatialSceneFactory {
                override fun isCompatible() = true
                override suspend fun load(maxBytes: Long) = large
            }
        }, 1024)
        assertEquals(0, factories); assertEquals(SceneState.Idle, controller.state.value)
        controller.request()
        assertEquals(1, factories); assertEquals(SceneState.Fallback, controller.state.value)
        assertTrue(large.stopped && large.closed)
        controller.close()
        val missing = LazySceneController(scope, { null })
        missing.request(); assertEquals(SceneState.Fallback, missing.state.value)
        missing.close(); scope.cancel()
    }
    @Test fun exitReleasesReadyAndLateLoadingResources() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ready = Scene(64)
        val controller = LazySceneController(scope, { object : SpatialSceneFactory {
            override fun isCompatible() = true
            override suspend fun load(maxBytes: Long) = ready
        } })
        controller.request(); assertEquals(SceneState.Ready, controller.state.value)
        controller.close(); assertTrue(ready.closed)
        val gate = CompletableDeferred<Unit>(); val late = Scene(64)
        val loading = LazySceneController(scope, { object : SpatialSceneFactory {
            override fun isCompatible() = true
            override suspend fun load(maxBytes: Long): SpatialScene = suspendCoroutine { continuation ->
                gate.invokeOnCompletion { continuation.resume(late) }
            }
        } })
        loading.request(); assertEquals(SceneState.Loading, loading.state.value)
        loading.close(); gate.complete(Unit); yield()
        assertEquals(SceneState.Idle, loading.state.value)
        assertTrue(late.stopped && late.closed)
        scope.cancel()
    }
}
