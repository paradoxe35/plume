package me.pngwasi.plume.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Typed DataStore over a JSON document.
 *
 * `ignoreUnknownKeys` plus defaults on every field means settings written by an older or newer
 * build still load — a missing field falls back rather than wiping the user's configuration.
 *
 * Okio-backed rather than file-backed: `produceFile` deals in `java.io.File`, which does not exist
 * outside the JVM.
 */
object SettingsSerializer : OkioSerializer<AppSettings> {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    override val defaultValue: AppSettings
        get() = AppSettings(
            translate = TranslateSettings(favorites = Languages.defaultFavorites()),
        )

    override suspend fun readFrom(source: BufferedSource): AppSettings =
        try {
            json.decodeFromString(AppSettings.serializer(), source.readUtf8())
        } catch (e: SerializationException) {
            throw CorruptionException("Could not read Plume settings", e)
        }

    override suspend fun writeTo(t: AppSettings, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(AppSettings.serializer(), t))
    }
}

const val SETTINGS_FILE_NAME = "plume_settings.json"

fun settingsPathIn(directory: String): Path = directory.toPath() / SETTINGS_FILE_NAME

/**
 * A corrupt file resets to defaults rather than failing every read forever. API keys live in
 * [SecretStore], so what a reset costs is provider choices, not credentials.
 */
fun createSettingsDataStore(
    path: Path,
    fileSystem: FileSystem = FileSystem.SYSTEM,
): DataStore<AppSettings> = DataStoreFactory.create(
    storage = OkioStorage(fileSystem, SettingsSerializer, producePath = { path }),
    corruptionHandler = ReplaceFileCorruptionHandler { SettingsSerializer.defaultValue },
)

class SettingsRepository(private val store: DataStore<AppSettings>) {

    val settings: Flow<AppSettings> = store.data

    suspend fun current(): AppSettings = store.data.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData(transform)
    }

    suspend fun setDefaultProvider(id: String) = update { it.copy(defaultProvider = id) }

    suspend fun setActionProvider(action: Action, providerId: String?) = update {
        it.withOverride(action, providerId)
    }

    suspend fun putProvider(id: String, config: ProviderConfig) = update {
        it.copy(providers = it.providers + (id to config))
    }

    /**
     * Deleting a provider must not leave the default or either override pointing at nothing, so
     * every reference to it is repaired in the same write.
     */
    suspend fun deleteProvider(id: String) = update { settings ->
        if (BuiltInProviders.isBuiltIn(id)) return@update settings
        val remaining = settings.providers - id
        val fallback = remaining.keys.firstOrNull() ?: BuiltInProviders.OPENAI
        settings.copy(
            providers = remaining,
            defaultProvider = if (settings.defaultProvider == id) fallback else settings.defaultProvider,
            reviseProvider = settings.reviseProvider?.takeIf { it != id },
            translateProvider = settings.translateProvider?.takeIf { it != id },
        )
    }

    suspend fun recordTranslationTarget(code: String) = update {
        it.copy(translate = it.translate.copy(recents = it.translate.recents.withRecentTarget(code)))
    }

    /**
     * Pins or unpins a language.
     *
     * Unpinning also drops it from recents. The picker sorts by recency, so a leftover entry would
     * keep ordering a language the user had just removed.
     */
    suspend fun toggleFavoriteLanguage(code: String) = update { settings ->
        val favorites = settings.translate.favorites
        val pinned = favorites.any { it.equals(code, ignoreCase = true) }
        settings.copy(
            translate = settings.translate.copy(
                favorites = if (pinned) {
                    favorites.filterNot { it.equals(code, ignoreCase = true) }
                } else {
                    favorites + code
                },
                recents = if (pinned) {
                    settings.translate.recents.filterNot { it.equals(code, ignoreCase = true) }
                } else {
                    settings.translate.recents
                },
            ),
        )
    }
}
