package com.afs.guard

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.afs.feature.phase2.DiagnosticItem
import com.afs.feature.phase2.DiagnosticsEvaluator
import com.afs.feature.phase2.DiagnosticsFacts
import com.afs.feature.phase2.DiagnosticsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only adapter around the existing diagnostics checks. It never mutates rules or policy. */
class DiagnosticsRepository(private val context: Context) {
    suspend fun load(): DiagnosticsSnapshot = withContext(Dispatchers.IO) {
        val owner = Guard.isOwner(context)
        val health = context.getSharedPreferences("afs_health", Context.MODE_PRIVATE)
        val status = context.getSharedPreferences("afs_status", Context.MODE_PRIVATE)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        var checked = 0
        var mismatched = 0
        var suspensionError: String? = null
        if (owner) {
            try {
                val policy = Guard.dpm(context)
                val admin = Guard.admin(context)
                for (packageName in Guard.enforcedRules(context).keys) {
                    if (!Guard.installed(context.packageManager, packageName)) continue
                    val expected = Guard.blockReasonNow(context, packageName) != 0
                    val actual = policy.isPackageSuspended(admin, packageName) ||
                        policy.isApplicationHidden(admin, packageName)
                    checked++
                    if (expected != actual) mismatched++
                }
            } catch (failure: Exception) {
                suspensionError = failure.message ?: failure.javaClass.simpleName
            }
        }

        val facts = DiagnosticsFacts(
            owner = owner,
            accessibilityConnected = KeywordGuardService.connected(),
            shizukuApplicable = false,
            shizukuConnected = false,
            backgroundHealthy = GuardService.healthy(),
            alarmRequestedAt = health.getLong("alarm_request_at", 0L),
            alarmDueAt = health.getLong("alarm_due", 0L),
            alarmFiredAt = health.getLong("alarm_fired", 0L),
            alarmError = health.getString("alarm_error", "").orEmpty().takeIf { it.isNotBlank() },
            canScheduleExactAlarms = Build.VERSION.SDK_INT < 31 ||
                alarm?.canScheduleExactAlarms() == true,
            backupJobScheduled = backupJobScheduled(),
            bootReceiverEnabled = runCatching {
                context.packageManager.getReceiverInfo(
                    ComponentName(context, BootReceiver::class.java),
                    0
                ).enabled
            }.getOrDefault(false),
            bootAt = health.getLong("boot_at", 0L),
            ignoringBatteryOptimizations =
                power?.isIgnoringBatteryOptimizations(context.packageName) == true,
            notificationsEnabled = manager?.areNotificationsEnabled() == true,
            usageAccess = AppUsageQuota.permission(context),
            checkedApps = checked,
            mismatchedApps = mismatched,
            suspensionCheckError = suspensionError,
            lastSuccessAt = status.getLong("last_success_at", 0L),
            lastError = status.getString("last_error", "").orEmpty().takeIf { it.isNotBlank() },
            appVersion = version?.versionName ?: "—",
            versionCode = when {
                version == null -> 0
                Build.VERSION.SDK_INT >= 28 -> version.longVersionCode.toInt()
                else -> @Suppress("DEPRECATION") version.versionCode
            },
            capturedAt = System.currentTimeMillis()
        )
        DiagnosticsEvaluator.evaluate(facts)
    }

    private fun backupJobScheduled(): Boolean {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return false
        return scheduler.allPendingJobs.any {
            it.service.className == GuardJob::class.java.name
        }
    }
}

data class DiagnosticsUiState(
    val snapshot: DiagnosticsSnapshot? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val actionMessage: String? = null
)
