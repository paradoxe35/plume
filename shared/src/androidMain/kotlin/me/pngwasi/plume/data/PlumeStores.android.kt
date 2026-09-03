package me.pngwasi.plume.data

import android.content.Context

/**
 * DataStore permits one active instance per file, and Plume opens settings from three separate
 * entry activities plus the keyboard service, so the repository is process-wide.
 */
object PlumeStores {

    @Volatile
    private var repository: SettingsRepository? = null

    @Volatile
    private var secrets: SecretStore? = null

    fun settings(context: Context): SettingsRepository =
        repository ?: synchronized(this) {
            repository ?: SettingsRepository(
                createSettingsDataStore(settingsPathIn(context.applicationContext.filesDir.path)),
            ).also { repository = it }
        }

    fun secrets(context: Context): SecretStore =
        secrets ?: synchronized(this) {
            secrets ?: AndroidSecretStore(context.applicationContext).also { secrets = it }
        }
}
