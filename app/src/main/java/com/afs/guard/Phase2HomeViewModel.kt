package com.afs.guard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.afs.core.model.HomeUiAction
import com.afs.feature.phase2.Phase2Snapshot
import com.afs.feature.phase2.TimelineDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Phase2HomeState(
    val snapshot: Phase2Snapshot? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val blockedOnly: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
    val saveMessage: String? = null
) {
    val apps get() = snapshot?.apps.orEmpty()
    val visibleApps get() = snapshot?.apps.orEmpty().filter { app ->
        (!blockedOnly || app.blocked) &&
            (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true))
    }
}

class Phase2HomeViewModel(
    private val repository: Phase2SnapshotRepository,
    private val timelineRepository: RuleTimelineRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(Phase2HomeState())
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun search(text: String) {
        mutable.update { it.copy(query = text) }
    }

    fun filterBlocked(enabled: Boolean) {
        mutable.update { it.copy(blockedOnly = enabled) }
    }

    fun dispatch(action: HomeUiAction) {
        when (action) {
            is HomeUiAction.Search -> search(action.text)
            is HomeUiAction.FilterBlocked -> filterBlocked(action.enabled)
            HomeUiAction.Refresh -> refresh()
        }
    }

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val snapshot = repository.load()
                mutable.update { it.copy(snapshot = snapshot, loading = false, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(
                        loading = false,
                        error = "读取失败：${failure.message ?: failure.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun refreshIfStale(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 30_000L) {
        val captured = state.value.snapshot?.capturedAt ?: return refresh()
        if (now - captured >= maxAgeMs) refresh()
    }

    fun saveTimeline(packageName: String, draft: TimelineDraft) {
        if (state.value.saving) return
        viewModelScope.launch {
            mutable.update { it.copy(saving = true, saveError = null, saveMessage = null) }
            try {
                val result = timelineRepository.save(packageName, draft)
                mutable.update {
                    it.copy(
                        saving = false,
                        saveMessage = "已保存 ${result.members} 个应用 · ${result.schedule}"
                    )
                }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(
                        saving = false,
                        saveError = failure.message ?: failure.javaClass.simpleName
                    )
                }
            }
        }
    }

    fun clearSaveNotice() {
        mutable.update { it.copy(saveError = null, saveMessage = null) }
    }
}
