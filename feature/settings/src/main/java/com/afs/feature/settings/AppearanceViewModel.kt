package com.afs.feature.settings

import androidx.lifecycle.ViewModel
import com.afs.guard.style.StyleScreenState
import com.afs.guard.style.StyleUiAction
import kotlinx.coroutines.flow.StateFlow

/** Preferences port: UI never knows DataStore, Context, concrete renderers or business stores. */
interface AppearancePreferences {
    val state: StateFlow<StyleScreenState>
    fun dispatch(action: StyleUiAction)
}

class AppearanceViewModel(private val preferences: AppearancePreferences) : ViewModel() {
    val state = preferences.state
    fun dispatch(action: StyleUiAction) = preferences.dispatch(action)
}
