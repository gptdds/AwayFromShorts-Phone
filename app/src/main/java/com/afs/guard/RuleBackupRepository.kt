package com.afs.guard

import android.content.Context
import android.net.Uri
import com.afs.core.rules.RuleBackupDocument
import com.afs.feature.phase2.BackupPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** SAF and restore adapter. Rule validation and writes stay in the existing RuleBackup core. */
class RuleBackupRepository(private val context: Context) {
    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val document = RuleBackup.snapshot(context)
        val json = document.encode()
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            if (output == null) throw IOException("无法写入目标文件")
            output.write(json.toByteArray(Charsets.UTF_8))
        }
        document.rules.size
    }

    suspend fun read(uri: Uri): RuleBackupDocument = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri).use { input ->
            RuleBackupDocument.read(input)
        }
        RuleBackupDocument.parse(raw, "device-owner")
    }

    fun preview(document: RuleBackupDocument): String =
        document.preview(RuleBackup.snapshot(context).rules)

    suspend fun restore(document: RuleBackupDocument, replace: Boolean) =
        withContext(Dispatchers.IO) {
            RuleBackup.restore(context, document, replace)
        }
}

data class BackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val preview: BackupPreview? = null
)
