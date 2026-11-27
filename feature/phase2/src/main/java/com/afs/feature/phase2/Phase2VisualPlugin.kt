package com.afs.feature.phase2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.afs.guard.style.EffectQuality

enum class Phase2FocusMode {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETE;

    companion object {
        fun from(value: String?): Phase2FocusMode = when (value?.uppercase()) {
            "IDLE", "READY" -> IDLE
            "PAUSED", "HELD" -> PAUSED
            "COMPLETE", "DONE" -> COMPLETE
            else -> RUNNING
        }
    }
}

enum class Phase2IndicatorShape {
    SOFT_SQUARE,
    SQUARE,
    DROP
}

/**
 * Optional visual capability owned by one style module. The review stage only knows this
 * contract, so a new style can be installed without adding branches to the feature module.
 */
@Stable
interface Phase2VisualPlugin {
    val id: String
    val displayName: String
    val order: Int
    val indicatorColor: Color
    val indicatorShape: Phase2IndicatorShape

    @Composable
    fun Backdrop(
        active: Boolean,
        quality: EffectQuality,
        energy: Float,
        anchor: Offset,
        modifier: Modifier
    )

    @Composable
    fun Focus(
        focus: FocusView,
        mode: Phase2FocusMode,
        quality: EffectQuality,
        active: Boolean,
        onMode: (Phase2FocusMode) -> Unit,
        onGesture: (Float, Offset) -> Unit,
        modifier: Modifier
    )

    @Composable
    fun Quota(
        quality: EffectQuality,
        active: Boolean,
        onGestureEnergy: (Float, Offset) -> Unit,
        modifier: Modifier
    )

    @Composable
    fun Daily(
        quality: EffectQuality,
        active: Boolean,
        stress: Boolean,
        onGestureEnergy: (Float, Offset) -> Unit,
        modifier: Modifier
    )
}
