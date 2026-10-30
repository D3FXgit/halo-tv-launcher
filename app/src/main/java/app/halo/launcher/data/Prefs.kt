package app.halo.launcher.data

import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.haloStore by preferencesDataStore(name = "halo")

/** Persists the home layout, icon customisations and settings as JSON. */
class Prefs(context: Context) {
    private val store = context.applicationContext.haloStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val settingsKey = stringPreferencesKey("settings")

    /** The main profile keeps the original key, so existing layouts carry over. */
    private fun layoutKey(profile: String) =
        stringPreferencesKey(if (profile == MAIN_PROFILE) "layout" else "layout_$profile")

    fun layout(profile: String): Flow<HomeLayout> = store.data
        .map { decodeLayout(it[layoutKey(profile)]) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    val settings: Flow<HaloSettings> = store.data
        .map { decodeSettings(it[settingsKey]) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    suspend fun updateLayout(profile: String, transform: (HomeLayout) -> HomeLayout) {
        val key = layoutKey(profile)
        store.edit { p ->
            p[key] = json.encodeToString(HomeLayout.serializer(), transform(decodeLayout(p[key])))
        }
    }

    suspend fun deleteLayout(profile: String) {
        if (profile == MAIN_PROFILE) return
        store.edit { it.remove(layoutKey(profile)) }
    }

    suspend fun updateSettings(transform: (HaloSettings) -> HaloSettings) {
        store.edit { p ->
            p[settingsKey] = json.encodeToString(HaloSettings.serializer(), transform(decodeSettings(p[settingsKey])))
        }
    }

    private fun decodeLayout(s: String?): HomeLayout =
        s?.let { runCatching { json.decodeFromString(HomeLayout.serializer(), it) }.getOrNull() } ?: HomeLayout()

    private fun decodeSettings(s: String?): HaloSettings =
        s?.let { runCatching { json.decodeFromString(HaloSettings.serializer(), it) }.getOrNull() } ?: HaloSettings()
}
