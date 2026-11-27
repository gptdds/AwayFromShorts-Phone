package com.afs.guard

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.afs.feature.phase2.Phase2ArtReviewStage
import com.afs.feature.phase2.Phase2PrototypeKind
import com.afs.feature.phase2.Phase2Snapshot
import com.afs.guard.style.EffectQuality
import com.afs.guard.style.EffectQualityManager

/** Isolated, non-exported preview host for the three phase-two components. */
class Phase2PreviewActivity : ComponentActivity() {
    private val repository by lazy { Phase2SnapshotRepository(applicationContext) }
    private var active by mutableStateOf(false)
    private var quality by mutableStateOf(EffectQuality.LOW)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersive()
        setContent {
            var snapshot by remember { mutableStateOf<Phase2Snapshot?>(null) }
            var loading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<String?>(null) }
            var reload by remember { mutableIntStateOf(0) }
            LaunchedEffect(reload) {
                loading = true
                error = null
                try {
                    snapshot = repository.load()
                } catch (failure: Exception) {
                    error = "读取失败：${failure.message ?: failure.javaClass.simpleName}"
                } finally {
                    loading = false
                }
            }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFB8ECFF),
                    background = Color(0xFF060A13),
                    surface = Color(0xFF111A2B)
                )
            ) {
                var pluginId by rememberSaveable {
                    mutableStateOf(intent.getStringExtra("phase2_direction") ?: "liquidglass")
                }
                val plugins = remember { ComposePhase2Catalog.all() }
                val plugin = remember(pluginId, plugins) {
                    ComposePhase2Catalog.resolve(pluginId)
                }
                val clean = remember {
                    intent.getBooleanExtra("phase2_clean", false)
                }
                val stressDaily = remember {
                    intent.getBooleanExtra("phase2_stress", false)
                }
                val initialPrototype = remember {
                    Phase2PrototypeKind.from(intent.getStringExtra("phase2_prototype"))
                }
                Box(Modifier.fillMaxSize().background(Color(0xFFE6EEF2))) {
                    Phase2ArtReviewStage(
                        snapshot = snapshot,
                        plugin = plugin,
                        quality = quality,
                        active = active,
                        initialPrototype = initialPrototype,
                        initialFocusState =
                            intent.getStringExtra("phase2_focus") ?: "RUNNING",
                        stressDaily = stressDaily,
                        clean = clean,
                        onCycleDirection = {
                            val current = plugins.indexOfFirst { it.id == plugin.id }
                            pluginId = plugins[(current + 1).mod(plugins.size)].id
                        }
                    )
                    if (loading && snapshot == null) {
                        // Snapshot loading never blocks the visual stage; the demo state remains usable.
                        LaunchedEffect(Unit) { loading = false }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        quality = EffectQualityManager.resolve(this)
        active = true
    }

    override fun onPause() {
        active = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
