package com.afs.guard

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.core.rules.RuleBackupDocument
import com.afs.feature.phase2.MinuteWindow
import com.afs.feature.phase2.TimelineDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class RuleTimelineRepositoryTest {
    private lateinit var context: Context
    private lateinit var baseline: RuleBackupDocument
    private lateinit var baselineSlots: List<FreezeSlotStore.Slot>
    private var oldStart = 0
    private var oldEnd = 0
    private var oldCleared = false
    private var oldDefault = false
    private var oldArmed = false

    @Before
    fun snapshotState() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(
            "时间轴写入回归需要未启用设备所有者或首次配置宽限期",
            LocalBackup.mayRestore(context)
        )
        baseline = RuleBackup.snapshot(context)
        baselineSlots = FreezeSlotStore.all(context)
        val window = context.getSharedPreferences("afs_time_window", Context.MODE_PRIVATE)
        oldStart = window.getInt("start", TimeWindow.DEFAULT_START)
        oldEnd = window.getInt("end", TimeWindow.DEFAULT_END)
        oldCleared = window.getBoolean("cleared", false)
        oldDefault = window.getBoolean("default_22_23", false)
        oldArmed = context.getSharedPreferences("afs_guard", Context.MODE_PRIVATE)
            .getBoolean("armed", false)
    }

    @After
    fun restoreState() {
        if (!::baseline.isInitialized) return
        try {
            RuleBackup.restore(context, baseline, true)
        } finally {
            FreezeSlotStore.save(context, baselineSlots)
            context.getSharedPreferences("afs_time_window", Context.MODE_PRIVATE).edit()
                .putInt("start", oldStart)
                .putInt("end", oldEnd)
                .putBoolean("cleared", oldCleared)
                .putBoolean("default_22_23", oldDefault)
                .commit()
            context.getSharedPreferences("afs_guard", Context.MODE_PRIVATE).edit()
                .putBoolean("armed", oldArmed)
                .commit()
        }
    }

    @Test
    fun saveWritesTheRuleAndCreatesTheFreezeSlot() = runBlocking {
        val fixture = "com.afs.phase2.timeline.fixture"
        RuleStore.put(
            context,
            fixture,
            Rule(0x7F, arrayOf(intArrayOf(21 * 60, 22 * 60)), true, true)
        )
        openAdjustmentWindow()
        val draft = TimelineDraft(
            0x7F,
            true,
            listOf(MinuteWindow(2 * 60 + 13, 2 * 60 + 19))
        )

        val result = RuleTimelineRepository(context).save(fixture, draft)

        val saved = RuleStore.all(context).getValue(fixture)
        assertEquals(draft.daysMask, saved.days)
        assertArrayEquals(draft.toRuleWindows(), saved.windows())
        assertTrue(result.members >= 1)
        val slot = FreezeSlotStore.all(context).first {
            FreezeSlotStore.scheduleKey(it.rule) == FreezeSlotStore.scheduleKey(saved)
        }
        assertTrue(FreezeSlotStore.members(slot, RuleStore.all(context)).contains(fixture))
    }

    @Test
    fun saveIsRejectedOutsideTheAdjustmentWindow() = runBlocking {
        val fixture = "com.afs.phase2.timeline.fixture"
        RuleStore.put(
            context,
            fixture,
            Rule(0x7F, arrayOf(intArrayOf(21 * 60, 22 * 60)), true, true)
        )
        closeAdjustmentWindow()
        val draft = TimelineDraft(
            0x7F,
            true,
            listOf(MinuteWindow(2 * 60 + 13, 2 * 60 + 19))
        )

        val failure = runCatching {
            RuleTimelineRepository(context).save(fixture, draft)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("调整时段"))
        assertFalse(failure?.message.isNullOrBlank())
    }

    private fun openAdjustmentWindow() {
        context.getSharedPreferences("afs_guard", Context.MODE_PRIVATE).edit()
            .putBoolean("armed", true)
            .commit()
        context.getSharedPreferences("afs_time_window", Context.MODE_PRIVATE).edit()
            .putInt("start", 0)
            .putInt("end", 0)
            .putBoolean("cleared", false)
            .putBoolean("default_22_23", true)
            .commit()
    }

    private fun closeAdjustmentWindow() {
        val minute = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) * 60 +
            Calendar.getInstance().get(Calendar.MINUTE)
        val start = (minute + 2) % 1440
        val end = (minute + 3) % 1440
        context.getSharedPreferences("afs_guard", Context.MODE_PRIVATE).edit()
            .putBoolean("armed", true)
            .commit()
        context.getSharedPreferences("afs_time_window", Context.MODE_PRIVATE).edit()
            .putInt("start", start)
            .putInt("end", end)
            .putBoolean("cleared", false)
            .putBoolean("default_22_23", true)
            .commit()
    }

    private fun assertArrayEquals(expected: Array<IntArray>, actual: Array<IntArray>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index ->
            org.junit.Assert.assertArrayEquals(expected[index], actual[index])
        }
    }
}
