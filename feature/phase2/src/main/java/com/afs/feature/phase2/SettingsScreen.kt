package com.afs.feature.phase2

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.style.api.LocalVisualEnvironment

data class SettingsSummary(
    val style: String,
    val theme: String,
    val quality: String,
    val version: String,
    val lowPower: Boolean
)

@Composable
fun SettingsScreen(
    summary: SettingsSummary,
    onAppearance: () -> Unit,
    onTheme: () -> Unit,
    onBackup: () -> Unit,
    onDiagnostics: () -> Unit,
    onSelfControl: () -> Unit,
    onData: () -> Unit,
    onAdvanced: () -> Unit,
    onLowPower: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = phase2StatusPalette()
    val text = status.text
    val text2 = status.textMuted
    val accent = status.accent
    var aboutOpen by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("settings-root"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    .testTag("settings-back")
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("设置", color = text, fontSize = 25.sp, fontWeight = FontWeight.Light)
                Text("外观、权限、备份与关于", color = text2, fontSize = 10.sp)
            }
        }

        SettingsSection("外观与性能")
        SettingsRow(
            icon = "◈",
            title = "界面风格",
            detail = "${summary.style} · 四套渲染器共用同一页面状态",
            value = summary.theme,
            tag = "settings-appearance",
            onClick = onAppearance
        )
        SettingsRow(
            icon = "◐",
            title = "主题与配色",
            detail = "深浅色与原有配色方案",
            tag = "settings-theme",
            onClick = onTheme
        )
        SettingsRow(
            icon = "✦",
            title = "动效与低功耗",
            detail = "当前 ${summary.quality}；低功耗使用静态材质和最少动画",
            value = if (summary.lowPower) "已开启" else "关闭",
            tag = "settings-low-power",
            onClick = { onLowPower(!summary.lowPower) }
        )

        SettingsSection("规则与数据")
        SettingsRow(
            icon = "⛨",
            title = "自律规则",
            detail = "重置时间、亮屏上限、提醒与调整时段",
            tag = "settings-self-control",
            onClick = onSelfControl
        )
        SettingsRow(
            icon = "⇄",
            title = "规则备份与恢复",
            detail = "导出或恢复应用、星期和时段；不含设备管理身份",
            tag = "settings-backup",
            onClick = onBackup
        )
        SettingsRow(
            icon = "▣",
            title = "完整数据管理",
            detail = "本地备份、云端配置和原有整合备份",
            tag = "settings-data",
            onClick = onData
        )

        SettingsSection("系统")
        SettingsRow(
            icon = "✚",
            title = "系统诊断",
            detail = "权限、守护、调度器与系统冻结一致性",
            tag = "settings-diagnostics",
            onClick = onDiagnostics
        )
        SettingsRow(
            icon = "⚙",
            title = "完整旧版设置",
            detail = "语言、插件、成就、跨设备额度与全部权限项",
            tag = "settings-advanced",
            onClick = onAdvanced
        )
        SettingsRow(
            icon = "i",
            title = "关于",
            detail = summary.version,
            tag = "settings-about",
            onClick = { aboutOpen = true }
        )
        Text(
            "视觉层只读取状态；冻结、额度和系统权限仍由原有业务核心执行。",
            color = text2.copy(alpha = .78f),
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
    }

    if (aboutOpen) {
        GlassDialog(
            onDismiss = { aboutOpen = false },
            title = "AwayFromShorts",
            confirmLabel = "知道了",
            onConfirm = { aboutOpen = false }
        ) {
            Text("版本 ${summary.version}", color = status.text, fontSize = 13.sp)
            Text(
                "数字专注与应用控制工具。界面材质与动效位于独立视觉层，规则、设备所有者、无障碍和额度逻辑保持原有实现。",
                color = status.textMuted,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    val status = phase2StatusPalette()
    Text(
        title,
        color = status.textMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, top = 9.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: String,
    title: String,
    detail: String,
    tag: String,
    value: String? = null,
    onClick: () -> Unit
) {
    val status = phase2StatusPalette()
    val moving = LocalVisualEnvironment.current.active &&
        LocalVisualEnvironment.current.quality.allowMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && moving) .982f else 1f,
        animationSpec = spring(.70f, 460f),
        label = "settings-row-scale"
    )
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .semantics {
                role = Role.Button
                contentDescription = "$title，$detail${value?.let { "，$it" }.orEmpty()}"
            }
            .testTag(tag),
        cornerRadius = 19.dp,
        padding = PaddingValues(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                status.accent.copy(alpha = .22f),
                                status.info.copy(alpha = .10f),
                                Color.White.copy(alpha = .035f)
                            )
                        ),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, color = status.info, fontSize = 15.sp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = status.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (value != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(value, color = status.info, fontSize = 9.sp)
                    }
                }
                Text(detail, color = status.textMuted, fontSize = 9.sp, lineHeight = 14.sp)
            }
            Text("›", color = status.textMuted.copy(alpha = .65f), fontSize = 18.sp)
        }
    }
}
