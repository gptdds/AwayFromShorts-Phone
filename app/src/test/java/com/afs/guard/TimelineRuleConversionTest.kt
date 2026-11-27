package com.afs.guard

import com.afs.core.rules.WeeklyRule
import com.afs.feature.phase2.MinuteWindow
import com.afs.feature.phase2.TimelineDraft
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineRuleConversionTest {
    @Test
    fun allDayLegacyAllowConvertsToEquivalentFreezeEveryMinute() {
        val legacy = Rule(Rule.ALL_DAYS, arrayOf(intArrayOf(21 * 60, 23 * 60)), true, false)
        val draft = TimelineDraft(Rule.ALL_DAYS, false, listOf(MinuteWindow(21 * 60, 23 * 60)))
        val converted = TimelineRuleConversion.canonicalFreeze(draft, false)
        assertNotNull(converted)
        for (day in 0..6) {
            for (minute in 0 until 1440) {
                assertEquals(
                    "day=$day minute=$minute",
                    WeeklyRule.allow(legacy.days, legacy.windows()).blocked(day, minute),
                    WeeklyRule.freeze(converted!!.days, converted.windows()).blocked(day, minute)
                )
            }
        }
    }

    @Test
    fun mixedLegacyAndFreezeGroupMembersReceiveEquivalentRepresentations() {
        val legacy = Rule(Rule.ALL_DAYS, arrayOf(intArrayOf(21 * 60, 23 * 60)), false, false)
        val freeze = Rule(Rule.ALL_DAYS, arrayOf(intArrayOf(0, 21 * 60), intArrayOf(23 * 60, 0)), true, true)
        val draft = TimelineDraft(Rule.ALL_DAYS, false, listOf(MinuteWindow(20 * 60, 22 * 60)))
        val canonical = TimelineRuleConversion.canonicalFreeze(draft, false)
        assertNotNull(canonical)

        val nextLegacy = TimelineRuleConversion.memberRule(legacy, draft, false, canonical)
        val nextFreeze = TimelineRuleConversion.memberRule(freeze, draft, false, canonical)
        assertFalse(nextLegacy.freeze)
        assertTrue(nextFreeze.freeze)
        assertFalse(nextLegacy.gate)
        assertTrue(nextFreeze.gate)
        for (day in 0..6) {
            for (minute in 0 until 1440) {
                assertEquals(
                    "day=$day minute=$minute",
                    WeeklyRule.allow(nextLegacy.days, nextLegacy.windows()).blocked(day, minute),
                    WeeklyRule.freeze(nextFreeze.days, nextFreeze.windows()).blocked(day, minute)
                )
            }
        }
    }

    @Test
    fun freezeCanConvertToLegacyAllowForAllDayGroups() {
        val allAllowed = TimelineRuleConversion.allowedWindows(
            Rule(Rule.ALL_DAYS, arrayOf(intArrayOf(0, 0)), true, true)
        )
        assertNotNull(allAllowed)
        assertEquals(0, allAllowed!!.size)

        val noneAllowed = TimelineRuleConversion.allowedWindows(
            Rule(Rule.ALL_DAYS, emptyArray(), true, true)
        )
        assertNotNull(noneAllowed)
        assertEquals(1, noneAllowed!!.size)
        assertArrayEquals(intArrayOf(0, 0), noneAllowed[0])
    }

    @Test
    fun weeklyLegacyRulesAreNotSilentlyConverted() {
        val draft = TimelineDraft(
            Rule.ALL_DAYS,
            false,
            listOf(MinuteWindow(21 * 60, 23 * 60))
        )
        assertNull(TimelineRuleConversion.canonicalFreeze(draft.copy(daysMask = 0x1F), false))
    }
}
