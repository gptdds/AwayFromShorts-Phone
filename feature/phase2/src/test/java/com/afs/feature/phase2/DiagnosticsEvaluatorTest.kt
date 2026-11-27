package com.afs.feature.phase2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsEvaluatorTest {
    private fun healthyFacts() = DiagnosticsFacts(
        owner = true,
        accessibilityConnected = true,
        shizukuApplicable = false,
        shizukuConnected = false,
        backgroundHealthy = true,
        alarmRequestedAt = 1_700_000_000_000L,
        alarmDueAt = 1_700_000_060_000L,
        alarmFiredAt = 1_700_000_000_000L,
        alarmError = null,
        canScheduleExactAlarms = true,
        backupJobScheduled = true,
        bootReceiverEnabled = true,
        bootAt = 1_700_000_000_000L,
        ignoringBatteryOptimizations = true,
        notificationsEnabled = true,
        usageAccess = true,
        checkedApps = 12,
        mismatchedApps = 0,
        suspensionCheckError = null,
        lastSuccessAt = 1_700_000_000_000L,
        lastError = null,
        appVersion = "1.91.91",
        versionCode = 2391,
        capturedAt = 1_700_000_000_000L
    )

    @Test
    fun healthyFactsProduceNoIssuesAndMarkShizukuInapplicable() {
        val snapshot = DiagnosticsEvaluator.evaluate(healthyFacts())
        assertTrue(snapshot.issues.isEmpty())
        assertEquals(0, snapshot.errorCount)
        val shizuku = snapshot.items.first { it.id == "shizuku" }
        assertEquals(DiagnosticLevel.INFO, shizuku.level)
        assertTrue(shizuku.detail.contains("不适用"))
    }

    @Test
    fun mismatchOffersReapplyWithoutHidingCount() {
        val snapshot = DiagnosticsEvaluator.evaluate(
            healthyFacts().copy(checkedApps = 9, mismatchedApps = 2)
        )
        val item = snapshot.items.first { it.id == "suspension" }
        assertEquals(DiagnosticLevel.ERROR, item.level)
        assertEquals(DiagnosticAction.REAPPLY_RULES, item.action)
        assertTrue(item.detail.contains("9"))
        assertTrue(item.detail.contains("2"))
    }

    @Test
    fun missingExactAlarmPointsToSystemPermissionNotReapply() {
        val snapshot = DiagnosticsEvaluator.evaluate(
            healthyFacts().copy(canScheduleExactAlarms = false)
        )
        val item = snapshot.items.first { it.id == "alarm" }
        assertEquals(DiagnosticLevel.WARNING, item.level)
        assertEquals(DiagnosticAction.OPEN_EXACT_ALARM, item.action)
    }

    @Test
    fun alarmErrorOutranksMissingExactPermission() {
        val snapshot = DiagnosticsEvaluator.evaluate(
            healthyFacts().copy(canScheduleExactAlarms = false, alarmError = "SecurityException")
        )
        val item = snapshot.items.first { it.id == "alarm" }
        assertEquals(DiagnosticLevel.ERROR, item.level)
        assertEquals(DiagnosticAction.OPEN_EXACT_ALARM, item.action)
        assertTrue(item.detail.contains("SecurityException"))
    }

    @Test
    fun everyWarningAndErrorHasExplicitTextAndActionWhereRepairable() {
        val snapshot = DiagnosticsEvaluator.evaluate(
            healthyFacts().copy(
                owner = false,
                accessibilityConnected = false,
                backgroundHealthy = false,
                backupJobScheduled = false,
                ignoringBatteryOptimizations = false,
                notificationsEnabled = false,
                usageAccess = false
            )
        )
        assertTrue(snapshot.items.none { it.detail.isBlank() })
        assertTrue(snapshot.items.first { it.id == "owner" }.action == null)
        assertEquals(DiagnosticAction.OPEN_ACCESSIBILITY, snapshot.items.first { it.id == "accessibility" }.action)
        assertEquals(DiagnosticAction.OPEN_BATTERY_OPTIMIZATION, snapshot.items.first { it.id == "battery" }.action)
    }

    @Test
    fun noRecordTimestampsAreNotRenderedAsNineteenSeventy() {
        val snapshot = DiagnosticsEvaluator.evaluate(
            healthyFacts().copy(alarmRequestedAt = 0L, alarmDueAt = 0L, alarmFiredAt = 0L)
        )
        val item = snapshot.items.first { it.id == "alarm" }
        assertTrue(item.detail.contains("尚无成功排程记录"))
        assertTrue(!item.detail.contains("1970"))
        assertNull(snapshot.items.first { it.id == "last-error" }.action)
    }

    @Test
    fun suspensionReadFailureIsAnErrorInsteadOfAFalseHealthyState() {
        val snapshot = DiagnosticsEvaluator.evaluate(
            healthyFacts().copy(suspensionCheckError = "SecurityException")
        )
        val item = snapshot.items.first { it.id == "suspension" }
        assertEquals(DiagnosticLevel.ERROR, item.level)
        assertEquals(DiagnosticAction.REAPPLY_RULES, item.action)
        assertTrue(item.detail.contains("SecurityException"))
    }
}
