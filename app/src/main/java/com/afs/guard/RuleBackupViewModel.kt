package com.afs.guard

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.afs.core.rules.RuleBackupDocument
import com.afs.feature.phase2.BackupPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RuleBackupViewModel(
    private val repository: RuleBackupRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(BackupUiState())
    val state = mutable.asStateFlow()
    private var pending: RuleBackupDocument? = null

    fun export(uri: Uri) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null, error = null) }
        viewModelScope.launch {
            try {
                val count = repository.export(uri)
                mutable.update {
                    it.copy(busy = false, message = "规则备份已导出 · $count 个应用")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(busy = false, error = "导出未完成：${failure.message ?: failure.javaClass.simpleName}")
                }
            }
        }
    }

    fun inspect(uri: Uri) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null, error = null, preview = null) }
        viewModelScope.launch {
            try {
                val document = repository.read(uri)
                pending = document
                mutable.update {
                    it.copy(
                        busy = false,
                        preview = BackupPreview(repository.preview(document))
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(busy = false, error = "导入未完成：${failure.message ?: failure.javaClass.simpleName}")
                }
            }
        }
    }

    fun restore(replace: Boolean) {
        val document = pending ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null, error = null, preview = null) }
        viewModelScope.launch {
            try {
                repository.restore(document, replace)
                pending = null
                mutable.update {
                    it.copy(
                        busy = false,
                        message = if (replace) "已按备份替换规则；当前配置已保存恢复前备份。" else "已合并备份规则；当前配置已保存恢复前备份。"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(busy = false, error = "恢复未完成：${failure.message ?: failure.javaClass.simpleName}")
                }
            }
        }
    }

    fun dismissPreview() {
        pending = null
        mutable.update { it.copy(preview = null) }
    }
}
