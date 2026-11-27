package com.afs.style.spatial3d

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.afs.feature.phase2.FocusView
import com.afs.feature.phase2.Phase2FocusMode
import com.afs.feature.phase2.Phase2IndicatorShape
import com.afs.feature.phase2.Phase2VisualPlugin
import com.afs.feature.phase2.SpatialPhase2Backdrop
import com.afs.feature.phase2.SpatialPhase2Daily
import com.afs.feature.phase2.SpatialPhase2Focus
import com.afs.feature.phase2.SpatialPhase2Quota
import com.afs.guard.style.EffectQuality

class SpatialPhase2Plugin : Phase2VisualPlugin {
    override val id = "spatial3d"
    override val displayName = "空间立体"
    override val order = 1
    override val indicatorColor = Color(0xFFE7DCCB)
    override val indicatorShape = Phase2IndicatorShape.SQUARE

    @Composable
    override fun Backdrop(
        active: Boolean,
        quality: EffectQuality,
        energy: Float,
        anchor: Offset,
        modifier: Modifier
    ) = SpatialPhase2Backdrop(active, quality, energy, anchor, modifier)

    @Composable
    override fun Focus(
        focus: FocusView,
        mode: Phase2FocusMode,
        quality: EffectQuality,
        active: Boolean,
        onMode: (Phase2FocusMode) -> Unit,
        onGesture: (Float, Offset) -> Unit,
        modifier: Modifier
    ) = SpatialPhase2Focus(focus, mode, quality, active, onMode, onGesture, modifier)

    @Composable
    override fun Quota(
        quality: EffectQuality,
        active: Boolean,
        onGestureEnergy: (Float, Offset) -> Unit,
        modifier: Modifier
    ) = SpatialPhase2Quota(quality, active, modifier, onGestureEnergy)

    @Composable
    override fun Daily(
        quality: EffectQuality,
        active: Boolean,
        stress: Boolean,
        onGestureEnergy: (Float, Offset) -> Unit,
        modifier: Modifier
    ) = SpatialPhase2Daily(quality, active, modifier, emptyList(), stress, onGestureEnergy)
}
