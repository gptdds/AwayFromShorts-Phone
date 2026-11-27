package com.afs.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.afs.core.model.*
import com.afs.core.domain.HomeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A single state owner, independent of all renderers and style preferences. */
class HomeViewModel(private val repository: HomeRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(HomeScreenState(
        query = saved["query"] ?: "", blockedOnly = saved["blockedOnly"] ?: false
    ))
    val state = mutable.asStateFlow()
    init { refresh() }
    fun dispatch(action: HomeUiAction) {
        when (action) {
            is HomeUiAction.Search -> { saved["query"] = action.text; mutable.update { it.copy(query = action.text) } }
            is HomeUiAction.FilterBlocked -> { saved["blockedOnly"] = action.enabled; mutable.update { it.copy(blockedOnly = action.enabled) } }
            HomeUiAction.Refresh -> if (!state.value.loading) refresh()
        }
    }
    private fun refresh() = viewModelScope.launch {
        mutable.update { it.copy(loading = true, error = null) }
        try {
            val snapshot = repository.load()
            mutable.update { it.copy(apps = snapshot.apps, checkedAt = snapshot.checkedAt, loading = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.update { it.copy(loading = false, error = "暂时无法读取应用状态，请刷新重试") } }
    }
}
