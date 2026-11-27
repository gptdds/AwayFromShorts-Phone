package com.afs.feature.componentlab

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import com.afs.style.api.*
import kotlin.math.abs

@Composable
fun ComponentLabScreen(styleId: String, onStyle: (String) -> Unit, playing: Boolean, onPlaying: (Boolean) -> Unit,
                       onDialogWindow: (android.view.Window?) -> Unit = {}) {
    val engine = LocalComponentLab.current
    var category by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var reminder by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableFloatStateOf(.42f) }
    var tag by rememberSaveable { mutableIntStateOf(0) }
    var choice by rememberSaveable { mutableIntStateOf(1) }
    var count by rememberSaveable { mutableIntStateOf(0) }
    var selectedRow by rememberSaveable { mutableIntStateOf(-1) }
    var notice by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf(false) }
    val dialogTransition = remember { MutableTransitionState(false) }
    dialogTransition.targetState = dialog
    val scroll = rememberScrollState()
    LaunchedEffect(category) { scroll.scrollTo(0) }
    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 178.dp).verticalScroll(scroll).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("COMPONENT STUDY", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text(if (engine.shaderReady) if (playing) "● 动态" else "○ 静态" else "材质载入中", color = engine.palette.accent, fontSize = 11.sp)
            }
            Text(engine.palette.name, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Light)
            Text(engine.palette.description, color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabButton("Glass", Modifier.weight(1f), LabRole.TAG, styleId == "liquidglass", "style-glass") { onStyle("liquidglass") }
                LabButton("Fluid", Modifier.weight(1f), LabRole.TAG, styleId == "fluidgradient", "style-fluid") { onStyle("fluidgradient") }
                LabButton(if (playing) "Ⅱ" else "▷", Modifier.width(52.dp), LabRole.THUMB, tag = "pause-motion") { onPlaying(!playing) }
            }
            when (category) {
                0 -> {
                    Section("01", "操作与反馈")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabButton("开始专注  ↗", Modifier.weight(1f), LabRole.PRIMARY, tag = "primary-action") { count++; notice = "已启动 $count 次" }
                        LabButton("重置", Modifier.width(88.dp), tag = "secondary-action") { count = 0; notice = "操作计数已重置" }
                        LabButton("＋", Modifier.width(52.dp), LabRole.THUMB, tag = "icon-action") { count++; notice = "已添加第 $count 项" }
                    }
                    Text("已触发 $count 次操作", Modifier.testTag("action-count"), color = Muted, fontSize = 12.sp)
                    LabNode(Modifier.fillMaxWidth(), LabRole.CARD) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("今日专注", color = Muted, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${(amount * 60).toInt()}", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Light)
                                Text("分钟", Modifier.padding(bottom = 8.dp), color = Muted, fontSize = 13.sp)
                            }
                            LabProgress(amount, playing)
                            Text("轻触、按住或拖动表面", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric("本周", "5 天", Modifier.weight(1f))
                        Metric("连续", "12 天", Modifier.weight(1f))
                    }
                    Text("标签 / 选项", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("专注", "休息", "灵感").forEachIndexed { index, label ->
                            LabButton(label, Modifier.weight(1f), LabRole.TAG, tag == index, "tag-$index") { tag = index }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("低", "标准", "高").forEachIndexed { index, label ->
                            LabButton(label, Modifier.weight(1f), LabRole.SECONDARY, choice == index, "choice-$index") { choice = index }
                        }
                    }
                }
                1 -> {
                    Section("02", "输入与调节")
                    LabInput(query, { query = it }, "⌕  搜索组件", "lab-search")
                    Text(if (query.isBlank()) "输入时观察凹槽与局部光线" else "搜索：$query", color = Muted, fontSize = 12.sp)
                    LabInput(note, { note = it }, "写下一点想法…", "lab-note", multiline = true)
                    LabNode(Modifier.fillMaxWidth(), LabRole.CARD) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column { Text("专注提醒", color = Ink, fontSize = 15.sp); Text(if (reminder) "已开启" else "已关闭", color = Muted, fontSize = 12.sp) }
                                LabSwitch(reminder) { reminder = it }
                            }
                            Text("时长  ${(amount * 60).toInt()} 分钟", Modifier.testTag("duration-value"), color = Ink, fontSize = 15.sp)
                            LabSlider(amount) { amount = it }
                            LabProgress(amount, playing)
                        }
                    }
                    LabButton("保存输入", Modifier.fillMaxWidth(), LabRole.PRIMARY, tag = "save-input") { notice = "已保存 ${note.length} 个字 · 提醒${if (reminder) "开启" else "关闭"}" }
                }
                else -> {
                    Section("03", "列表与浮层")
                    Text("点击列表项切换选中状态", color = Muted, fontSize = 12.sp)
                    listOf("晨间计划", "深度阅读", "整理思绪", "晚间回顾").filter { query.isBlank() || it.contains(query) }.forEachIndexed { index, name ->
                        LabNode(Modifier.fillMaxWidth().testTag("list-row-$index").selectable(selectedRow == index, onClick = { selectedRow = if (selectedRow == index) -1 else index }), LabRole.CARD, selectedRow == index) {
                            Row(Modifier.padding(20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(name, color = Ink, fontSize = 15.sp); Text("${15 + index * 5} 分钟 · 个人计划", color = Muted, fontSize = 12.sp) }
                                LabNode(Modifier.size(30.dp), LabRole.THUMB, selectedRow == index) { Text(if (selectedRow == index) "✓" else "›", Modifier.align(Alignment.Center), color = Ink) }
                            }
                        }
                    }
                    Text(if (selectedRow < 0) "尚未选择项目" else "已选择第 ${selectedRow + 1} 项", Modifier.testTag("list-selection-status"), color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabButton("弹出菜单", Modifier.weight(1f), tag = "open-menu") { menu = true }
                        LabButton("打开对话框", Modifier.weight(1f), LabRole.PRIMARY, tag = "open-dialog") { dialog = true }
                    }
                    LabButton("显示通知", Modifier.fillMaxWidth(), LabRole.NOTICE, tag = "show-notice") { notice = "全部内容已同步" }
                    LabNode(Modifier.fillMaxWidth(), LabRole.CARD) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Text("同步进度", color = Muted, fontSize = 13.sp); LabProgress(amount, playing) }
                    }
                }
            }
        }
        LabButton("＋", Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 106.dp).size(56.dp), LabRole.THUMB, tag = "floating-action") { dialog = true }
        LabNavigation(category, { category = it }, Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 18.dp))
        AnimatedVisibility(notice.isNotEmpty(), Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp, vertical = 112.dp), enter = slideInVertically { -it / 3 } + fadeIn(), exit = fadeOut() + slideOutVertically { -it / 3 }) {
            LabNode(Modifier.fillMaxWidth().testTag("notice"), LabRole.NOTICE, true) {
                Row(Modifier.padding(18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(notice, Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                    Text("×", Modifier.padding(start = 16.dp).testTag("dismiss-notice").clickable { notice = "" }, color = Ink, fontSize = 23.sp)
                }
            }
        }
        if (menu) Popup(alignment = Alignment.TopEnd, onDismissRequest = { menu = false }) {
            LabNode(Modifier.padding(top = 130.dp, end = 24.dp).width(220.dp).testTag("popup-menu"), LabRole.DIALOG) {
                Column(Modifier.padding(12.dp)) {
                    listOf("收藏组件", "复制样式", "恢复默认").forEach { item ->
                        Text(item, Modifier.fillMaxWidth().clickable { notice = "已$item"; menu = false }.padding(18.dp), color = Ink, fontSize = 14.sp)
                    }
                }
            }
        }
        if (dialogTransition.currentState || dialogTransition.targetState) Dialog(onDismissRequest = { dialog = false }) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            DisposableEffect(window) { onDialogWindow(window); onDispose { onDialogWindow(null) } }
            AnimatedVisibility(dialogTransition, enter = scaleIn(initialScale = engine.palette.entranceScale) + fadeIn(), exit = scaleOut(targetScale = engine.palette.entranceScale) + fadeOut()) {
                LabNode(Modifier.fillMaxWidth().testTag("lab-dialog"), LabRole.DIALOG, true) {
                    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Text("保留这次灵感", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Light)
                        Text("将当前组件设置保存为一个新的组合。", color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LabButton("取消", Modifier.weight(1f), tag = "cancel-dialog") { dialog = false }
                            LabButton("保存", Modifier.weight(1f), LabRole.PRIMARY, tag = "confirm-dialog") { dialog = false; notice = "组件组合已保存" }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Section(index: String, name: String) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(index, color = LocalComponentLab.current.palette.accent, fontSize = 11.sp)
        Text(name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    LabNode(modifier, LabRole.CARD) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(label, color = Muted, fontSize = 12.sp); Text(value, color = Ink, fontSize = 22.sp) } }
}

@Composable private fun LabNavigation(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val engine = LocalComponentLab.current
    val position by animateFloatAsState(selected.toFloat(), spring(engine.palette.damping, 300f), label = "navigation-fluid-travel")
    LabNode(modifier.fillMaxWidth().height(72.dp), LabRole.NAVIGATION) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(7.dp)) {
            val cell = maxWidth / 3
            LabNode(Modifier.width(cell).fillMaxHeight().graphicsLayer {
                translationX = cell.toPx() * position
                scaleX = 1f + abs(selected - position).coerceAtMost(1f) * .20f
            }, LabRole.TAG, true) {}
            Row(Modifier.fillMaxSize()) {
                listOf("操作", "输入", "信息").forEachIndexed { index, label ->
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("category-$index").semantics(mergeDescendants = true) { this.selected = selected == index }
                        .clickable { onSelect(index) }, contentAlignment = Alignment.Center) {
                        Text(label, color = if (selected == index) Ink else Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
