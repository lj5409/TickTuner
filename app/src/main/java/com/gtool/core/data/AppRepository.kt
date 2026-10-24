package com.gtool.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gtool.core.music.Note
import com.gtool.core.music.Tuning
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.gtoolStore by preferencesDataStore(name = "gtool")

@Serializable
private data class TuningDto(
    val id: String,
    val name: String,
    val notes: List<String>
)

/**
 * 应用唯一的数据仓库：设置 + 自定义调弦。
 * 通过 GtoolApplication.repository 获取。
 */
class AppRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private object Keys {
        val SETTINGS = stringPreferencesKey("settings_json")
        val CUSTOM_TUNINGS = stringPreferencesKey("custom_tunings_json")
    }

    val state: Flow<AppState> = context.gtoolStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            AppState(
                settings = decodeSettings(prefs[Keys.SETTINGS]),
                customTunings = decodeTunings(prefs[Keys.CUSTOM_TUNINGS])
            )
        }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.gtoolStore.edit { prefs ->
            val current = decodeSettings(prefs[Keys.SETTINGS])
            prefs[Keys.SETTINGS] = json.encodeToString(AppSettings.serializer(), transform(current))
        }
    }

    suspend fun setActiveTuning(id: String) = updateSettings { it.copy(lastTuningId = id) }

    suspend fun upsertTuning(tuning: Tuning) {
        context.gtoolStore.edit { prefs ->
            val current = decodeTunings(prefs[Keys.CUSTOM_TUNINGS]).toMutableList()
            val index = current.indexOfFirst { it.id == tuning.id }
            val custom = tuning.copy(builtIn = false)
            if (index >= 0) current[index] = custom else current.add(custom)
            prefs[Keys.CUSTOM_TUNINGS] = encodeTunings(current)
        }
    }

    suspend fun deleteTuning(id: String) {
        context.gtoolStore.edit { prefs ->
            val current = decodeTunings(prefs[Keys.CUSTOM_TUNINGS]).filterNot { it.id == id }
            prefs[Keys.CUSTOM_TUNINGS] = encodeTunings(current)
        }
    }

    private fun decodeSettings(raw: String?): AppSettings =
        if (raw.isNullOrBlank()) AppSettings()
        else runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrElse { AppSettings() }

    private fun decodeTunings(raw: String?): List<Tuning> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer, raw).mapNotNull { dto ->
                val notes = dto.notes.mapNotNull { Note.parse(it) }
                if (notes.size < 4) null
                else Tuning(id = dto.id, name = dto.name, strings = notes, builtIn = false)
            }
        }.getOrElse { emptyList() }
    }

    private fun encodeTunings(tunings: List<Tuning>): String =
        json.encodeToString(
            ListSerializer,
            tunings.map { TuningDto(it.id, it.name, it.strings.map { note -> note.name() }) }
        )

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(TuningDto.serializer())
    }
}
