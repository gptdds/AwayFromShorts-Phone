package com.afs.guard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DiagnosticsViewModel(
    private val repository: DiagnosticsRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(DiagnosticsUiState())
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val snapshot = repository.load()
                mutable.update {
                    it.copy(snapshot = snapshot, loading = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(
                        loading = false,
                        error = "诊断读取失败：${failure.message ?: failure.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun showAction(message: String) {
        mutable.update { it.copy(actionMessage = message) }
    }

    fun clearAction() {
        mutable.update { it.copy(actionMessage = null) }
    }
}
