package com.afs.style.api

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import android.graphics.Bitmap

@Composable
fun rememberSceneImage(resources: VisualResources, path: String, active: Boolean): SceneImage? {
    val context = LocalContext.current.applicationContext
    val loaded by produceState<SceneImage?>(null, resources, resources.epoch, active, path) {
        value = null
        if (!active || !resources.resumed) return@produceState
        val image = withContext(Dispatchers.IO) { runCatching { SceneImage(context, path) }.getOrNull() }
            ?: return@produceState
        if (!resources.resumed) { image.close(); return@produceState }
        value = image
        val lease = resources.register { image.close() }
        try { awaitCancellation() } finally { lease.close() }
    }
    return loaded
}

@Composable
fun rememberTextureMaterial(resources: VisualResources, bitmap: Bitmap?, path: String, enabled: Boolean): TextureMaterial? {
    val context = LocalContext.current.applicationContext
    val loaded by produceState<TextureMaterial?>(null, resources, resources.epoch, bitmap, path, enabled) {
        value = null
        if (!enabled || !resources.resumed || bitmap == null) return@produceState
        val material = withContext(Dispatchers.Default) {
            runCatching { TextureMaterial(context, path, bitmap) }
                .onFailure { android.util.Log.e("AFSUniverse", "Material compile failed: $path", it) }.getOrNull()
        } ?: return@produceState
        if (!resources.resumed) { material.close(); return@produceState }
        value = material
        try { awaitCancellation() } finally { material.close() }
    }
    return loaded
}
