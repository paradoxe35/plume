package me.pngwasi.plume.ai

/**
 * Models sometimes wrap a reply in a code fence or quotation marks despite being told not to.
 * Since the output is pasted straight back into the user's text field, that wrapping has to go.
 *
 * Deliberately conservative: it only unwraps when the delimiters enclose the *whole* reply, so a
 * genuine code snippet or a quoted sentence inside longer prose survives untouched.
 */
object ResponseCleaner {

    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        text = stripCodeFence(text)
        text = stripWrappingQuotes(text)
        return text.trim()
    }

    private fun stripCodeFence(text: String): String {
        if (!text.startsWith("```")) return text
        val lines = text.lines()
        if (lines.size < 2) return text
        val closing = lines.indexOfLast { it.trimEnd() == "```" }
        // Only unwrap a fence that closes on the final line — otherwise it is content, not wrapping.
        if (closing <= 0 || closing != lines.lastIndex) return text
        return lines.subList(1, closing).joinToString("\n")
    }

    private fun stripWrappingQuotes(text: String): String {
        if (text.length < 2) return text
        val pairs = listOf(
            '"' to '"',
            '\'' to '\'',
            '“' to '”', // “ ”
            '«' to '»', // « » — French guillemets
        )
        for ((open, close) in pairs) {
            if (text.first() != open || text.last() != close) continue
            val inner = text.substring(1, text.length - 1)
            // A closing delimiter in the middle means the quotes are part of the content.
            if (inner.contains(close) && open != close) continue
            if (open == close && inner.contains(open)) continue
            return inner.trim()
        }
        return text
    }
}
