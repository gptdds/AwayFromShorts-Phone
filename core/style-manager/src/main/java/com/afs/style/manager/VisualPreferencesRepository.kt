package com.afs.style.manager

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.visualPreferences by preferencesDataStore(name = "visual-style-v1")

data class VisualPreferences(val style: String = "paper", val quality: String = "auto")

/** DataStore owns durable values; initialization/migration and writes are atomic edits. */
class VisualPreferencesRepository(context: Context) {
    private val store = context.applicationContext.visualPreferences
    private val style = stringPreferencesKey("style_id")
    private val quality = stringPreferencesKey("effect_quality")
    private val migrated = booleanPreferencesKey("legacy_imported")
    val values = store.data.map { VisualPreferences(it[style] ?: "paper", it[quality] ?: "auto") }
    suspend fun initialize(legacy: VisualPreferences): VisualPreferences {
        val saved = store.edit {
            if (it[migrated] != true) {
                it[style] = legacy.style
                it[quality] = legacy.quality
                it[migrated] = true
            }
        }
        return VisualPreferences(saved[style] ?: "paper", saved[quality] ?: "auto")
    }
    suspend fun save(value: VisualPreferences) {
        store.edit { it[style] = value.style; it[quality] = value.quality }
    }
    suspend fun read(): VisualPreferences = values.first()
}
