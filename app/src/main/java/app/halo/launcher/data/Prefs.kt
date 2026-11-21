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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import androidx.datastore.preferences.core.Preferences

private val Context.haloStore by preferencesDataStore(name = "halo")

private const val BACKUP_FORMAT = "halo-backup"

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

    // ---- Local backup (a JSON file the user keeps, e.g. on the phone) ----------------------

    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    private fun isBackedUp(name: String) = name == "settings" || name == "layout" || name.startsWith("layout_")

    /**
     * Settings, profiles and every profile's home layout (favourites order, hidden apps, icon
     * styles). The Gemini and TMDB keys are left out: a backup file may be shared or stored anywhere.
     */
    suspend fun exportBackup(appVersion: String): String {
        val p = store.data.first()
        val root = buildJsonObject {
            put("format", BACKUP_FORMAT)
            put("version", 1)
            put("app", appVersion)
            put("created", System.currentTimeMillis())
            put("data", buildJsonObject {
                p.asMap().forEach { (k, v) ->
                    val text = v as? String ?: return@forEach
                    if (!isBackedUp(k.name)) return@forEach
                    val clean = if (k.name == "settings") {
                        json.encodeToString(HaloSettings.serializer(), decodeSettings(text).copy(geminiKey = "", tmdbKey = ""))
                    } else text
                    runCatching { json.parseToJsonElement(clean) }.getOrNull()?.let { put(k.name, it) }
                }
            })
        }
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    /** Checks a backup file; null when it isn't a (readable) Halo backup. */
    fun parseBackup(text: String): Map<String, String>? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        require(root["format"]?.jsonPrimitive?.content == BACKUP_FORMAT)
        val data = root["data"]!!.jsonObject
        val out = LinkedHashMap<String, String>()
        data.forEach { (k, v) ->
            if (!isBackedUp(k)) return@forEach
            // Validate each part before anything is replaced.
            if (k == "settings") json.decodeFromJsonElement(HaloSettings.serializer(), v)
            else json.decodeFromJsonElement(HomeLayout.serializer(), v)
            out[k] = json.encodeToString(JsonElement.serializer(), v)
        }
        out.takeIf { "settings" in it }
    }.getOrNull()

    /** Replaces settings and all layouts with a parsed backup (keeps the current Gemini key). */
    suspend fun restoreBackup(data: Map<String, String>) {
        store.edit { p ->
            val before = decodeSettings(p[settingsKey])
            val keepKey = before.geminiKey
            @Suppress("UNCHECKED_CAST")
            p.asMap().keys.filter { it.name == "layout" || it.name.startsWith("layout_") }
                .forEach { p.remove(it as Preferences.Key<String>) }
            data.forEach { (k, v) -> p[stringPreferencesKey(k)] = v }
            val restored = decodeSettings(p[settingsKey])
            if ((restored.geminiKey.isBlank() && keepKey.isNotBlank()) || (restored.tmdbKey.isBlank() && before.tmdbKey.isNotBlank())) {
                p[settingsKey] = json.encodeToString(
                    HaloSettings.serializer(),
                    restored.copy(
                        geminiKey = restored.geminiKey.ifBlank { keepKey },
                        tmdbKey = restored.tmdbKey.ifBlank { before.tmdbKey },
                    ),
                )
            }
        }
    }

    private fun decodeLayout(s: String?): HomeLayout =
        s?.let { runCatching { json.decodeFromString(HomeLayout.serializer(), it) }.getOrNull() } ?: HomeLayout()

    private fun decodeSettings(s: String?): HaloSettings =
        s?.let { runCatching { json.decodeFromString(HaloSettings.serializer(), it) }.getOrNull() } ?: HaloSettings()
}
