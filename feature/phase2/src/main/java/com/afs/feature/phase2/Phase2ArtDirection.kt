package com.afs.feature.phase2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.afs.style.api.LocalComponents

/**
 * Three deliberately different visual grammars. Business state stays in the existing model;
 * this enum only selects material, composition and gesture treatment.
 */
enum class Phase2ArtDirection {
    LIQUID_GLASS,
    SPATIAL,
    FLUID_GRADIENT,
    LEGACY
}

@Composable
fun rememberPhase2ArtDirection(): Phase2ArtDirection {
    val override = LocalPhase2ArtDirection.current
    if (override != null) return override
    val style = runCatching { LocalComponents.current.id }.getOrDefault("")
    return remember(style) {
        when (style) {
            "liquidglass" -> Phase2ArtDirection.LIQUID_GLASS
            "spatial3d" -> Phase2ArtDirection.SPATIAL
            "fluidgradient" -> Phase2ArtDirection.FLUID_GRADIENT
            else -> Phase2ArtDirection.LEGACY
        }
    }
}

val LocalPhase2ArtDirection = staticCompositionLocalOf<Phase2ArtDirection?> { null }

@Composable
fun Phase2VisualDirectionProvider(
    direction: Phase2ArtDirection,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalPhase2ArtDirection provides direction, content = content)
}

fun Phase2ArtDirection.allowedRatio(app: AppView): Float {
    val minutes = app.windows.sumOf { it.minutes }.coerceIn(0, 1440)
    val allowed = if (app.freezeMode) 1440 - minutes else minutes
    return (allowed / 1440f).coerceIn(0f, 1f)
}

internal object LiquidInk {
    val field = Color(0xFFE6EEF2)
    val fieldDeep = Color(0xFFC7D7DE)
    val glass = Color(0xB8FFFFFF)
    val glassDeep = Color(0x5FFFFFFF)
    val edge = Color(0xE8FFFFFF)
    val ink = Color(0xFF14242C)
    val muted = Color(0xFF5F737C)
    val teal = Color(0xFF147F8C)
    val tealDeep = Color(0xFF0B5964)
    val amber = Color(0xFFD79C43)
    val danger = Color(0xFFB84748)
    val shadow = Color(0x4011252D)
}

internal object SpatialInk {
    val field = Color(0xFFE4DDD1)
    val fieldDeep = Color(0xFFC9BEAD)
    val stone = Color(0xFFD1C6B5)
    val stoneLight = Color(0xFFF0E9DE)
    val stoneDark = Color(0xFF9F9483)
    val graphite = Color(0xFF343331)
    val ink = Color(0xFF252320)
    val muted = Color(0xFF6C665E)
    val oxide = Color(0xFFB34B35)
    val copper = Color(0xFF2C706A)
    val amber = Color(0xFFD69238)
    val shadow = Color(0x55251E18)
}

internal object FluidInk {
    val field = Color(0xFFF0E8D9)
    val fieldWarm = Color(0xFFF7F0E4)
    val cream = Color(0xFFFBF4E8)
    val mineral = Color(0xFF1D7559)
    val mineralDeep = Color(0xFF123F3A)
    val teal = Color(0xFF2D9680)
    val apricot = Color(0xFFE29E68)
    val rose = Color(0xFFC66B62)
    val ink = Color(0xFF17251F)
    val muted = Color(0xFF677267)
    val warning = Color(0xFFB2712C)
    val danger = Color(0xFFAC4D4A)
    val shadow = Color(0x3D1A342C)
}
