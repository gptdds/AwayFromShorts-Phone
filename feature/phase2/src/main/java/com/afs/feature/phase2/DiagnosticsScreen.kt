package com.afs.feature.phase2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.guard.style.EffectQuality
import com.afs.style.api.LocalVisualEnvironment

@Composable
fun DiagnosticsScreen(
    snapshot: DiagnosticsSnapshot?,
    loading: Boolean,
    error: String?,
    actionMessage: String?,
    quality: EffectQuality,
    active: Boolean,
    onRefresh: () -> Unit,
    onAction: (DiagnosticAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = phase2StatusPalette()
    val text = status.text
    val text2 = status.textMuted
    val accent = status.accent
    val issues = snapshot?.issues.orEmpty()

    Column(
        modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("diagnostics-root"),
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
                    .semantics { contentDescription = "返回守卫中枢" }
                    .testTag("diagnostics-back")
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("系统诊断", color = text, fontSize = 25.sp, fontWeight = FontWeight.Light)
                Text("权限、守护、调度与规则一致性", color = text2, fontSize = 10.sp)
            }
            GlassButton(onClick = onRefresh, tag = "diagnostics-refresh") {
                Text("重新运行", color = accent, fontSize = 11.sp)
            }
        }

        when {
            loading && snapshot == null -> DiagnosticNotice("正在读取系统状态…", text2)
            error != null -> DiagnosticNotice(error, status.error)
            snapshot == null -> DiagnosticNotice("没有诊断结果。", text2)
            else -> {
                FluidStatusOrb(
                    level = when {
                        snapshot.errorCount > 0 -> DiagnosticLevel.ERROR
                        snapshot.warningCount > 0 -> DiagnosticLevel.WARNING
                        else -> DiagnosticLevel.OK
                    },
                    heading = snapshot.headline,
                    detail = if (issues.isEmpty()) {
                        "所有已检查项目都有明确结果。"
                    } else {
                        "共 ${issues.size} 项需要关注；可以按每项右侧的操作处理。"
                    },
                    active = active,
                    quality = quality
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DiagnosticCount("正常", snapshot.okCount, status.success, Modifier.weight(1f))
                    DiagnosticCount("警告", snapshot.warningCount, status.warning, Modifier.weight(1f))
                    DiagnosticCount("错误", snapshot.errorCount, status.error, Modifier.weight(1f))
                }

                if (issues.isNotEmpty()) {
                    Text(
                        "需要处理",
                        color = text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, top = 3.dp)
                    )
                    issues.forEach { item ->
                        DiagnosticRow(item, onAction)
                    }
                }

                Text(
                    "全部检查",
                    color = text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, top = 3.dp)
                )
                snapshot.items.forEach { item ->
                    DiagnosticRow(item, onAction)
                }

                actionMessage?.let {
                    DiagnosticNotice(it, status.success)
                }
                DiagnosticNotice("状态读取于 ${formatClock(snapshot.capturedAt)}", text2.copy(alpha = .8f))
            }
        }
    }
}

@Composable
private fun DiagnosticCount(label: String, value: Int, color: Color, modifier: Modifier) {
    val status = phase2StatusPalette()
    Column(
        modifier
            .background(color.copy(alpha = .09f), RoundedCornerShape(15.dp))
            .border(1.dp, color.copy(alpha = .24f), RoundedCornerShape(15.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, color = color, fontSize = 9.sp)
        Text(value.toString(), color = status.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DiagnosticRow(item: DiagnosticItem, onAction: (DiagnosticAction) -> Unit) {
    val status = phase2StatusPalette()
    val color = when (item.level) {
        DiagnosticLevel.OK -> status.success
        DiagnosticLevel.INFO -> status.info
        DiagnosticLevel.WARNING -> status.warning
        DiagnosticLevel.ERROR -> status.error
    }
    val label = when (item.level) {
        DiagnosticLevel.OK -> "正常"
        DiagnosticLevel.INFO -> "信息"
        DiagnosticLevel.WARNING -> "警告"
        DiagnosticLevel.ERROR -> "错误"
    }
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${item.title}，$label，${item.detail}" }
            .testTag("diagnostic-${item.id}"),
        cornerRadius = 18.dp,
        padding = PaddingValues(13.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(28.dp)
                        .background(color, RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(9.dp))
                Text(item.title, color = status.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(label, color = color, fontSize = 9.sp)
            }
            Text(item.detail, color = status.textMuted, fontSize = 10.sp, lineHeight = 15.sp)
            item.action?.let { action ->
                GlassButton(
                    onClick = { onAction(action) },
                    tag = "diagnostic-action-${item.id}"
                ) {
                    Text(actionLabel(action), color = status.info, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun actionLabel(action: DiagnosticAction): String = when (action) {
    DiagnosticAction.REAPPLY_RULES -> "重新应用规则"
    DiagnosticAction.OPEN_ACCESSIBILITY -> "无障碍设置"
    DiagnosticAction.OPEN_BATTERY_OPTIMIZATION -> "电池优化设置"
    DiagnosticAction.OPEN_USAGE_ACCESS -> "使用情况访问设置"
    DiagnosticAction.OPEN_NOTIFICATIONS -> "通知设置"
    DiagnosticAction.OPEN_EXACT_ALARM -> "精确闹钟设置"
}

@Composable
private fun DiagnosticNotice(message: String, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = .08f), RoundedCornerShape(14.dp))
            .border(1.dp, color.copy(alpha = .22f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(message, color = color, fontSize = 10.sp, lineHeight = 15.sp)
    }
}
