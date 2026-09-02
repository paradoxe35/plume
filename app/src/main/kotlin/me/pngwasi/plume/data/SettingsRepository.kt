package me.pngwasi.plume.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Typed DataStore over a JSON document.
 *
 * `ignoreUnknownKeys` plus defaults on every field means settings written by an older or newer
 * build still load — a missing field falls back rather than wiping the user's configuration.
 */
object SettingsSerializer : Serializer<AppSettings> {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    override val defaultValue: AppSettings
        get() = AppSettings(
            translate = TranslateSettings(favorites = Languages.defaultFavorites()),
        )

    override suspend fun readFrom(input: InputStream): AppSettings =
        try {
            json.decodeFromString(AppSettings.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Could not read Plume settings", e)
        }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(json.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}

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

    suspend fun toggleFavoriteLanguage(code: String) = update { settings ->
        val favorites = settings.translate.favorites
        settings.copy(
            translate = settings.translate.copy(
                favorites = if (favorites.any { it.equals(code, ignoreCase = true) }) {
                    favorites.filterNot { it.equals(code, ignoreCase = true) }
                } else {
                    favorites + code
                },
            ),
        )
    }

    companion object {
        private const val FILE_NAME = "plume_settings.json"

        @Volatile
        private var instance: SettingsRepository? = null

        /**
         * DataStore permits only one active instance per file, and Plume opens settings from three
         * separate entry activities, so the repository is process-wide.
         */
        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): SettingsRepository {
            val store = DataStoreFactory.create(
                serializer = SettingsSerializer,
                produceFile = { File(context.filesDir, FILE_NAME) },
            )
            return SettingsRepository(store)
        }
    }
}
