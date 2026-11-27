package com.afs.style.fluidgradient

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.afs.feature.phase2.FluidGradientPhase2Backdrop
import com.afs.feature.phase2.FluidGradientPhase2Daily
import com.afs.feature.phase2.FluidGradientPhase2Focus
import com.afs.feature.phase2.FluidGradientPhase2Quota
import com.afs.feature.phase2.FocusView
import com.afs.feature.phase2.Phase2FocusMode
import com.afs.feature.phase2.Phase2IndicatorShape
import com.afs.feature.phase2.Phase2VisualPlugin
import com.afs.guard.style.EffectQuality

class FluidGradientPhase2Plugin : Phase2VisualPlugin {
    override val id = "fluidgradient"
    override val displayName = "流体渐变"
    override val order = 2
    override val indicatorColor = Color(0xFFF1B27F)
    override val indicatorShape = Phase2IndicatorShape.DROP

    @Composable
    override fun Backdrop(
        active: Boolean,
        quality: EffectQuality,
        energy: Float,
        anchor: Offset,
        modifier: Modifier
    ) = FluidGradientPhase2Backdrop(active, quality, energy, anchor, modifier)

    @Composable
    override fun Focus(
        focus: FocusView,
        mode: Phase2FocusMode,
        quality: EffectQuality,
        active: Boolean,
        onMode: (Phase2FocusMode) -> Unit,
        onGesture: (Float, Offset) -> Unit,
        modifier: Modifier
    ) = FluidGradientPhase2Focus(focus, mode, quality, active, onMode, onGesture, modifier)

    @Composable
    override fun Quota(
        quality: EffectQuality,
        active: Boolean,
        onGestureEnergy: (Float, Offset) -> Unit,
        modifier: Modifier
    ) = FluidGradientPhase2Quota(quality, active, modifier, onGestureEnergy)

    @Composable
    override fun Daily(
        quality: EffectQuality,
        active: Boolean,
        stress: Boolean,
        onGestureEnergy: (Float, Offset) -> Unit,
        modifier: Modifier
    ) = FluidGradientPhase2Daily(quality, active, modifier, emptyList(), stress, onGestureEnergy)
}
