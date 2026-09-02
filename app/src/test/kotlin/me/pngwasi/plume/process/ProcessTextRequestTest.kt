package me.pngwasi.plume.process

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The contract with the host app. Getting the readonly flag or the result extra wrong silently
 * breaks replacement in every app at once, so it is worth pinning down.
 */
@RunWith(RobolectricTestRunner::class)
class ProcessTextRequestTest {

    private fun intent(text: CharSequence?, readOnly: Boolean? = null): Intent =
        Intent(Intent.ACTION_PROCESS_TEXT).apply {
            text?.let { putExtra(Intent.EXTRA_PROCESS_TEXT, it) }
            readOnly?.let { putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, it) }
        }

    @Test
    fun `an editable selection is parsed as editable`() {
        val request = ProcessTextRequest.from(intent("Bonjour", readOnly = false))

        assertEquals("Bonjour", request?.text)
        assertTrue(request!!.editable)
    }

    @Test
    fun `a read-only selection cannot be replaced`() {
        val request = ProcessTextRequest.from(intent("Bonjour", readOnly = true))

        assertFalse(request!!.editable)
    }

    /** Apps that omit the extra are treated as editable, matching the platform default. */
    @Test
    fun `a missing readonly extra defaults to editable`() {
        assertTrue(ProcessTextRequest.from(intent("Bonjour"))!!.editable)
    }

    @Test
    fun `a styled CharSequence selection is flattened to text`() {
        val styled = android.text.SpannableString("Bonjour")

        assertEquals("Bonjour", ProcessTextRequest.from(intent(styled))?.text)
    }

    @Test
    fun `a null intent yields nothing`() {
        assertNull(ProcessTextRequest.from(null))
    }

    @Test
    fun `an intent with the wrong action yields nothing`() {
        assertNull(ProcessTextRequest.from(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_PROCESS_TEXT, "x")))
    }

    @Test
    fun `a missing selection yields nothing`() {
        assertNull(ProcessTextRequest.from(intent(null)))
    }

    @Test
    fun `a blank selection yields nothing`() {
        assertNull(ProcessTextRequest.from(intent("   \n ")))
    }

    @Test
    fun `the replacement intent carries the text in the extra the host reads`() {
        val result = ProcessTextRequest.replacementIntent("Corrigé")

        assertEquals("Corrigé", result.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
    }
}
