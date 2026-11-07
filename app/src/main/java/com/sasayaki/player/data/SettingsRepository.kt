package com.sasayaki.player.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class SortMode { TITLE, RECENTLY_ADDED, RECENTLY_PLAYED }

data class Settings(
    val roots: List<String> = emptyList(),
    val theme: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val secondaryColor: Int? = null,
    val preferLossless: Boolean = true,
    val skipBackSec: Int = 10,
    val skipForwardSec: Int = 30,
    val fillPartial: Boolean = true,
    val sort: SortMode = SortMode.TITLE,
    val resumeRewindSec: Int = 3,
    val coverOverrides: Map<String, Ref> = emptyMap(),
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val roots = stringPreferencesKey("roots")
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val secondary = intPreferencesKey("secondary_color")
        val lossless = booleanPreferencesKey("prefer_lossless")
        val back = intPreferencesKey("skip_back")
        val fwd = intPreferencesKey("skip_forward")
        val fill = booleanPreferencesKey("fill_partial")
        val sort = stringPreferencesKey("sort")
        val rewind = intPreferencesKey("resume_rewind")
        val covers = stringPreferencesKey("cover_overrides")
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun read(p: Preferences) = Settings(
        roots = p[K.roots]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(),
        theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
        dynamicColor = p[K.dynamic] ?: false,
        secondaryColor = p[K.secondary],
        preferLossless = p[K.lossless] ?: true,
        skipBackSec = p[K.back] ?: 10,
        skipForwardSec = p[K.fwd] ?: 30,
        fillPartial = p[K.fill] ?: true,
        sort = p[K.sort]?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.TITLE,
        resumeRewindSec = p[K.rewind] ?: 3,
        coverOverrides = p[K.covers]?.let { runCatching { json.decodeFromString<Map<String, Ref>>(it) }.getOrNull() } ?: emptyMap(),
    )

    // Read once synchronously so the first frame already uses the right theme.
    private val initial: Settings = runBlocking { read(context.dataStore.data.first()) }

    val settings: StateFlow<Settings> = context.dataStore.data.map(::read)
        .stateIn(scope, SharingStarted.Eagerly, initial)

    val current: Settings get() = settings.value

    suspend fun update(block: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = block(read(p))
            p[K.roots] = s.roots.joinToString("\n")
            p[K.theme] = s.theme.name
            p[K.dynamic] = s.dynamicColor
            if (s.secondaryColor != null) p[K.secondary] = s.secondaryColor else p.remove(K.secondary)
            p[K.lossless] = s.preferLossless
            p[K.back] = s.skipBackSec
            p[K.fwd] = s.skipForwardSec
            p[K.fill] = s.fillPartial
            p[K.sort] = s.sort.name
            p[K.rewind] = s.resumeRewindSec
            p[K.covers] = json.encodeToString(s.coverOverrides)
        }
    }
}
