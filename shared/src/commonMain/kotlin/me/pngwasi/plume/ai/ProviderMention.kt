package me.pngwasi.plume.ai

/**
 * What a leading `@provider` in the text asked for.
 *
 * Carried over from MyReviser: writing `@openai fix this` sends that one request to a named
 * provider instead of the configured default, which is how you reach a stronger model for a hard
 * sentence without opening settings and changing anything back afterwards.
 */
data class MentionedRequest(
    /** The provider to use, or null to follow the configured routing. */
    val providerId: String?,
    /** The text with the mention removed, which is what should reach the model. */
    val text: String,
)

/**
 * Reads a leading `@provider` mention.
 *
 * Only the first word is considered, and only when it names a provider that actually exists —
 * otherwise `@someone hello` in a real message would be silently eaten. An unknown mention is left
 * alone and treated as ordinary text.
 */
fun parseProviderMention(
    text: String,
    providerIds: Set<String>,
    enabled: Boolean = true,
): MentionedRequest {
    if (!enabled) return MentionedRequest(null, text)

    val trimmed = text.trimStart()
    if (!trimmed.startsWith("@")) return MentionedRequest(null, text)

    val mention = trimmed.drop(1).takeWhile { !it.isWhitespace() }
    if (mention.isEmpty()) return MentionedRequest(null, text)

    val matched = providerIds.firstOrNull { it.equals(mention, ignoreCase = true) }
        ?: return MentionedRequest(null, text)

    val remainder = trimmed.drop(1 + mention.length).trimStart()
    // "@openai" on its own leaves nothing to work on; the mention is not worth honouring, and the
    // caller's own empty check gives a better message than an empty request would.
    if (remainder.isEmpty()) return MentionedRequest(null, text)

    return MentionedRequest(matched, remainder)
}
