package com.afs.core.model

/** Display snapshot only; enforcement remains in the original Guard/Store classes. */
data class ManagedApp(val id: String, val name: String, val status: String, val blocked: Boolean)
data class HomeSnapshot(val apps: List<ManagedApp>, val checkedAt: Long)

data class HomeScreenState(
    val apps: List<ManagedApp> = emptyList(),
    val query: String = "",
    val blockedOnly: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val checkedAt: Long = 0L
) {
    val visibleApps: List<ManagedApp> get() = apps.filter {
        (!blockedOnly || it.blocked) && (query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true))
    }
}

sealed interface HomeUiAction {
    data class Search(val text: String) : HomeUiAction
    data class FilterBlocked(val enabled: Boolean) : HomeUiAction
    data object Refresh : HomeUiAction
}
