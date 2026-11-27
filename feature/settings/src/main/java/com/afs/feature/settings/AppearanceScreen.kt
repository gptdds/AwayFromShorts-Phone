package com.afs.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.afs.guard.style.StyleScreenState
import com.afs.guard.style.StyleUiAction

data class StyleOption(val id: String, val title: String)

/** One settings structure and action protocol for every renderer. */
@Composable
fun AppearanceScreen(state: StyleScreenState, options: List<StyleOption>, actualStyle: String,
                     persistenceError: String?, onAction: (StyleUiAction) -> Unit, onBack: () -> Unit) {
    var qualityOpen by rememberSaveable { mutableStateOf(false) }
    val qualities = listOf("auto" to "自动", "high" to "高", "medium" to "中", "low" to "低", "reduced" to "减少动效")
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("back-home")) { Text("返回") }
        Text("界面风格", style = MaterialTheme.typography.headlineMedium)
        persistenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (actualStyle != state.selectedId) Text("所选风格暂不可用，当前使用简约风格", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        options.forEach { style ->
            OutlinedButton(onClick = { onAction(StyleUiAction(StyleUiAction.Kind.SELECT_STYLE, style.id)) },
                modifier = Modifier.fillMaxWidth().testTag("style-${style.id}")) {
                Text(style.title + if (style.id == actualStyle) " ✓" else "")
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("特效档位", style = MaterialTheme.typography.titleMedium)
        Box {
            TextButton(onClick = { qualityOpen = true }, modifier = Modifier.testTag("quality-menu")) {
                Text(qualities.find { it.first == state.qualityPreference }?.second ?: "自动")
            }
            DropdownMenu(expanded = qualityOpen, onDismissRequest = { qualityOpen = false }) {
                qualities.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) },
                    onClick = { qualityOpen = false; onAction(StyleUiAction(StyleUiAction.Kind.SELECT_QUALITY, key)) },
                    modifier = Modifier.testTag("quality-$key")) }
            }
        }
        Text("自动根据设备能力和实际流畅度调整；减少动效会优先遵循系统设置。", style = MaterialTheme.typography.bodySmall)
    }
}
