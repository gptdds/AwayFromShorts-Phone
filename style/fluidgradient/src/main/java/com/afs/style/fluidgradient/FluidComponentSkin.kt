package com.afs.style.fluidgradient

import androidx.compose.ui.graphics.Color
import com.afs.style.api.*

class FluidComponentSkin : ComponentSkin {
    override val palette = LabPalette("Fluid Gradient", "凝聚、流动与柔性形变",
        "visual/fluid-universe/components.agsl", "visual/fluid-universe/sculpture-v2.png",
        Color(0xFF86FFDA), .045f, .105f, 18f, .65f, .82f)
}
