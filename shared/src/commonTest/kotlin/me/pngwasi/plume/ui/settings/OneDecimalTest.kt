package me.pngwasi.plume.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Temperature was rendered with `String.format` and `java.util.Locale`, which do not exist on
 * Kotlin/Native — in a file compiled for iOS. The replacement has to match what it produced.
 */
class OneDecimalTest {

    @Test
    fun `a whole number keeps its decimal place`() {
        assertEquals("1.0", oneDecimal(1f))
        assertEquals("0.0", oneDecimal(0f))
        assertEquals("2.0", oneDecimal(2f))
    }

    @Test
    fun `a fraction is rounded to one place`() {
        assertEquals("0.7", oneDecimal(0.7f))
        assertEquals("1.3", oneDecimal(1.25f))
        assertEquals("0.1", oneDecimal(0.05f))
    }

    /** The API and the slider both speak dots; a comma from a European locale would not parse. */
    @Test
    fun `the separator is always a dot`() {
        assertEquals(1, oneDecimal(1.5f).count { it == '.' })
        assertEquals(0, oneDecimal(1.5f).count { it == ',' })
    }
}
