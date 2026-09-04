package me.pngwasi.plume.data

import platform.Foundation.NSUserDefaults

/**
 * Whether the keyboard has ever run with Full Access, as far as the container app can tell.
 *
 * iOS gives the container app no way to ask. `hasFullAccess` exists only on the extension's own
 * `UIInputViewController`, and the keyboard's bundle id cannot be found in `activeInputModes`
 * without a private KVC key that would put the app at risk on review. So the extension reports it
 * instead, through the App Group both processes already share.
 *
 * Only the positive case is reportable. With Full Access off, iOS takes away the network, the
 * pasteboard and a reliably writable group container — the very channel the answer would travel
 * on — so a missing confirmation means "not seen working yet", never "definitely off".
 */
object KeyboardStatus {

    private const val KEY = "full_access_confirmed"

    /** Called by the extension once it knows it has Full Access. */
    fun confirmFullAccess() {
        defaults()?.setBool(true, KEY)
    }

    /** A flag rather than a timestamp: one less interop call in code that cannot be built here. */
    val isConfirmed: Boolean get() = defaults()?.boolForKey(KEY) == true

    private fun defaults() = NSUserDefaults(suiteName = PlumeStores.APP_GROUP)
}
