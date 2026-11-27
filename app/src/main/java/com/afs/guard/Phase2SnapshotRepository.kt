package com.afs.guard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.afs.feature.phase2.AppView
import com.afs.feature.phase2.FocusView
import com.afs.feature.phase2.MinuteWindow
import com.afs.feature.phase2.Phase2Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only adapter from the deployed guard core to the isolated phase-two components.
 *
 * It deliberately does not write rules, call DevicePolicyManager, or create a second
 * schedule implementation. The existing Rule/WeeklyRule objects remain the source of
 * truth for every value shown here.
 */
class Phase2SnapshotRepository(context: android.content.Context) {
    private val app = context.applicationContext
    private val reads = AtomicInteger()
    val readCount: Int get() = reads.get()

    suspend fun load(): Phase2Snapshot = withContext(Dispatchers.IO) {
        reads.incrementAndGet()
        val now = System.currentTimeMillis()
        val nowCalendar = GuardClock.now(app).apply {
            timeInMillis = now
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val rules = Guard.enforcedRules(app)
        val next = Guard.nextScheduledChange(app)
        val previous = previousScheduleChange(rules.values.toList(), nowCalendar)
        val apps = rules.map { (packageName, rule) ->
            val reason = Guard.blockReasonNow(app, packageName)
            val installed = Guard.installed(app.packageManager, packageName)
            val rawLabel = Guard.labelOf(app.packageManager, packageName)
            val label = if (installed && rawLabel != packageName) rawLabel
                else "未安装 · ${packageName.substringAfterLast('.')}"
            AppView(
                packageName = packageName,
                label = label,
                blocked = reason != 0,
                status = Guard.blockReasonText(reason),
                allowedText = rule.windowText(),
                nextChangeAt = rule.nextFlip(nowCalendar),
                daysMask = rule.days,
                freezeMode = rule.freeze,
                windows = (0 until rule.windowCount()).map {
                    MinuteWindow(rule.startAt(it), rule.endAt(it))
                },
                gate = rule.gate,
                installed = installed,
                icon = icon(packageName)
            )
        }.sortedWith(
            compareByDescending<AppView> { it.installed && it.blocked }
                .thenByDescending { it.installed }
                .thenBy { it.label.lowercase() }
        )

        val blocked = apps.count { it.installed && it.blocked }
        val hasNext = next > now
        val span = (next - previous).coerceAtLeast(60_000L)
        val progress = if (hasNext) ((now - previous).toFloat() / span).coerceIn(0f, 1f) else 0f
        Phase2Snapshot(
            focus = FocusView(
                remainingMs = if (hasNext) next - now else 0L,
                progress = progress,
                nextChangeAt = next,
                lastChangeAt = previous,
                blockedApps = blocked,
                managedApps = apps.size,
                status = if (blocked > 0) "守卫中" else "全部放行",
                detail = "环进度来自当前计划段；冻结数量来自现有实时判定。"
            ),
            apps = apps,
            capturedAt = now
        )
    }

    /**
     * Find the most recent minute where any scheduled rule changed state. This uses the
     * same Rule/WeeklyRule transition source as enforcement instead of a UI-only clock.
     */
    private fun previousScheduleChange(rules: List<Rule>, now: Calendar): Long {
        if (rules.isEmpty()) return now.timeInMillis - DAY_MS
        val cursor = (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var signature = signature(rules, cursor)
        repeat(8 * 24 * 60) {
            cursor.add(Calendar.MINUTE, -1)
            val previous = signature(rules, cursor)
            if (previous != signature) return cursor.timeInMillis + 60_000L
            signature = previous
        }
        return now.timeInMillis - DAY_MS
    }

    private fun signature(rules: List<Rule>, calendar: Calendar): String {
        val bits = CharArray(rules.size)
        rules.forEachIndexed { index, rule ->
            bits[index] = if (Guard.blocked(rule, calendar)) '1' else '0'
        }
        return String(bits)
    }

    private fun icon(packageName: String): ImageBitmap? {
        val drawable = AppIcons.drawable(app, packageName) ?: return null
        if (drawable is BitmapDrawable) return drawable.bitmap.asImageBitmap()
        val side = 64
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, side, side)
        drawable.draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
