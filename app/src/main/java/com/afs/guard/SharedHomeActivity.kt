package com.afs.guard

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.afs.feature.phase2.AppRulesScreen
import com.afs.feature.phase2.BackupScreen
import com.afs.feature.phase2.DashboardScreen
import com.afs.feature.phase2.DiagnosticAction
import com.afs.feature.phase2.DiagnosticsScreen
import com.afs.feature.phase2.SettingsScreen
import com.afs.feature.phase2.SettingsSummary
import com.afs.feature.phase2.TimelineScreen
import com.afs.feature.settings.*
import com.afs.guard.style.*
import com.afs.style.api.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Incremental Compose host in the same application, sharing the original core and style registry. */
class SharedHomeActivity : ComponentActivity() {
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        val decor = window.decorView
        if (renderingActive && decor.width > 0 && decor.height > 0) {
            componentRenderer?.pointer(if (event.pointerCount > 1) android.view.MotionEvent.ACTION_CANCEL else event.actionMasked,
                event.x / decor.width, event.y / decor.height)
        }
        return super.dispatchTouchEvent(event)
    }
    val phase2Repository by lazy { Phase2SnapshotRepository(applicationContext) }
    val repository get() = phase2Repository
    val appearanceModel: AppearanceViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppearanceViewModel(StylePreferencesBridge.get(applicationContext)) as T
        }
    }
    val homeModel: Phase2HomeViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                Phase2HomeViewModel(
                    phase2Repository,
                    RuleTimelineRepository(applicationContext)
                ) as T
        }
    }
    val diagnosticsModel: DiagnosticsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DiagnosticsViewModel(DiagnosticsRepository(applicationContext)) as T
        }
    }
    val backupModel: RuleBackupViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RuleBackupViewModel(RuleBackupRepository(applicationContext)) as T
        }
    }
    private var selectedStyle by mutableStateOf("paper")
    private var renderingActive by mutableStateOf(false)
    private var selectedQuality by mutableStateOf(EffectQuality.LOW)
    var componentRenderer: ComponentContracts? = null
        private set
    private var componentRequest: String? = null
    private val preferences by lazy { getSharedPreferences(Ui.SP, 0) }
    private val motionChanged = Runnable { if (renderingActive) applyStyle() }
    private var frameMetrics: FrameMetricsWatcher? = null
    val observedFrames get() = frameMetrics?.sampleCount() ?: 0L
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == StyleManager.KEY || key == "effect_quality") {
            selectedStyle = StyleManager.selected(this)
            if (renderingActive) applyStyle()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ComposeStyleCatalog.install()
        selectedStyle = StyleManager.selected(this)
        if (packageName == "com.afs.guard.universe" && !preferences.contains(StyleManager.KEY)) {
            StyleManager.select(this, "liquidglass")
            selectedStyle = "liquidglass"
        }
        setContent {
            val persistenceError by StylePreferencesBridge.get(this).error.collectAsStateWithLifecycle()
            val state by homeModel.state.collectAsStateWithLifecycle()
            val diagnostics by diagnosticsModel.state.collectAsStateWithLifecycle()
            val backup by backupModel.state.collectAsStateWithLifecycle()
            val appearance by appearanceModel.state.collectAsStateWithLifecycle()
            val navigation = rememberNavController()
            val palette = Ui(this)
            val components = remember(selectedStyle) { ComposeStyleCatalog.resolve(selectedStyle) }
            val destination by navigation.currentBackStackEntryAsState()
            LaunchedEffect(components, destination?.destination?.route) { components.destinationChanged() }
            DisposableEffect(components) { onDispose { components.release() } }
            SideEffect { componentRenderer = components; componentRequest = selectedStyle; if (renderingActive) applyStyle() }
            val universe = components.id == "liquidglass" || components.id == "fluidgradient"
            val baseTokens = palette.tokens()
            val environment = VisualEnvironment(
                if (universe && !baseTokens.dark) universeTokens(components.id) else baseTokens,
                palette.motionTokens(),
                selectedQuality,
                renderingActive
            )
            SideEffect {
                val bar = if (universe) android.graphics.Color.rgb(8, 12, 24) else palette.bg()
                window.statusBarColor = bar
                window.navigationBarColor = bar
            }
            val colors = if (palette.dark || universe) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors.copy(primary = if (universe) Color(0xFFB8ECFF) else Color(palette.accent()),
                background = if (universe) Color(0xFF080C18) else Color(palette.bg()),
                surface = if (universe) Color(0xFF152139) else Color(palette.surface()))) {
                CompositionLocalProvider(LocalComponents provides components, LocalVisualEnvironment provides environment) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
                    // Crossfade only decoration. Outgoing effects are paused/released immediately;
                    // their static fallback can fade out without keeping shaders or page trees alive.
                    Crossfade(components, Modifier.matchParentSize(),
                        animationSpec = tween(if (environment.active && environment.quality.allowMotion()) environment.motion.pageMs.toInt() else 0),
                        label = "style-background") { background ->
                        CompositionLocalProvider(LocalVisualEnvironment provides environment.copy(active = environment.active && background === components)) {
                            background.Background(Modifier.fillMaxSize())
                        }
                    }
                    // No style-based key or replacement NavHost: both destinations use this state owner.
                    val transitionMs = if (environment.active && environment.quality.allowMotion()) environment.motion.pageMs.toInt() else 0
                    NavHost(navigation, startDestination = "home",
                        enterTransition = {
                            when (components.navigationMotion) {
                                NavigationMotion.FLUID -> expandIn(tween(transitionMs), expandFrom = Alignment.CenterEnd, clip = true) + fadeIn(tween(transitionMs))
                                NavigationMotion.GLASS -> scaleIn(tween(transitionMs), initialScale = .90f) + slideInHorizontally(tween(transitionMs)) { it / 10 } + fadeIn(tween(transitionMs))
                                NavigationMotion.DEPTH -> scaleIn(tween(transitionMs), initialScale = .96f) + slideInHorizontally(tween(transitionMs)) { it / 24 } + fadeIn(tween(transitionMs))
                                else -> fadeIn(tween(transitionMs))
                            }
                        }, exitTransition = { fadeOut(tween(transitionMs)) },
                        popEnterTransition = { fadeIn(tween(transitionMs)) },
                        popExitTransition = {
                            fadeOut(tween(transitionMs)) + if (components.navigationMotion == NavigationMotion.DEPTH)
                                scaleOut(tween(transitionMs), targetScale = .96f) else ExitTransition.None
                        }) {
                        composable("home") {
                            DashboardScreen(
                                snapshot = state.snapshot,
                                loading = state.loading,
                                error = state.error,
                                quality = selectedQuality,
                                active = renderingActive,
                                onReload = homeModel::refresh,
                                onAppearance = { navigation.navigate("appearance") },
                                onCheckin = { open(CheckinActivity::class.java) },
                                onFreeze = { open(FreezeBoardActivity::class.java) },
                                onQuota = { open(DailyQuotaActivity::class.java) },
                                onApps = { navigation.navigate("apps") },
                                onOpenApp = { navigation.navigate("timeline/${Uri.encode(it.packageName)}") },
                                onDiagnostics = { navigation.navigate("diagnostics") },
                                onBackup = { navigation.navigate("backup") },
                                onSettings = { navigation.navigate("settings") }
                            )
                        }
                        composable("apps") {
                            val addApp = rememberLauncherForActivityResult(
                                ActivityResultContracts.StartActivityForResult()
                            ) { homeModel.refresh() }
                            AppRulesScreen(
                                snapshot = state.snapshot,
                                loading = state.loading,
                                error = state.error,
                                quality = selectedQuality,
                                active = renderingActive,
                                query = state.query,
                                blockedOnly = state.blockedOnly,
                                onQuery = homeModel::search,
                                onBlockedOnly = homeModel::filterBlocked,
                                onReload = homeModel::refresh,
                                onAddApp = {
                                    addApp.launch(Intent(this@SharedHomeActivity, AppPickerActivity::class.java))
                                },
                                onOpenTimeline = { navigation.navigate("timeline/${Uri.encode(it.packageName)}") },
                                onBack = { navigation.popBackStack() }
                            )
                        }
                        composable(
                            "timeline/{package}",
                            arguments = listOf(navArgument("package") { type = NavType.StringType })
                        ) { entry ->
                            val packageName = entry.arguments?.getString("package").orEmpty()
                            TimelineScreen(
                                app = state.snapshot?.apps?.firstOrNull { it.packageName == packageName },
                                now = state.snapshot?.capturedAt ?: System.currentTimeMillis(),
                                quality = selectedQuality,
                                active = renderingActive,
                                saving = state.saving,
                                saveError = state.saveError,
                                saveMessage = state.saveMessage,
                                onSave = { draft ->
                                    state.snapshot?.apps
                                        ?.firstOrNull { it.packageName == packageName }
                                        ?.let { homeModel.saveTimeline(it.packageName, draft) }
                                },
                                onBack = { navigation.popBackStack() }
                            )
                        }
                        composable("diagnostics") {
                            LaunchedEffect(Unit) { diagnosticsModel.refresh() }
                            DiagnosticsScreen(
                                snapshot = diagnostics.snapshot,
                                loading = diagnostics.loading,
                                error = diagnostics.error,
                                actionMessage = diagnostics.actionMessage,
                                quality = selectedQuality,
                                active = renderingActive,
                                onRefresh = diagnosticsModel::refresh,
                                onAction = ::runDiagnosticAction,
                                onBack = { navigation.popBackStack() }
                            )
                        }
                        composable("settings") {
                            val styleName = StyleRegistry.find(selectedStyle)
                                ?.displayName(this@SharedHomeActivity)
                                ?: "简约风格"
                            SettingsScreen(
                                summary = settingsSummary(styleName),
                                onAppearance = { navigation.navigate("appearance") },
                                onTheme = { open(ThemeActivity::class.java) },
                                onBackup = { navigation.navigate("backup") },
                                onDiagnostics = { navigation.navigate("diagnostics") },
                                onSelfControl = { open(ControlActivity::class.java) },
                                onData = { open(DataManagementActivity::class.java) },
                                onAdvanced = { open(SettingsActivity::class.java) },
                                onLowPower = { enabled ->
                                    EffectQualityManager.setPreference(
                                        this@SharedHomeActivity,
                                        if (enabled) "low" else "auto"
                                    )
                                    applyStyle()
                                },
                                onBack = { navigation.popBackStack() }
                            )
                        }
                        composable("backup") {
                            val exportLauncher = rememberLauncherForActivityResult(
                                ActivityResultContracts.CreateDocument("application/json")
                            ) { uri -> if (uri != null) backupModel.export(uri) }
                            val importLauncher = rememberLauncherForActivityResult(
                                ActivityResultContracts.OpenDocument()
                            ) { uri -> if (uri != null) backupModel.inspect(uri) }
                            BackupScreen(
                                busy = backup.busy,
                                message = backup.message,
                                error = backup.error,
                                preview = backup.preview,
                                onPickExport = {
                                    val stamp = SimpleDateFormat(
                                        "yyyyMMdd-HHmmss",
                                        Locale.ROOT
                                    ).format(Date())
                                    exportLauncher.launch("AwayFromShorts-rules-$stamp.json")
                                },
                                onPickImport = { importLauncher.launch(arrayOf("*/*")) },
                                onRestoreMerge = { backupModel.restore(false) },
                                onRestoreReplace = { backupModel.restore(true) },
                                onDismissPreview = backupModel::dismissPreview,
                                onBack = { navigation.popBackStack() }
                            )
                        }
                        composable("appearance") {
                            AppearanceScreen(appearance,
                                StyleRegistry.all().map { StyleOption(it.id(), it.displayName(this@SharedHomeActivity)) },
                                components.id, persistenceError, appearanceModel::dispatch) { navigation.popBackStack() }
                        }
                    }
                } }
                }
            }
        }
    }
    private fun open(activity: Class<out android.app.Activity>) {
        startActivity(Intent(this, activity))
    }
    private fun settingsSummary(styleName: String): SettingsSummary {
        val theme = when (Ui.themeName(this)) {
            "auto" -> "跟随系统"
            "dark" -> "深色"
            "light" -> "浅色"
            else -> Ui.themeName(this)
        }
        val version = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName ?: "—"}（${
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            }）"
        }.getOrDefault("—")
        return SettingsSummary(
            style = styleName,
            theme = theme,
            quality = EffectQualityManager.describe(this),
            version = version,
            lowPower = EffectQualityManager.preference(this) == "low"
        )
    }
    private fun universeTokens(style: String): com.afs.guard.style.DesignTokens =
        com.afs.guard.style.DesignTokens(
            0xFF080C18.toInt(),
            0xFF152139.toInt(),
            0xFF1D2C46.toInt(),
            0xFF314563.toInt(),
            0xFFF2F7FF.toInt(),
            0xFFA5B7D2.toInt(),
            0xFF70839E.toInt(),
            if (style == "fluidgradient") 0xFF8CEBFF.toInt() else 0xFFB8ECFF.toInt(),
            0xFF07101E.toInt(),
            0xFF2A3B58.toInt(),
            true
        )
    private fun runDiagnosticAction(action: DiagnosticAction) {
        when (action) {
            DiagnosticAction.REAPPLY_RULES -> {
                Guard.applyAsync(this)
                diagnosticsModel.showAction("已提交重新应用；稍后自动刷新诊断。")
                window.decorView.postDelayed({ diagnosticsModel.refresh() }, 1400L)
            }
            DiagnosticAction.OPEN_ACCESSIBILITY ->
                openSystem(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            DiagnosticAction.OPEN_BATTERY_OPTIMIZATION ->
                openSystem(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            DiagnosticAction.OPEN_USAGE_ACCESS ->
                openSystem(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            DiagnosticAction.OPEN_NOTIFICATIONS ->
                openSystem(
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            DiagnosticAction.OPEN_EXACT_ALARM -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    openSystem(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
                } else {
                    diagnosticsModel.showAction("当前系统版本不需要精确闹钟授权。")
                }
            }
        }
    }
    private fun openSystem(action: String, data: Uri? = null) {
        try {
            startActivity(Intent(action).apply { if (data != null) this.data = data })
        } catch (failure: RuntimeException) {
            diagnosticsModel.showAction("没有找到对应的系统设置页。")
        }
    }
    private fun applyStyle() {
        selectedQuality = EffectQualityManager.resolve(this)
        val renderer = componentRenderer
        if (renderer != null) {
            // Avoid briefly attaching an obsolete renderer while selection recomposes.
            if (componentRequest != selectedStyle) return
            StyleManager.applyExternal(window.decorView as ViewGroup, renderer.id, renderer) { renderer.pause() }
            renderer.resume()
        }
    }
    override fun onResume() {
        super.onResume(); renderingActive = true
        selectedStyle = StyleManager.selected(this)
        homeModel.refreshIfStale()
        preferences.registerOnSharedPreferenceChangeListener(changed)
        MotionPolicy.add(this, motionChanged)
        EffectQualityManager.addListener(motionChanged)
        frameMetrics = FrameMetricsWatcher(window)
        applyStyle()
    }
    override fun onPause() {
        renderingActive = false
        componentRenderer?.pause()
        frameMetrics?.close(); frameMetrics = null
        EffectQualityManager.removeListener(motionChanged)
        preferences.unregisterOnSharedPreferenceChangeListener(changed)
        MotionPolicy.remove(motionChanged)
        StyleManager.release(window.decorView as ViewGroup)
        super.onPause()
    }
    override fun onDestroy() {
        StyleManager.release(window.decorView as ViewGroup)
        super.onDestroy()
    }
}
