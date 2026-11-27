package com.afs.feature.phase2

/** Severity is always paired with text in the UI; colour never carries meaning alone. */
enum class DiagnosticLevel { OK, INFO, WARNING, ERROR }

enum class DiagnosticAction {
    REAPPLY_RULES,
    OPEN_ACCESSIBILITY,
    OPEN_BATTERY_OPTIMIZATION,
    OPEN_USAGE_ACCESS,
    OPEN_NOTIFICATIONS,
    OPEN_EXACT_ALARM
}

data class DiagnosticItem(
    val id: String,
    val title: String,
    val detail: String,
    val level: DiagnosticLevel,
    val action: DiagnosticAction? = null
)

/**
 * Raw facts are collected by the platform layer. Evaluation stays pure so severity and repair
 * guidance can be tested without Android services or a running device.
 */
data class DiagnosticsFacts(
    val owner: Boolean,
    val accessibilityConnected: Boolean,
    val shizukuApplicable: Boolean,
    val shizukuConnected: Boolean,
    val backgroundHealthy: Boolean,
    val alarmRequestedAt: Long,
    val alarmDueAt: Long,
    val alarmFiredAt: Long,
    val alarmError: String?,
    val canScheduleExactAlarms: Boolean,
    val backupJobScheduled: Boolean,
    val bootReceiverEnabled: Boolean,
    val bootAt: Long,
    val ignoringBatteryOptimizations: Boolean,
    val notificationsEnabled: Boolean,
    val usageAccess: Boolean,
    val checkedApps: Int,
    val mismatchedApps: Int,
    val suspensionCheckError: String?,
    val lastSuccessAt: Long,
    val lastError: String?,
    val appVersion: String,
    val versionCode: Int,
    val capturedAt: Long
)

data class DiagnosticsSnapshot(
    val items: List<DiagnosticItem>,
    val capturedAt: Long
) {
    val errorCount: Int get() = items.count { it.level == DiagnosticLevel.ERROR }
    val warningCount: Int get() = items.count { it.level == DiagnosticLevel.WARNING }
    val okCount: Int get() = items.count { it.level == DiagnosticLevel.OK }
    val issues: List<DiagnosticItem>
        get() = items.filter {
            it.level == DiagnosticLevel.ERROR || it.level == DiagnosticLevel.WARNING
        }
    val headline: String
        get() = when {
            errorCount > 0 -> "发现 $errorCount 个需要处理的问题"
            warningCount > 0 -> "运行中，$warningCount 项建议检查"
            else -> "系统状态正常"
        }
}

object DiagnosticsEvaluator {
    fun evaluate(facts: DiagnosticsFacts): DiagnosticsSnapshot {
        val rows = ArrayList<DiagnosticItem>(14)

        rows += DiagnosticItem(
            id = "owner",
            title = "设备所有者",
            detail = if (facts.owner) {
                "已授权，系统冻结与锁定可用。"
            } else {
                "尚未取得设备所有者，系统冻结不可用；需要通过部署包授权。"
            },
            level = if (facts.owner) DiagnosticLevel.OK else DiagnosticLevel.ERROR
        )

        rows += DiagnosticItem(
            id = "accessibility",
            title = "关键词无障碍",
            detail = if (facts.accessibilityConnected) {
                "服务已连接；关键词匹配在本机完成。"
            } else {
                "服务未连接。此服务不影响设备所有者的系统冻结。"
            },
            level = if (facts.accessibilityConnected) DiagnosticLevel.OK else DiagnosticLevel.WARNING,
            action = if (facts.accessibilityConnected) null else DiagnosticAction.OPEN_ACCESSIBILITY
        )

        rows += if (facts.shizukuApplicable) {
            DiagnosticItem(
                id = "shizuku",
                title = "Shizuku 执行器",
                detail = if (facts.shizukuConnected) "已连接，可执行受授权命令。" else "尚未连接。",
                level = if (facts.shizukuConnected) DiagnosticLevel.OK else DiagnosticLevel.WARNING
            )
        } else {
            DiagnosticItem(
                id = "shizuku",
                title = "Shizuku 执行器",
                detail = "不适用：当前是 Device Owner 版本，不依赖 Shizuku。",
                level = DiagnosticLevel.INFO
            )
        }

        rows += DiagnosticItem(
            id = "guard-service",
            title = "后台守护",
            detail = if (facts.backgroundHealthy) {
                "常驻守护已连接，最近两分钟内完成巡检。"
            } else {
                "没有收到近期巡检验证；请检查后台限制并重新应用规则。"
            },
            level = if (facts.backgroundHealthy) DiagnosticLevel.OK else DiagnosticLevel.ERROR,
            action = if (facts.backgroundHealthy) null else DiagnosticAction.REAPPLY_RULES
        )

        val alarmDetail = when {
            facts.alarmRequestedAt <= 0L -> "尚无成功排程记录，可重新应用当前规则。"
            !facts.canScheduleExactAlarms ->
                "系统未允许精确闹钟，切换可能延迟。计划 ${stamp(facts.alarmDueAt)}，最近回调 ${stamp(facts.alarmFiredAt)}。"
            else ->
                "排程已接受：${stamp(facts.alarmRequestedAt)}；计划 ${stamp(facts.alarmDueAt)}，最近回调 ${stamp(facts.alarmFiredAt)}。"
        }
        rows += DiagnosticItem(
            id = "alarm",
            title = "定时调度",
            detail = listOfNotNull(
                alarmDetail,
                facts.alarmError?.takeIf { it.isNotBlank() }?.let { "最近错误：$it" }
            ).joinToString("\n"),
            level = when {
                !facts.alarmError.isNullOrBlank() -> DiagnosticLevel.ERROR
                facts.alarmRequestedAt <= 0L || !facts.canScheduleExactAlarms -> DiagnosticLevel.WARNING
                else -> DiagnosticLevel.OK
            },
            action = when {
                !facts.canScheduleExactAlarms -> DiagnosticAction.OPEN_EXACT_ALARM
                facts.alarmRequestedAt <= 0L || !facts.alarmError.isNullOrBlank() -> DiagnosticAction.REAPPLY_RULES
                else -> null
            }
        )

        rows += DiagnosticItem(
            id = "backup-job",
            title = "后备任务",
            detail = if (facts.backupJobScheduled) {
                "系统 JobScheduler 队列中存在守护任务。"
            } else {
                "系统队列中没有守护任务，重新应用规则可补排。"
            },
            level = if (facts.backupJobScheduled) DiagnosticLevel.OK else DiagnosticLevel.WARNING,
            action = if (facts.backupJobScheduled) null else DiagnosticAction.REAPPLY_RULES
        )

        rows += DiagnosticItem(
            id = "boot",
            title = "开机恢复",
            detail = if (facts.bootReceiverEnabled) {
                "接收器已注册；最近开机回调 ${stamp(facts.bootAt)}。"
            } else {
                "开机恢复接收器被禁用，重启后可能不会恢复规则。"
            },
            level = if (facts.bootReceiverEnabled) DiagnosticLevel.OK else DiagnosticLevel.ERROR
        )

        rows += DiagnosticItem(
            id = "battery",
            title = "电池优化",
            detail = if (facts.ignoringBatteryOptimizations) {
                "已忽略电池优化；厂商额外后台限制仍需分别检查。"
            } else {
                "电池优化可能压缩后台守护的存活时间。"
            },
            level = if (facts.ignoringBatteryOptimizations) DiagnosticLevel.OK else DiagnosticLevel.WARNING,
            action = if (facts.ignoringBatteryOptimizations) null else DiagnosticAction.OPEN_BATTERY_OPTIMIZATION
        )

        rows += DiagnosticItem(
            id = "notifications",
            title = "通知权限",
            detail = if (facts.notificationsEnabled) "通知可用，能显示守护状态。" else "通知被关闭，守护状态可能不可见。",
            level = if (facts.notificationsEnabled) DiagnosticLevel.OK else DiagnosticLevel.WARNING,
            action = if (facts.notificationsEnabled) null else DiagnosticAction.OPEN_NOTIFICATIONS
        )

        rows += DiagnosticItem(
            id = "usage",
            title = "使用情况访问",
            detail = if (facts.usageAccess) {
                "已授权，时间限额与亮屏统计可以计时。"
            } else {
                "未授权，时间限额与亮屏统计无法正常计时。"
            },
            level = if (facts.usageAccess) DiagnosticLevel.OK else DiagnosticLevel.WARNING,
            action = if (facts.usageAccess) null else DiagnosticAction.OPEN_USAGE_ACCESS
        )

        rows += DiagnosticItem(
            id = "suspension",
            title = "系统冻结一致性",
            detail = when {
                !facts.owner -> "没有设备管理权限，无法核对系统冻结状态。"
                !facts.suspensionCheckError.isNullOrBlank() ->
                    "无法读取系统冻结状态：${facts.suspensionCheckError}"
                facts.mismatchedApps > 0 ->
                    "已核对 ${facts.checkedApps} 个已安装应用，${facts.mismatchedApps} 个与规则不一致。"
                else -> "已核对 ${facts.checkedApps} 个已安装应用，系统状态与规则一致。"
            },
            level = when {
                !facts.owner -> DiagnosticLevel.INFO
                !facts.suspensionCheckError.isNullOrBlank() -> DiagnosticLevel.ERROR
                facts.mismatchedApps > 0 -> DiagnosticLevel.ERROR
                else -> DiagnosticLevel.OK
            },
            action = if (facts.owner && (!facts.suspensionCheckError.isNullOrBlank() || facts.mismatchedApps > 0))
                DiagnosticAction.REAPPLY_RULES else null
        )

        rows += DiagnosticItem(
            id = "last-success",
            title = "最近一次完整执行",
            detail = stamp(facts.lastSuccessAt),
            level = DiagnosticLevel.INFO
        )

        rows += DiagnosticItem(
            id = "last-error",
            title = "最近一次执行错误",
            detail = facts.lastError?.takeIf { it.isNotBlank() } ?: "没有已记录的执行失败。",
            level = if (facts.lastError.isNullOrBlank()) DiagnosticLevel.OK else DiagnosticLevel.WARNING,
            action = if (facts.lastError.isNullOrBlank()) null else DiagnosticAction.REAPPLY_RULES
        )

        rows += DiagnosticItem(
            id = "version",
            title = "应用版本",
            detail = "${facts.appVersion}（${facts.versionCode}）",
            level = DiagnosticLevel.INFO
        )

        return DiagnosticsSnapshot(rows, facts.capturedAt)
    }

    private fun stamp(value: Long): String = if (value <= 0L) "暂无记录" else formatClock(value)
}
