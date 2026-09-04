package me.pngwasi.plume.data

/**
 * Process-wide stores. DataStore permits one instance per file, and the tray, the settings window
 * and the hotkey handler all read settings.
 */
object PlumeStores {

    val settings: SettingsRepository by lazy {
        SettingsRepository(createSettingsDataStore(settingsPathIn(plumeConfigDirectory())))
    }

    val secrets: SecretStore by lazy { DesktopSecretStore() }
}
