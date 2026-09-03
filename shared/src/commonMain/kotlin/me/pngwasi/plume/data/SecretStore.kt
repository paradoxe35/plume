package me.pngwasi.plume.data

/**
 * Encrypted storage for API keys, kept separate from [AppSettings] so that exporting or logging
 * settings can never leak a credential.
 *
 * An interface rather than an `expect class` because there is nothing shared to inherit: every
 * platform delegates to a different OS facility — the Android Keystore, the Apple Keychain, DPAPI
 * on Windows, the Secret Service on Linux.
 */
interface SecretStore {
    fun getKey(providerId: String): String
    fun setKey(providerId: String, value: String)
    fun removeKey(providerId: String)
    fun hasKey(providerId: String): Boolean
}

/** Entry name shared by every backend, so keys stay findable across versions. */
internal fun secretEntryName(providerId: String): String = "key_${providerId.lowercase()}"
