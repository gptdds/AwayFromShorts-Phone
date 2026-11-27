package com.afs.feature.phase2

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.afs.style.api.LocalVisualEnvironment

/** Local design tokens for the isolated component preview. */
object Phase2Colors {
    val background = Color(0xFF060A13)
    val backgroundDeep = Color(0xFF03060C)
    val surface = Color(0xFF111A2B)
    val surfaceHigh = Color(0xFF17243A)
    val surfaceLow = Color(0xFF0A1220)
    val ink = Color(0xFFF2F7FF)
    val inkMuted = Color(0xFF96A8C4)
    val line = Color(0xFF2A3B58)
    val ice = Color(0xFFB8ECFF)
    val iceDeep = Color(0xFF3FB7E8)
    val cyan = Color(0xFF59E1FF)
    val cyanSoft = Color(0xFF8CEBFF)
    val amber = Color(0xFFFFC46B)
    val amberDeep = Color(0xFFE08B2E)
    val green = Color(0xFF7BE3B0)
    val red = Color(0xFFFF7B85)
    val shadow = Color(0xCC000000)
}

object Phase2Motion {
    const val PRESS_SCALE = 0.965f
    const val CARD_PRESS_SCALE = 0.978f
    const val SPRING_DAMPING = 0.72f
    const val SPRING_STIFFNESS = 420f
    const val CARD_TILT = 5.5f
    const val RING_TILT = 4.5f
}

data class Phase2StatusPalette(
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val dark: Boolean
)

/** Severity colours stay legible on both the paper theme and the dark universe surfaces. */
@Composable
fun phase2StatusPalette(): Phase2StatusPalette {
    val tokens = LocalVisualEnvironment.current.tokens
    return remember(tokens.dark, tokens.text, tokens.text2, tokens.accent) {
        if (tokens.dark) {
            Phase2StatusPalette(
                text = Color(tokens.text),
                textMuted = Color(tokens.text2),
                accent = Color(tokens.accent),
                success = Phase2Colors.green,
                warning = Phase2Colors.amber,
                error = Phase2Colors.red,
                info = Phase2Colors.ice,
                dark = true
            )
        } else {
            Phase2StatusPalette(
                text = Color(tokens.text),
                textMuted = Color(tokens.text2),
                accent = Color(tokens.accent),
                success = Color(0xFF176B4D),
                warning = Color(0xFF8A5700),
                error = Color(0xFFB3261E),
                info = Color(0xFF1769AA),
                dark = false
            )
        }
    }
}
