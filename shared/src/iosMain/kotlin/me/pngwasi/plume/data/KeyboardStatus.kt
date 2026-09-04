package me.pngwasi.plume.data

import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

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

    private const val KEY = "full_access_confirmed_at"

    /** Called by the extension once it knows it has Full Access. */
    fun confirmFullAccess() {
        defaults()?.setDouble(NSDate().timeIntervalSince1970, KEY)
    }

    /** When the keyboard last confirmed Full Access, or null if it never has. */
    fun fullAccessConfirmedAt(): Double? =
        defaults()?.let { store ->
            val at = store.doubleForKey(KEY)
            at.takeIf { it > 0.0 }
        }

    val isConfirmed: Boolean get() = fullAccessConfirmedAt() != null

    /** Cleared when the user is told to set it up again, so a stale yes cannot mislead. */
    fun forget() {
        defaults()?.removeObjectForKey(KEY)
    }

    private fun defaults() = NSUserDefaults(suiteName = PlumeStores.APP_GROUP)
}
