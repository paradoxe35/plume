package me.pngwasi.plume.ime

import android.content.ComponentName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Matching an input-method id against our component.
 *
 * The system writes ids with `flattenToShortString`, which abbreviates a class name that shares the
 * package prefix. Comparing that against `flattenToString` output silently never matches — which
 * left the setup checklist stuck on "not done" no matter what the user did in Android settings.
 */
@RunWith(RobolectricTestRunner::class)
class KeyboardComponentTest {

    private val target = ComponentName(
        "me.pngwasi.plume",
        "me.pngwasi.plume.ime.PlumeInputMethodService",
    )

    /** The form the platform actually stores and returns. */
    @Test
    fun `the short form the system uses is recognised`() {
        assertTrue(KeyboardComponent.matches("me.pngwasi.plume/.ime.PlumeInputMethodService", target))
    }

    @Test
    fun `the fully qualified form is recognised`() {
        assertTrue(
            KeyboardComponent.matches(
                "me.pngwasi.plume/me.pngwasi.plume.ime.PlumeInputMethodService",
                target,
            ),
        )
    }

    @Test
    fun `another keyboard is not mistaken for ours`() {
        assertFalse(
            KeyboardComponent.matches(
                "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
                target,
            ),
        )
    }

    @Test
    fun `another service in our own package is not a match`() {
        assertFalse(KeyboardComponent.matches("me.pngwasi.plume/.ime.SomethingElse", target))
    }

    @Test
    fun `a package that merely starts with ours is not a match`() {
        assertFalse(
            KeyboardComponent.matches("me.pngwasi.plumeria/.ime.PlumeInputMethodService", target),
        )
    }

    @Test
    fun `missing or malformed ids are not matches`() {
        assertFalse(KeyboardComponent.matches(null, target))
        assertFalse(KeyboardComponent.matches("", target))
        assertFalse(KeyboardComponent.matches("no-slash-here", target))
    }
}
