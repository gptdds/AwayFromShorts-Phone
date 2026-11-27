package com.afs.style.liquidglass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.afs.feature.phase2.FocusView
import com.afs.feature.phase2.LiquidGlassPhase2Backdrop
import com.afs.feature.phase2.LiquidGlassPhase2Daily
import com.afs.feature.phase2.LiquidGlassPhase2Focus
import com.afs.feature.phase2.LiquidGlassPhase2Quota
import com.afs.feature.phase2.Phase2FocusMode
import com.afs.feature.phase2.Phase2IndicatorShape
import com.afs.feature.phase2.Phase2VisualPlugin
import com.afs.guard.style.EffectQuality

class LiquidGlassPhase2Plugin : Phase2VisualPlugin {
    override val id = "liquidglass"
    override val displayName = "液态玻璃"
    override val order = 0
    override val indicatorColor = Color(0xFFD9F7F4)
    override val indicatorShape = Phase2IndicatorShape.SOFT_SQUARE

    @Composable
    override fun Backdrop(
        active: Boolean,
        quality: EffectQuality,
        energy: Float,
        anchor: Offset,
        modifier: Modifier
    ) = LiquidGlassPhase2Backdrop(active, quality, energy, anchor, modifier)

    @Composable
    override fun Focus(
        focus: FocusView,
        mode: Phase2FocusMode,
        quality: EffectQuality,
        active: Boolean,
        onMode: (Phase2FocusMode) -> Unit,
        onGesture: (Float, Offset) -> Unit,
        modifier: Modifier
    ) = LiquidGlassPhase2Focus(focus, mode, quality, active, onMode, onGesture, modifier)

    @Composable
    override fun Quota(
        quality: EffectQuality,
        active: Boolean,
        onGestureEnergy: (Float, Offset) -> Unit,
        modifier: Modifier
    ) = LiquidGlassPhase2Quota(quality, active, modifier, onGestureEnergy)

    @Composable
    override fun Daily(
        quality: EffectQuality,
        active: Boolean,
        stress: Boolean,
        onGestureEnergy: (Float, Offset) -> Unit,
        modifier: Modifier
    ) = LiquidGlassPhase2Daily(quality, active, modifier, emptyList(), stress, onGestureEnergy)
}
