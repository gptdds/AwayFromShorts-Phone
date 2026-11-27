package com.afs.core.model

import org.junit.Assert.*
import org.junit.Test

class HomeStateTest {
    private val apps = listOf(ManagedApp("one", "Telegram", "冻结", true), ManagedApp("two", "Steam", "可用", false))
    @Test fun searchAndFilterUseSameSnapshot() {
        val state = HomeScreenState(apps = apps, query = "tele", blockedOnly = true)
        assertEquals(listOf(apps[0]), state.visibleApps)
        assertEquals(2, state.apps.size)
        assertTrue(state.copy(query = "steam").visibleApps.isEmpty())
        assertEquals(listOf(apps[1]), state.copy(query = "TWO", blockedOnly = false).visibleApps)
    }
}
