package com.afs.guard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import com.afs.style.manager.VisualPreferences
import com.afs.style.manager.VisualPreferencesRepository
import com.afs.feature.settings.AppearancePreferences
import com.afs.guard.style.StyleUiAction
import com.afs.guard.style.StyleManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Application-lifetime adapter for Java pages; style preference IO never runs on the UI thread. */
class StylePreferencesBridge private constructor(context: Context) : AppearancePreferences {
    private val app = context.applicationContext
    private val mirror = app.getSharedPreferences(Ui.SP, 0)
    val repository = VisualPreferencesRepository(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private sealed interface Command {
        data class Save(val value: VisualPreferences) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val revision = AtomicLong()
    private val readyMutable = MutableStateFlow(false)
    val ready = readyMutable.asStateFlow()
    private val errorMutable = MutableStateFlow<String?>(null)
    val error = errorMutable.asStateFlow()
    private val stateMutable = MutableStateFlow(StyleManager.state(app))
    override val state = stateMutable.asStateFlow()
    override fun dispatch(action: StyleUiAction) = StyleManager.dispatch(app, action)
    @Volatile private var restoring = false
    private fun snapshot() = VisualPreferences(mirror.getString("style_id", "paper") ?: "paper",
        mirror.getString("effect_quality", "auto") ?: "auto")
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "style_id" || key == "effect_quality") stateMutable.value = StyleManager.state(app)
        if (!restoring && (key == "style_id" || key == "effect_quality")) {
            revision.incrementAndGet()
            commands.trySend(Command.Save(snapshot()))
        }
    }
    init {
        val initial = snapshot()
        mirror.registerOnSharedPreferenceChangeListener(changed)
        scope.launch {
            try {
                val persisted = repository.initialize(initial)
                withContext(Dispatchers.Main.immediate) {
                    // A user selection made during startup takes precedence over initial restoration.
                    if (revision.get() == 0L) {
                        restoring = true
                        try { mirror.edit().putString("style_id", persisted.style)
                            .putString("effect_quality", persisted.quality).apply() }
                        finally { restoring = false }
                    }
                }
            } catch (error: Exception) { errorMutable.value = "外观设置暂时无法读取，请稍后重试" }
            finally { readyMutable.value = true }
            for (command in commands) when (command) {
                is Command.Save -> try { repository.save(command.value); errorMutable.value = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { errorMutable.value = "外观设置保存失败，请重新选择后重试" }
                is Command.Barrier -> command.done.complete(Unit)
            }
        }
    }
    suspend fun awaitWrites() {
        val barrier = CompletableDeferred<Unit>()
        commands.send(Command.Barrier(barrier)); barrier.await()
    }
    companion object {
        @Volatile private var instance: StylePreferencesBridge? = null
        fun get(context: Context): StylePreferencesBridge = instance ?: synchronized(this) {
            instance ?: StylePreferencesBridge(context).also { instance = it }
        }
    }
}

/** Starts only lightweight preference plumbing before the Application; no renderer/scene is created. */
class StylePreferencesProvider : ContentProvider() {
    override fun onCreate(): Boolean { context?.let { StylePreferencesBridge.get(it) }; return true }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
