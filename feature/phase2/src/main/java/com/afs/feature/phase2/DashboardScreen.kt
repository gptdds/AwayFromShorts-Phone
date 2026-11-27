package com.afs.feature.phase2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.guard.style.EffectQuality
import com.afs.style.api.LocalComponents
import com.afs.style.api.LocalVisualEnvironment

@Composable
fun DashboardScreen(
    snapshot: Phase2Snapshot?,
    loading: Boolean,
    error: String?,
    quality: EffectQuality,
    active: Boolean,
    onReload: () -> Unit,
    onAppearance: () -> Unit,
    onCheckin: () -> Unit,
    onFreeze: () -> Unit,
    onQuota: () -> Unit,
    onApps: () -> Unit,
    onOpenApp: (AppView) -> Unit,
    onDiagnostics: () -> Unit,
    onBackup: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalVisualEnvironment.current.tokens
    val surface = Color(tokens.surface)
    val surface2 = Color(tokens.surface2)
    val line = Color(tokens.line)
    val text = Color(tokens.text)
    val text2 = Color(tokens.text2)
    val accent = Color(tokens.accent)
    val components = LocalComponents.current

    Column(
        modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("dashboard-root"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AWAY FROM SHORTS", color = accent, fontSize = 9.sp, letterSpacing = 2.sp)
                Text("守卫中枢", color = text, fontSize = 27.sp, fontWeight = FontWeight.Light)
            }
            components.Action(
                onClick = onAppearance,
                modifier = Modifier.testTag("dashboard-appearance"),
                enabled = true
            ) { Text("外观") }
        }

        when {
            loading && snapshot == null -> DashboardMessage("正在读取真实规则…", text2)
            error != null -> Box(
                Modifier
                    .fillMaxWidth()
                    .background(Phase2Colors.red.copy(alpha = .10f), RoundedCornerShape(18.dp))
                    .border(1.dp, Phase2Colors.red.copy(alpha = .34f), RoundedCornerShape(18.dp))
                    .clickable(onClick = onReload)
                    .padding(18.dp)
            ) {
                Text(error, color = Phase2Colors.red, fontSize = 12.sp)
            }
            snapshot == null -> DashboardMessage("暂无可显示的状态。", text2)
            else -> {
                FocusRing3D(
                    focus = snapshot.focus,
                    quality = quality,
                    active = active,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DashboardMetric("受管应用", snapshot.focus.managedApps.toString(), Modifier.weight(1f), surface, line, text, text2)
                    DashboardMetric("实际冻结", snapshot.focus.blockedApps.toString(), Modifier.weight(1f), surface, line, text, text2)
                    DashboardMetric(
                        "下次切换",
                        formatNext(snapshot.focus.nextChangeAt, snapshot.capturedAt),
                        Modifier.weight(1f),
                        surface,
                        line,
                        text,
                        text2
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DashboardAction(
                        "打卡",
                        "今天",
                        Modifier.weight(1f).testTag("dashboard-checkin"),
                        surface,
                        surface2,
                        line,
                        text,
                        text2,
                        accent,
                        onCheckin
                    )
                    DashboardAction(
                        "冻结",
                        "时段",
                        Modifier.weight(1f).testTag("dashboard-freeze"),
                        surface,
                        surface2,
                        line,
                        text,
                        text2,
                        Phase2Colors.ice,
                        onFreeze
                    )
                    DashboardAction(
                        "限额",
                        "用量",
                        Modifier.weight(1f).testTag("dashboard-quota"),
                        surface,
                        surface2,
                        line,
                        text,
                        text2,
                        Phase2Colors.amber,
                        onQuota
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("应用规则", color = text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        "全部 ${snapshot.apps.size}",
                        color = accent,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable(onClick = onApps)
                            .padding(8.dp)
                            .testTag("dashboard-open-apps")
                    )
                }
                val featured = snapshot.apps.filter { it.installed && it.blocked }.ifEmpty { snapshot.apps }.take(3)
                if (featured.isEmpty()) {
                    DashboardMessage("当前没有受管应用。", text2)
                } else {
                    featured.forEach { app ->
                        AppCard3D(
                            app = app,
                            now = snapshot.capturedAt,
                            quality = quality,
                            active = active,
                            modifier = Modifier.testTag("dashboard-app-preview-${app.packageName}"),
                            onClick = { onOpenApp(app) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DashboardTextAction("诊断", "dashboard-diagnostics", Modifier.weight(1f), surface, line, text2, onDiagnostics)
                    DashboardTextAction("备份", "dashboard-backup", Modifier.weight(1f), surface, line, text2, onBackup)
                    DashboardTextAction("设置", "dashboard-settings", Modifier.weight(1f), surface, line, text2, onSettings)
                }
                Text(
                    "状态读取于 ${formatClock(snapshot.capturedAt)} · 点击刷新",
                    color = text2.copy(alpha = .72f),
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(onClick = onReload)
                        .padding(vertical = 8.dp)
                        .testTag("dashboard-reload")
                )
            }
        }
    }
}

@Composable
fun AppRulesScreen(
    snapshot: Phase2Snapshot?,
    loading: Boolean,
    error: String?,
    quality: EffectQuality,
    active: Boolean,
    query: String,
    blockedOnly: Boolean,
    onQuery: (String) -> Unit,
    onBlockedOnly: (Boolean) -> Unit,
    onReload: () -> Unit,
    onAddApp: () -> Unit,
    onOpenTimeline: (AppView) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalVisualEnvironment.current.tokens
    val surface = Color(tokens.surface)
    val line = Color(tokens.line)
    val text = Color(tokens.text)
    val text2 = Color(tokens.text2)
    val accent = Color(tokens.accent)
    val components = LocalComponents.current
    val visible = snapshot?.apps.orEmpty().filter { app ->
        (!blockedOnly || app.blocked) &&
            (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true))
    }

    Column(
        modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("app-rules-root")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "返回",
                color = accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 10.dp, horizontal = 4.dp)
                    .testTag("app-rules-back")
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("应用规则", color = text, fontSize = 24.sp, fontWeight = FontWeight.Light)
                Text("真实冻结状态与时间窗口", color = text2, fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "添加",
                    color = accent,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable(onClick = onAddApp)
                        .padding(10.dp)
                        .testTag("app-rules-add")
                )
                Text(
                    "刷新",
                    color = accent,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable(onClick = onReload)
                        .padding(10.dp)
                        .testTag("app-rules-refresh")
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        components.SearchField(
            value = query,
            onValueChange = onQuery,
            label = "搜索名称或包名",
            modifier = Modifier.fillMaxWidth().testTag("app-rules-search")
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleFilter("全部", !blockedOnly, "app-rules-all", surface, line, text2, accent) { onBlockedOnly(false) }
            RuleFilter("只看冻结", blockedOnly, "app-rules-blocked", surface, line, text2, accent) { onBlockedOnly(true) }
            Text(
                "${visible.size} / ${snapshot?.apps?.size ?: 0}",
                color = text2,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
        Spacer(Modifier.height(6.dp))
        when {
            loading && snapshot == null -> DashboardMessage("正在读取应用规则…", text2)
            error != null -> DashboardMessage(error, Phase2Colors.red)
            visible.isEmpty() -> DashboardMessage("没有符合条件的应用。", text2)
            else -> androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).testTag("app-rules-list"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(visible, key = { it.packageName }) { app ->
                    AppCard3D(
                        app = app,
                        now = snapshot?.capturedAt ?: System.currentTimeMillis(),
                        quality = quality,
                        active = active,
                        onClick = { onOpenTimeline(app) }
                    )
                }
            }
        }
    }
}

@Composable
fun TimelineScreen(
    app: AppView?,
    now: Long,
    quality: EffectQuality,
    active: Boolean,
    saving: Boolean,
    saveError: String?,
    saveMessage: String?,
    onSave: (TimelineDraft) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalVisualEnvironment.current.tokens
    val text = Color(tokens.text)
    val text2 = Color(tokens.text2)
    val accent = Color(tokens.accent)
    Column(
        modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("timeline-route")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "返回",
                color = accent,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(vertical = 10.dp, horizontal = 4.dp)
                    .testTag("timeline-back")
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("冻结时段", color = text, fontSize = 24.sp, fontWeight = FontWeight.Light)
                Text("拖动或使用无障碍步进调整", color = text2, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (app == null) {
            DashboardMessage("应用已不在当前规则中。", text2)
        } else {
            TimelineEditor(
                app = app,
                now = now,
                quality = quality,
                active = active,
                modifier = Modifier.weight(1f),
                saving = saving,
                saveError = saveError,
                saveMessage = saveMessage,
                onSave = onSave
            )
        }
    }
}

@Composable
private fun DashboardMetric(
    label: String,
    value: String,
    modifier: Modifier,
    surface: Color,
    line: Color,
    text: Color,
    text2: Color
) {
    Column(
        modifier
            .background(surface.copy(alpha = .72f), RoundedCornerShape(16.dp))
            .border(1.dp, line.copy(alpha = .62f), RoundedCornerShape(16.dp))
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = text2, fontSize = 8.sp)
        Text(value, color = text, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun DashboardAction(
    title: String,
    subtitle: String,
    modifier: Modifier,
    surface: Color,
    surface2: Color,
    line: Color,
    text: Color,
    text2: Color,
    accent: Color,
    onClick: () -> Unit
) {
    Column(
        modifier
            .background(Brush.linearGradient(listOf(surface.copy(alpha = .94f), surface2.copy(alpha = .82f))), RoundedCornerShape(18.dp))
            .border(1.dp, line.copy(alpha = .70f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(Modifier.size(7.dp).background(accent, RoundedCornerShape(50)))
        Text(title, color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = text2, fontSize = 9.sp)
    }
}

@Composable
private fun DashboardTextAction(
    label: String,
    tag: String,
    modifier: Modifier,
    surface: Color,
    line: Color,
    text2: Color,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(surface.copy(alpha = .68f), RoundedCornerShape(14.dp))
            .border(1.dp, line.copy(alpha = .54f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) { Text(label, color = text2, fontSize = 11.sp) }
}

@Composable
private fun RuleFilter(
    label: String,
    selected: Boolean,
    tag: String,
    surface: Color,
    line: Color,
    text2: Color,
    accent: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .background(if (selected) accent.copy(alpha = .14f) else surface.copy(alpha = .58f), RoundedCornerShape(11.dp))
            .border(1.dp, if (selected) accent.copy(alpha = .48f) else line.copy(alpha = .52f), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
            .testTag(tag)
            .semantics { this.selected = selected }
    ) {
        Text(label, color = if (selected) accent else text2, fontSize = 10.sp)
    }
}

@Composable
private fun DashboardMessage(text: String, color: Color) {
    Box(Modifier.fillMaxWidth().padding(vertical = 26.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 12.sp)
    }
}
