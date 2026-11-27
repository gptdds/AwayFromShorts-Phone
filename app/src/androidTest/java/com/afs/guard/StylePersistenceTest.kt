package com.afs.guard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.afs.guard.style.StyleManager
import com.afs.guard.style.EffectQualityManager
import com.afs.style.manager.VisualPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Invoked in two separate target processes by run-style-persistence-device.ps1. */
@RunWith(AndroidJUnit4::class)
class StylePersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val backup get() = File(context.filesDir, "visual-restart-qa.json")
    private fun ready() = runBlocking { withTimeout(10000) {
        StylePreferencesBridge.get(context).ready.first { it }
    } }
    private fun flush() = runBlocking { withTimeout(10000) { StylePreferencesBridge.get(context).awaitWrites() } }
    @Test fun writeBeforeRestart() {
        ready()
        if (!backup.exists()) backup.writeText(JSONObject().put("style", StyleManager.selected(context))
            .put("quality", EffectQualityManager.preference(context)).toString())
        instrumentation.runOnMainSync {
            StyleManager.select(context, "fluidgradient")
            EffectQualityManager.setPreference(context, "low")
        }
        flush()
        val stored = runBlocking { StylePreferencesBridge.get(context).repository.read() }
        assertEquals(VisualPreferences("fluidgradient", "low"), stored)
        assertTrue(File(context.filesDir, "datastore/visual-style-v1.preferences_pb").exists())
        assertNull(StylePreferencesBridge.get(context).error.value)
        assertTrue(LocalBackup.key("afs_ui", "style_id"))
        assertTrue(LocalBackup.key("afs_ui", "effect_quality"))
    }
    @Test fun readAfterRestartAndRestore() {
        ready()
        Assume.assumeTrue("需要先运行 writeBeforeRestart，或通过重启脚本分阶段执行", backup.exists())
        try {
            assertEquals("fluidgradient", StyleManager.selected(context))
            assertEquals("low", EffectQualityManager.preference(context))
            val persisted = runBlocking { StylePreferencesBridge.get(context).repository.initialize(VisualPreferences("paper", "high")) }
            assertEquals(VisualPreferences("fluidgradient", "low"), persisted)
        } finally {
            val original = JSONObject(backup.readText())
            instrumentation.runOnMainSync {
                StyleManager.select(context, original.getString("style"))
                EffectQualityManager.setPreference(context, original.getString("quality"))
            }
            flush(); backup.delete()
        }
    }
}
