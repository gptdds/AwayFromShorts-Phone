package com.afs.guard

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.core.rules.RuleBackupDocument
import com.afs.core.rules.WeeklyRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.LinkedHashMap

@RunWith(AndroidJUnit4::class)
class RuleBackupRepositoryTest {
    @Test fun exportAndInspectRoundTripDoNotMutateRules() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = RuleStore.exportRules(context)
        val file = File(context.cacheDir, "phase2-rule-backup-test.json")
        file.delete()
        val repository = RuleBackupRepository(context)

        val count = repository.export(Uri.fromFile(file))
        assertTrue(file.isFile)
        val raw = file.readText()
        assertTrue(raw.contains("\"format\": \"afs-rule-backup\""))
        assertTrue(raw.contains("\"variant\": \"device-owner\""))

        val document = repository.read(Uri.fromFile(file))
        assertEquals(count, document.rules.size)
        assertTrue(repository.preview(document).contains("备份包含"))
        assertEquals(before, RuleStore.exportRules(context))
    }

    @Test fun restoreWritesRulesAndExposesTheRestoredFreezeSlot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Assume.assumeTrue(
            "恢复回归需要未启用设备所有者或首次配置宽限期",
            LocalBackup.mayRestore(context)
        )

        val baseline = RuleBackup.snapshot(context)
        val fixture = "com.afs.phase2.backup.fixture"
        val fixtureRule = WeeklyRule.freeze(
            WeeklyRule.ALL_DAYS,
            arrayOf(intArrayOf(21 * 60, 22 * 60))
        )
        val backupDirectory = File(context.filesDir, "rule-backups")
        val backupsBefore = backupDirectory.listFiles().orEmpty().count { it.name.endsWith(".json") }
        val document = RuleBackupDocument(
            "device-owner",
            LinkedHashMap(mapOf(fixture to fixtureRule)),
            emptySet(),
            0L,
            RuleStore.FALLBACK_START,
            RuleStore.FALLBACK_END
        )

        try {
            RuleBackupRepository(context).restore(document, false)

            assertTrue(RuleStore.all(context).containsKey(fixture))
            val restored = FreezeSlotStore.members(
                FreezeSlotStore.all(context).first {
                    FreezeSlotStore.scheduleKey(it.rule) ==
                        FreezeSlotStore.scheduleKey(
                            Rule(fixtureRule.days(), fixtureRule.windows(), true, true)
                        )
                },
                RuleStore.all(context)
            )
            assertTrue(restored.contains(fixture))
            assertTrue(
                "恢复前应创建新的安全备份",
                backupDirectory.listFiles().orEmpty().count { it.name.endsWith(".json") } >
                    backupsBefore
            )
        } finally {
            RuleBackupRepository(context).restore(baseline, true)
            val fixtureKey = FreezeSlotStore.scheduleKey(
                Rule(fixtureRule.days(), fixtureRule.windows(), true, true)
            )
            val rules = RuleStore.all(context)
            FreezeSlotStore.save(
                context,
                FreezeSlotStore.all(context).filterNot {
                    FreezeSlotStore.scheduleKey(it.rule) == fixtureKey &&
                        FreezeSlotStore.members(it, rules).isEmpty()
                }
            )
        }
    }
}
