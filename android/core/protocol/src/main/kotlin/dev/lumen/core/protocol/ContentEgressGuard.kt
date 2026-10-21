package dev.lumen.core.protocol

/**
 * The kinds of content that can leave the device.
 *
 * The distinction matters: a goal the user typed is theirs, and a screen the
 * agent read is the user's too, but only with their consent.
 */
public enum class EgressContent {
    /** Text read from the screen. */
    SCREEN_TEXT,

    /** A frame captured from the screen. */
    SCREEN_FRAME,

    /** The package name of an app, and similar metadata. */
    APP_METADATA,

    /** Something the user typed or said. */
    USER_TEXT,
}

/** A refusal to send content. */
public class EgressDenied(
    public val content: EgressContent,
) : Exception("the content '$content' may not leave the device")

/**
 * The guard on what leaves the device.
 *
 * Screen content is denied by default. It is sent only when the user has
 * enabled spectating, and the check is at the point of sending, so a new
 * outbound path cannot forget it (invariant I11).
 */
public class ContentEgressGuard(
    private val spectatingEnabled: Boolean = false,
    private val additionalAllowed: Set<EgressContent> = emptySet(),
) {

    /** Whether [content] may leave the device. */
    public fun permits(content: EgressContent): Boolean {
        if (content in additionalAllowed) return true
        return when (content) {
            EgressContent.SCREEN_TEXT, EgressContent.SCREEN_FRAME -> spectatingEnabled
            EgressContent.APP_METADATA, EgressContent.USER_TEXT -> true
        }
    }

    /**
     * Checks [content] and throws when it may not leave.
     *
     * @throws EgressDenied
     */
    public fun require(content: EgressContent) {
        if (!permits(content)) throw EgressDenied(content)
    }

    /** A guard with [content] additionally allowed. */
    public fun allowing(vararg content: EgressContent): ContentEgressGuard =
        ContentEgressGuard(spectatingEnabled, additionalAllowed + content)

    /** Whether spectating is on. */
    public val isSpectating: Boolean get() = spectatingEnabled
}
