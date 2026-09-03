package me.pngwasi.plume.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL

/**
 * The container app and the keyboard extension are separate processes, so both the settings file
 * and the Keychain have to live in a shared App Group. Without one the keyboard would start with
 * default settings and no key, and there would be no way to tell why.
 */
object PlumeStores {

    /** Must match the App Group configured on both targets in Xcode. */
    const val APP_GROUP = "group.me.pngwasi.plume"

    /** Must match the Keychain sharing group on both targets. */
    const val KEYCHAIN_GROUP = "me.pngwasi.plume.shared"

    val settings: SettingsRepository by lazy {
        SettingsRepository(createSettingsDataStore(settingsPathIn(sharedDirectory())))
    }

    val secrets: SecretStore by lazy { IosSecretStore(accessGroup = KEYCHAIN_GROUP) }

    @OptIn(ExperimentalForeignApi::class)
    private fun sharedDirectory(): String {
        val shared: NSURL? = NSFileManager.defaultManager
            .containerURLForSecurityApplicationGroupIdentifier(APP_GROUP)
        shared?.path?.let { return it }

        // Falling back to the app's own container keeps the container app working when the group
        // is missing; the keyboard will simply not see those settings, which is visible rather
        // than silent.
        val documents = NSFileManager.defaultManager.URLsForDirectory(
            directory = platform.Foundation.NSDocumentDirectory,
            inDomains = platform.Foundation.NSUserDomainMask,
        ).firstOrNull() as? NSURL
        return documents?.path ?: NSFileManager.defaultManager.temporaryDirectory.path.orEmpty()
    }
}
