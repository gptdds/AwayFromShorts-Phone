package com.afs.feature.phase2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineDraftTest {
    private val allDays = 0x7F
    private val monday = 1

    @Test
    fun allDayCoversOnlySelectedDays() {
        val window = MinuteWindow(0, 0)
        assertTrue(window.allDay)
        assertTrue(window.covers(0, 720, allDays))
        assertFalse(window.covers(0, 720, 0))
        assertEquals(1440, window.minutes)
    }

    @Test
    fun crossMidnightUsesStartDayForBothHalves() {
        val window = MinuteWindow(22 * 60, 60)
        assertTrue(window.crossMidnight)
        assertTrue(window.covers(0, 23 * 60, monday))
        assertTrue(window.covers(1, 30, monday))
        assertFalse(window.covers(0, 30, monday))
        assertFalse(window.covers(1, 23 * 60, monday))
    }

    @Test
    fun freezeAndAllowAreExactComplements() {
        val windows = listOf(MinuteWindow(9 * 60, 10 * 60))
        val freeze = TimelineDraft(allDays, true, windows)
        val allow = TimelineDraft(allDays, false, windows)

        assertTrue(freeze.blockedAt(0, 9 * 60))
        assertFalse(freeze.blockedAt(0, 8 * 60))
        assertFalse(allow.blockedAt(0, 9 * 60))
        assertTrue(allow.blockedAt(0, 8 * 60))
        assertEquals(1380, freeze.allowedMinutes(0))
        assertEquals(60, allow.allowedMinutes(0))
    }

    @Test
    fun emptyWindowsMeanAllDayFreezeInAllowMode() {
        val draft = TimelineDraft(allDays, false, emptyList())
        assertTrue(draft.blockedAt(0, 0))
        assertTrue(draft.blockedAt(0, 1439))
        assertEquals(0, draft.allowedMinutes(0))
    }

    @Test
    fun overlapReportsOnlySharedMinutes() {
        val draft = TimelineDraft(
            allDays,
            true,
            listOf(MinuteWindow(9 * 60, 10 * 60), MinuteWindow(9 * 60 + 30, 10 * 60 + 30))
        )
        assertEquals(30, draft.overlaps().size)
    }

    @Test
    fun editingWindowsKeepsDraftImmutable() {
        val original = TimelineDraft(allDays, true, listOf(MinuteWindow(9 * 60, 10 * 60)))
        val moved = original.withWindow(0, 10 * 60, 11 * 60)
        val added = moved.addWindow(12 * 60, 13 * 60)
        val removed = added.removeWindow(1)
        val toggled = removed.toggleDay(0)

        assertEquals(MinuteWindow(9 * 60, 10 * 60), original.windows[0])
        assertEquals(MinuteWindow(10 * 60, 11 * 60), moved.windows[0])
        assertEquals(2, added.windows.size)
        assertEquals(1, removed.windows.size)
        assertEquals(allDays xor 1, toggled.daysMask)
    }

    @Test
    fun rangesCanDescribeTheAllowComplement() {
        val draft = TimelineDraft(allDays, false, listOf(MinuteWindow(9 * 60, 10 * 60)))
        val blocked = draft.ranges(0, true)
        assertEquals(2, blocked.size)
        assertEquals(0, blocked[0].start)
        assertEquals(9 * 60, blocked[0].end)
        assertEquals(10 * 60, blocked[1].start)
        assertEquals(0, blocked[1].end)
    }
}
