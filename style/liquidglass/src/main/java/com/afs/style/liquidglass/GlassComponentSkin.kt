package com.afs.style.liquidglass

import androidx.compose.ui.graphics.Color
import com.afs.style.api.*

class GlassComponentSkin : ComponentSkin {
    override val palette = LabPalette("Liquid Glass", "折射、凹槽与悬浮透镜",
        "visual/liquid-universe/components.agsl", "visual/liquid-universe/sculpture-v2.png",
        Color(0xFFBEEDFF), .015f, .075f, 26f, .78f, .92f)
}
