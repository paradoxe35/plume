package me.pngwasi.plume.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.Foundation.NSCopyingProtocol
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.CFRelease
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.numberWithBool
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setObject
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.noErr

/**
 * API keys in the iOS Keychain.
 *
 * [accessGroup] is what lets the keyboard extension read a key the container app saved: an
 * extension is a separate process with its own container, so without a shared group it sees an
 * empty Keychain and every request fails as unconfigured, with nothing to explain why.
 *
 * `AfterFirstUnlock` rather than `WhenUnlocked` because the keyboard can be asked to work from a
 * lock-screen reply, and a key that cannot be read then looks like Plume losing its settings.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSecretStore(
    private val service: String = "me.pngwasi.plume",
    private val accessGroup: String? = null,
) : SecretStore {

    override fun getKey(providerId: String): String = memScoped {
        val found = alloc<CFTypeRefVar>()
        val query = query(providerId) {
            setObject(NSNumber.numberWithBool(true), forKey = key(kSecReturnData))
        }
        val status = SecItemCopyMatching(query, found.ptr)
        CFRelease(query)
        if (status != noErr.toInt()) return@memScoped ""

        val data = CFBridgingRelease(found.value) as? NSData ?: return@memScoped ""
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString().orEmpty()
    }

    override fun setKey(providerId: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            removeKey(providerId)
            return
        }
        val data = (trimmed as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return

        // Update before add: SecItemAdd returns errSecDuplicateItem for an entry that exists, so a
        // plain add would fail on every save after the first.
        val existing = query(providerId)
        val changes = NSMutableDictionary().apply {
            setObject(data, forKey = key(kSecValueData))
        }
        val changesRef = CFBridgingRetain(changes) as CFDictionaryRef
        val updated = SecItemUpdate(existing, changesRef)
        CFRelease(existing)
        CFRelease(changesRef)
        if (updated == noErr.toInt()) return

        val insert = query(providerId) { setObject(data, forKey = key(kSecValueData)) }
        SecItemAdd(insert, null)
        CFRelease(insert)
    }

    override fun removeKey(providerId: String) {
        val query = query(providerId)
        SecItemDelete(query)
        CFRelease(query)
    }

    override fun hasKey(providerId: String): Boolean = getKey(providerId).isNotEmpty()

    /**
     * A Security constant as a dictionary key.
     *
     * The constants are `CFStringRef` and the dictionary wants an `NSCopying`. They are toll-free
     * bridged to `NSString`, so the pointer only needs re-typing, but `CFBridgingRelease` takes
     * ownership and these are immortal globals: the `CFRetain` leaves the count where it started.
     */
    private fun key(constant: CFStringRef?): NSCopyingProtocol =
        CFBridgingRelease(CFRetain(constant)) as NSCopyingProtocol

    /** Caller owns the result and must [CFRelease] it. */
    private fun query(
        providerId: String,
        extra: NSMutableDictionary.() -> Unit = {},
    ): CFDictionaryRef {
        val dictionary = NSMutableDictionary().apply {
            setObject(key(kSecClassGenericPassword), forKey = key(kSecClass))
            setObject(service, forKey = key(kSecAttrService))
            setObject(secretEntryName(providerId), forKey = key(kSecAttrAccount))
            setObject(key(kSecAttrAccessibleAfterFirstUnlock), forKey = key(kSecAttrAccessible))
            accessGroup?.let { setObject(it, forKey = key(kSecAttrAccessGroup)) }
            extra()
        }
        return CFBridgingRetain(dictionary) as CFDictionaryRef
    }
}
