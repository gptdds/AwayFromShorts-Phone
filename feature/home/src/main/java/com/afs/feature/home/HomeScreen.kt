package com.afs.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.afs.core.model.*
import com.afs.style.api.LocalComponents

/** Shared semantic page. Styles change surfaces/tokens, never the list or event handlers. */
@Composable
fun HomeScreen(state: HomeScreenState, onAction: (HomeUiAction) -> Unit, onSettings: () -> Unit,
               onCheckin: () -> Unit, onFreeze: () -> Unit) {
    val listState = rememberLazyListState()
    val components = LocalComponents.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("守卫", style = MaterialTheme.typography.headlineLarge)
            components.Action(onClick = onSettings, modifier = Modifier.testTag("settings"), enabled = true) { Text("外观") }
        }
        Text("${state.apps.size} 个受管应用 · ${state.apps.count { it.blocked }} 个冻结中", style = MaterialTheme.typography.bodyMedium)
        components.SceneHeader(Modifier.fillMaxWidth())
        Row {
            components.Action(onClick = onCheckin, modifier = Modifier, enabled = true) { Text("打卡") }
            components.Action(onClick = onFreeze, modifier = Modifier, enabled = true) { Text("冻结管理") }
            components.Action(onClick = { onAction(HomeUiAction.Refresh) }, modifier = Modifier, enabled = !state.loading) { Text("刷新") }
        }
        components.SearchField(value = state.query, onValueChange = { onAction(HomeUiAction.Search(it)) },
            label = "搜索应用", modifier = Modifier.fillMaxWidth().testTag("search"))
        FilterChip(selected = state.blockedOnly, onClick = { onAction(HomeUiAction.FilterBlocked(!state.blockedOnly)) },
            label = { Text("只看冻结") }, modifier = Modifier.testTag("blocked-filter"))
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("apps"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.visibleApps, key = { it.id }) { app ->
                components.Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(app.name, style = MaterialTheme.typography.titleMedium)
                        Text(app.status, color = if (app.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!state.loading && state.visibleApps.isEmpty()) item { Text(if (state.query.isBlank()) "暂无符合条件的应用" else "没有找到匹配的应用") }
        }
    }
}
