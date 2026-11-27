package com.afs.guard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import com.afs.feature.componentlab.ComponentLabScreen
import com.afs.guard.style.StyleRegistry
import com.afs.style.api.*
import com.afs.style.fluidgradient.FluidComponentSkin
import com.afs.style.liquidglass.GlassComponentSkin

class ComponentLabActivity : ComponentActivity() {
    private var active by mutableStateOf(false)
    var currentEngine: ComponentLabEngine? = null; private set
    var currentStyle: String = "liquidglass"; private set
    var dialogWindow: android.view.Window? = null; private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        if (!StyleRegistry.hasCapability("liquidglass", ComponentSkin::class.java))
            StyleRegistry.registerCapability("liquidglass", ComponentSkin::class.java) { GlassComponentSkin() }
        if (!StyleRegistry.hasCapability("fluidgradient", ComponentSkin::class.java))
            StyleRegistry.registerCapability("fluidgradient", ComponentSkin::class.java) { FluidComponentSkin() }
        setContent {
            var style by rememberSaveable { mutableStateOf(intent.getStringExtra("lab_style") ?: "liquidglass") }
            var playing by rememberSaveable { mutableStateOf(!intent.getBooleanExtra("lab_static", false)) }
            val skin = remember(style) { StyleRegistry.createCapability(style, ComponentSkin::class.java) ?: GlassComponentSkin() }
            val engine = remember(skin) { ComponentLabEngine(skin.palette) }
            SideEffect { currentEngine = engine; currentStyle = style }
            MaterialTheme(colorScheme = darkColorScheme(primary = skin.palette.accent, background = Color.Black)) {
                engine.Provide(active, playing) {
                    ComponentLabScreen(style, { style = it }, playing, { playing = it }, { dialogWindow = it })
                }
            }
        }
    }
    override fun onResume() { super.onResume(); active = true }
    override fun onPause() { active = false; currentEngine?.resources?.pause(); super.onPause() }
}
