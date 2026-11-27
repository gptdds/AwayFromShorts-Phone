package com.afs.feature.phase2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afs.guard.style.EffectQuality

@Composable
fun Phase2Screen(
    snapshot: Phase2Snapshot?,
    loading: Boolean,
    error: String?,
    quality: EffectQuality,
    active: Boolean,
    onReload: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val apps = snapshot?.apps.orEmpty()
    val selected = apps.firstOrNull { it.packageName == selectedPackage } ?: apps.firstOrNull()

    Column(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Phase2Colors.background, Phase2Colors.backgroundDeep)
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PHASE 2", color = Phase2Colors.ice, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("三核心组件", color = Phase2Colors.ink, fontSize = 24.sp, fontWeight = FontWeight.Light)
            }
            Text(
                when {
                    loading -> "读取中"
                    error != null -> "读取失败"
                    snapshot == null -> "无数据"
                    else -> "真实规则"
                },
                color = if (error != null) Phase2Colors.red else Phase2Colors.inkMuted,
                fontSize = 10.sp,
                modifier = Modifier
                    .clickable(onClick = onReload)
                    .padding(10.dp)
                    .testTag("phase2-reload")
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("专注环", "应用卡片", "时间轴").forEachIndexed { index, label ->
                TabButton(
                    label = label,
                    selected = tab == index,
                    modifier = Modifier.weight(1f).testTag("phase2-tab-$index")
                ) { tab = index }
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            loading && snapshot == null -> Message("正在读取现有规则与应用状态…")
            error != null -> Message(error, Phase2Colors.red)
            snapshot == null -> Message("没有可显示的规则快照。")
            tab == 0 -> FocusPage(snapshot, quality, active)
            tab == 1 -> AppPage(apps, snapshot.capturedAt, quality, active)
            else -> TimelinePage(
                apps = apps,
                now = snapshot.capturedAt,
                quality = quality,
                active = active,
                selected = selected,
                onSelect = { selectedPackage = it.packageName }
            )
        }
    }
}

@Composable
private fun FocusPage(snapshot: Phase2Snapshot, quality: EffectQuality, active: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FocusRing3D(
            focus = snapshot.focus,
            quality = quality,
            active = active,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Metric("受管应用", "${snapshot.focus.managedApps}", Modifier.weight(1f))
            Metric("实际冻结", "${snapshot.focus.blockedApps}", Modifier.weight(1f))
            Metric("下次计划", formatNext(snapshot.focus.nextChangeAt, snapshot.capturedAt), Modifier.weight(1f))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = .035f), RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = .07f), RoundedCornerShape(18.dp))
                .padding(16.dp)
        ) {
            Text(
                snapshot.focus.detail,
                color = Phase2Colors.inkMuted,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun AppPage(
    apps: List<AppView>,
    now: Long,
    quality: EffectQuality,
    active: Boolean
) {
    if (apps.isEmpty()) {
        Message("当前没有受管应用。")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(apps, key = { it.packageName }) { app ->
            AppCard3D(app = app, now = now, quality = quality, active = active)
        }
    }
}

@Composable
private fun TimelinePage(
    apps: List<AppView>,
    now: Long,
    quality: EffectQuality,
    active: Boolean,
    selected: AppView?,
    onSelect: (AppView) -> Unit
) {
    if (selected == null) {
        Message("当前没有可编辑的受管应用。")
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(apps, key = { it.packageName }) { app ->
                Box(
                    Modifier
                        .background(
                            if (app.packageName == selected.packageName) Phase2Colors.ice.copy(alpha = .16f)
                            else Color.White.copy(alpha = .045f),
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            1.dp,
                            if (app.packageName == selected.packageName) Phase2Colors.ice.copy(alpha = .44f)
                            else Color.White.copy(alpha = .07f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelect(app) }
                        .padding(horizontal = 11.dp, vertical = 7.dp)
                        .testTag("phase2-app-picker-${app.packageName}")
                ) {
                    Text(app.label, color = Phase2Colors.ink, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TimelineEditor(
            app = selected,
            now = now,
            quality = quality,
            active = active,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TabButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(
                if (selected) Phase2Colors.ice.copy(alpha = .15f) else Color.White.copy(alpha = .035f),
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                if (selected) Phase2Colors.ice.copy(alpha = .45f) else Color.White.copy(alpha = .065f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Phase2Colors.ice else Phase2Colors.inkMuted, fontSize = 11.sp)
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.White.copy(alpha = .035f), RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = .065f), RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = Phase2Colors.inkMuted, fontSize = 9.sp)
        Text(value, color = Phase2Colors.ink, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Message(text: String, color: Color = Phase2Colors.inkMuted) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 12.sp)
    }
}
