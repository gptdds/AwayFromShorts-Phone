package com.afs.feature.phase2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.style.api.LocalVisualEnvironment

@Composable
fun BackupScreen(
    busy: Boolean,
    message: String?,
    error: String?,
    preview: BackupPreview?,
    onPickExport: () -> Unit,
    onPickImport: () -> Unit,
    onRestoreMerge: () -> Unit,
    onRestoreReplace: () -> Unit,
    onDismissPreview: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = phase2StatusPalette()
    val text = status.text
    val text2 = status.textMuted
    val accent = status.accent

    Column(
        modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("backup-root"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "返回",
                color = accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 10.dp, horizontal = 4.dp)
                    .testTag("backup-back")
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("规则备份与恢复", color = text, fontSize = 24.sp, fontWeight = FontWeight.Light)
                Text("应用、星期和时段；不含设备管理身份", color = text2, fontSize = 10.sp)
            }
        }

        GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
            Text("导出", color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "生成可审阅的 JSON 文件。不会导出设备所有者、无障碍授权、Shizuku、密钥或系统权限。",
                color = text2,
                fontSize = 10.sp,
                lineHeight = 16.sp
            )
            GlassButton(
                onClick = onPickExport,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                tag = "backup-export"
            ) {
                Text(if (busy) "正在处理…" else "导出 JSON 规则备份", color = status.info, fontSize = 12.sp)
            }
        }

        GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
            Text("导入", color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                "先解析并预览新增与变更数量，再由你选择合并或替换。恢复前会自动保存当前规则。",
                color = text2,
                fontSize = 10.sp,
                lineHeight = 16.sp
            )
            GlassButton(
                onClick = onPickImport,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                tag = "backup-import"
            ) {
                Text("选择 JSON 规则备份", color = status.info, fontSize = 12.sp)
            }
        }

        if (preview != null) {
            GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
                Text("导入预览", color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(preview.text, color = text2, fontSize = 10.sp, lineHeight = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(
                        onClick = onRestoreMerge,
                        enabled = !busy,
                        tag = "backup-merge"
                    ) {
                        Text("合并规则", color = status.info, fontSize = 11.sp)
                    }
                    GlassButton(
                        onClick = onRestoreReplace,
                        enabled = !busy,
                        tag = "backup-replace"
                    ) {
                        Text("替换规则", color = status.warning, fontSize = 11.sp)
                    }
                    GlassButton(onClick = onDismissPreview, tag = "backup-cancel-preview") {
                        Text("取消", color = text2, fontSize = 11.sp)
                    }
                }
            }
        }

        message?.let {
            GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(13.dp)) {
                Text(it, color = status.success, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
        error?.let {
            GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(13.dp)) {
                Text(it, color = status.error, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
        Text(
            "恢复受现有锁定策略和调整时段约束；这里不会绕过这些限制。",
            color = text2.copy(alpha = .78f),
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
    }
}
