package me.pngwasi.plume.data

import android.content.Context
import androidx.core.content.edit
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException

/**
 * androidx.security:security-crypto (EncryptedSharedPreferences) was deprecated in April 2025, so
 * this uses the successor path Google points at: Tink AEAD with the keyset wrapped by a master key
 * in the Android Keystore. Ciphertext lands in an ordinary SharedPreferences file; the key material
 * never leaves the Keystore.
 */
class AndroidSecretStore(context: Context) : SecretStore {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE)

    // Tink initialisation touches the Keystore, so it is deferred off the activity's critical path.
    private val aead: Aead? by lazy { runCatching { buildAead() }.getOrNull() }

    override fun getKey(providerId: String): String {
        val stored = prefs.getString(entry(providerId), null) ?: return ""
        val cipher = aead ?: return ""
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            String(cipher.decrypt(raw, providerId.toByteArray()), Charsets.UTF_8)
        }.getOrElse {
            // Keystore reset (device restore, key invalidation) leaves undecryptable blobs behind.
            prefs.edit { remove(entry(providerId)) }
            ""
        }
    }

    override fun setKey(providerId: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            removeKey(providerId)
            return
        }
        val cipher = aead ?: return
        runCatching {
            val encrypted = cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8), providerId.toByteArray())
            prefs.edit { putString(entry(providerId), Base64.encodeToString(encrypted, Base64.NO_WRAP)) }
        }
    }

    override fun removeKey(providerId: String) {
        prefs.edit { remove(entry(providerId)) }
    }

    override fun hasKey(providerId: String): Boolean = prefs.contains(entry(providerId))

    private fun entry(providerId: String) = secretEntryName(providerId)

    @Throws(GeneralSecurityException::class)
    private fun buildAead(): Aead {
        AeadConfig.register()
        val handle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        // getPrimitive(Class) is deprecated; the configuration argument replaces the global registry.
        return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private companion object {
        const val SECRETS_PREFS = "plume_secrets"
        const val KEYSET_PREFS = "plume_keyset_prefs"
        const val KEYSET_NAME = "plume_master_keyset"
        const val MASTER_KEY_URI = "android-keystore://plume_master_key"
    }
}
