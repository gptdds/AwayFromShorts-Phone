package com.afs.feature.phase2

import androidx.compose.ui.graphics.ImageBitmap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Half-open minutes in one local day. end == 0 means 24:00 when start > 0. */
data class MinuteWindow(val start: Int, val end: Int) {
    init {
        require(start in 0..1439) { "start minute out of range" }
        require(end in 0..1439) { "end minute out of range" }
    }

    val allDay: Boolean get() = start == end
    val crossMidnight: Boolean get() = start > end
    val minutes: Int get() = when {
        allDay -> 1440
        crossMidnight -> (1440 - start) + end
        else -> end - start
    }

    fun covers(dayIndex: Int, minute: Int, daysMask: Int): Boolean = when {
        allDay -> selected(daysMask, dayIndex)
        start < end -> selected(daysMask, dayIndex) && minute >= start && minute < end
        else -> (selected(daysMask, dayIndex) && minute >= start) ||
            (selected(daysMask, previousDay(dayIndex)) && minute < end)
    }
}

data class TimelineDraft(
    val daysMask: Int,
    val freezeMode: Boolean,
    val windows: List<MinuteWindow>
) {
    fun sorted(): TimelineDraft = copy(windows = windows.sortedWith(compareBy({ it.start }, { it.end })))

    fun withWindow(index: Int, start: Int, end: Int): TimelineDraft =
        copy(windows = windows.toMutableList().also { it[index] = MinuteWindow(norm(start), norm(end)) })

    fun addWindow(start: Int, end: Int): TimelineDraft =
        copy(windows = windows + MinuteWindow(norm(start), norm(end)))

    fun removeWindow(index: Int): TimelineDraft =
        copy(windows = windows.toMutableList().also { it.removeAt(index) })

    fun toggleDay(dayIndex: Int): TimelineDraft =
        copy(daysMask = daysMask xor (1 shl dayIndex))

    fun blockedAt(dayIndex: Int, minute: Int): Boolean {
        val covered = windows.any { it.covers(dayIndex, minute, daysMask) }
        return if (freezeMode) covered else !covered
    }

    fun allowedMinutes(dayIndex: Int): Int = (0 until 1440).count { !blockedAt(dayIndex, it) }

    fun freezeMinutes(dayIndex: Int): Int = 1440 - allowedMinutes(dayIndex)

    fun ranges(dayIndex: Int, blocked: Boolean): List<MinuteWindow> {
        val result = mutableListOf<MinuteWindow>()
        var start = -1
        for (minute in 0..1440) {
            val matches = minute < 1440 && blockedAt(dayIndex, minute) == blocked
            if (matches && start < 0) start = minute
            if (!matches && start >= 0) {
                result += MinuteWindow(start, minute % 1440)
                start = -1
            }
        }
        return result
    }

    fun overlaps(): List<Int> {
        val count = IntArray(1440)
        windows.forEach { window ->
            val segments = windowSegments(window)
            segments.forEach { (from, to) -> for (m in from until to) count[m]++ }
        }
        return (0 until 1440).filter { count[it] > 1 }
    }

    fun summary(dayIndex: Int = 0): String {
        val mode = if (freezeMode) "冻结" else "放行"
        val spans = if (freezeMode) windows else ranges(dayIndex, false)
        val times = if (spans.isEmpty()) "全天" else spans.joinToString("、") {
            "${clock(it.start)}–${clock(if (it.end == 0) 1440 else it.end)}"
        }
        return "${daysText(daysMask)} · $times $mode"
    }
}

data class FocusView(
    val remainingMs: Long,
    val progress: Float,
    val nextChangeAt: Long,
    val lastChangeAt: Long,
    val blockedApps: Int,
    val managedApps: Int,
    val status: String,
    val detail: String
)

data class AppView(
    val packageName: String,
    val label: String,
    val blocked: Boolean,
    val status: String,
    val allowedText: String,
    val nextChangeAt: Long,
    val daysMask: Int,
    val freezeMode: Boolean,
    val windows: List<MinuteWindow>,
    val gate: Boolean,
    val installed: Boolean,
    val icon: ImageBitmap? = null
) {
    val id: String get() = packageName
}

data class Phase2Snapshot(
    val focus: FocusView,
    val apps: List<AppView>,
    val capturedAt: Long
)

fun norm(minute: Int): Int = ((minute % 1440) + 1440) % 1440

fun selected(mask: Int, dayIndex: Int): Boolean = (mask and (1 shl (dayIndex and 7))) != 0

fun previousDay(dayIndex: Int): Int = (dayIndex + 6) % 7

fun windowSegments(window: MinuteWindow): List<Pair<Int, Int>> = when {
    window.allDay -> listOf(0 to 1440)
    window.start < window.end -> listOf(window.start to window.end)
    else -> listOf(window.start to 1440, 0 to window.end).filter { it.first < it.second }
}

fun clock(minute: Int): String {
    val normalized = if (minute >= 1440) 0 else norm(minute)
    return String.format(Locale.CHINA, "%02d:%02d", normalized / 60, normalized % 60)
}

fun daysText(mask: Int): String = when {
    (mask and 0x7F) == 0 -> "一天都没选"
    (mask and 0x7F) == 0x7F -> "每天"
    (mask and 0x7F) == 0x1F -> "工作日"
    (mask and 0x7F) == 0x60 -> "周末"
    else -> (0..6).filter { selected(mask, it) }.joinToString("、") { "周${"一二三四五六日"[it]}" }
}

fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) String.format(Locale.CHINA, "%02d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)
}

fun formatClock(ms: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(Date(ms))

fun formatNext(ms: Long, now: Long): String {
    if (ms <= 0L) return "暂无计划切换"
    val delta = ms - now
    return if (delta <= 0L) "即将切换" else "${formatDuration(delta)} 后"
}

fun currentDayIndex(now: Long = System.currentTimeMillis()): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = now }
    return (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
}
