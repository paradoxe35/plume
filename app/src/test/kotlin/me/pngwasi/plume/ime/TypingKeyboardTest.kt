package me.pngwasi.plume.ime

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Choosing where "Keyboard" returns to.
 *
 * The rule is deliberately narrow: the keyboard the user was actually on, or nothing. Landing them
 * in a keyboard they never chose — or worse, back in Plume, which has no keys — is the failure this
 * has to avoid, so anything uncertain resolves to null and the caller shows the picker instead.
 */
@RunWith(RobolectricTestRunner::class)
class TypingKeyboardTest {

    private val plume = ComponentName(
        "me.pngwasi.plume",
        "me.pngwasi.plume.ime.PlumeInputMethodService",
    )

    private val gboard = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
    private val swiftkey = "com.touchtype.swiftkey/com.touchtype.KeyboardService"
    private val voice = "com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService"

    @Test
    fun `the remembered keyboard is used when it is still enabled`() {
        val target = TypingKeyboard.chooseTarget(
            remembered = swiftkey,
            enabled = listOf(voice, gboard, swiftkey, plume.flattenToShortString()),
            plume = plume,
        )

        assertEquals(swiftkey, target)
    }

    /** Whatever the user actually types with — nothing here favours any particular keyboard. */
    @Test
    fun `any third-party keyboard is honoured, not just the stock one`() {
        assertEquals(
            swiftkey,
            TypingKeyboard.chooseTarget(swiftkey, listOf(gboard, swiftkey), plume),
        )
        assertEquals(
            gboard,
            TypingKeyboard.chooseTarget(gboard, listOf(gboard, swiftkey), plume),
        )
    }

    @Test
    fun `a remembered keyboard that was uninstalled or disabled is not used`() {
        assertNull(TypingKeyboard.chooseTarget(swiftkey, listOf(gboard, voice), plume))
    }

    /** Nothing known yet — a fresh install, or the first switch after a restart. */
    @Test
    fun `nothing remembered resolves to null so the caller can ask`() {
        assertNull(TypingKeyboard.chooseTarget(null, listOf(gboard, swiftkey), plume))
        assertNull(TypingKeyboard.chooseTarget("", listOf(gboard, swiftkey), plume))
    }

    /** Returning to Plume would strand the user in a keyboard with no keys. */
    @Test
    fun `plume is never chosen as the destination`() {
        assertNull(
            TypingKeyboard.chooseTarget(
                remembered = plume.flattenToShortString(),
                enabled = listOf(plume.flattenToShortString(), gboard),
                plume = plume,
            ),
        )
        assertNull(
            TypingKeyboard.chooseTarget(
                remembered = plume.flattenToString(),
                enabled = listOf(plume.flattenToString(), gboard),
                plume = plume,
            ),
        )
    }

    @Test
    fun `no enabled keyboards at all resolves to null`() {
        assertNull(TypingKeyboard.chooseTarget(gboard, emptyList(), plume))
    }
}
