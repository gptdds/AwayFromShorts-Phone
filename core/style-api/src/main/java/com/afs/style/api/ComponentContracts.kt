package com.afs.style.api

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.afs.guard.style.DesignTokens
import com.afs.guard.style.MotionTokens
import com.afs.guard.style.EffectQuality

/** Purely visual inputs. No repositories, page state, routes or business callbacks. */
data class VisualEnvironment(val tokens: DesignTokens, val motion: MotionTokens,
                             val quality: EffectQuality, val active: Boolean)

/** Styles wrap the same semantic content; they must not replace or own its state. */
interface ComponentContracts {
    val id: String
    val navigationMotion: NavigationMotion get() = NavigationMotion.FADE
    /** Decoration only; never wraps a navigation host, so changing it cannot dispose page state. */
    @Composable fun Background(modifier: Modifier)
    @Composable fun SceneHeader(modifier: Modifier) {}
    @Composable fun SearchField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier) {
        androidx.compose.material3.OutlinedTextField(value, onValueChange, modifier = modifier,
            label = { androidx.compose.material3.Text(label) }, singleLine = true)
    }
    @Composable fun Card(modifier: Modifier, content: @Composable ColumnScope.() -> Unit)
    @Composable fun Action(onClick: () -> Unit, modifier: Modifier, enabled: Boolean,
                           content: @Composable RowScope.() -> Unit)
    fun resume() {}
    fun pause() {}
    fun release() {}
    fun pointer(action: Int, x: Float, y: Float) {}
    fun destinationChanged() {}
}

enum class NavigationMotion { FADE, DEPTH, GLASS, FLUID }

val LocalComponents = staticCompositionLocalOf<ComponentContracts> { error("Missing component renderer") }
val LocalVisualEnvironment = staticCompositionLocalOf<VisualEnvironment> { error("Missing visual tokens") }
