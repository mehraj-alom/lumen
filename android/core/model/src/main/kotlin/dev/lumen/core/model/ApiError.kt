package dev.lumen.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The stable error codes of the system.
 *
 * These strings appear in the IPC contract and in the HTTP API. They are part
 * of the public surface: a code may be added, but an existing code never
 * changes meaning (`API_SPEC.md` §1).
 */
@Serializable
public enum class ErrorCode {
    @SerialName("not_found")
    NOT_FOUND,

    @SerialName("invalid_state")
    INVALID_STATE,

    @SerialName("denied_by_policy")
    DENIED_BY_POLICY,

    @SerialName("approval_required")
    APPROVAL_REQUIRED,

    @SerialName("approval_denied")
    APPROVAL_DENIED,

    @SerialName("approval_expired")
    APPROVAL_EXPIRED,

    @SerialName("quota_exceeded")
    QUOTA_EXCEEDED,

    @SerialName("aborted")
    ABORTED,

    @SerialName("accessibility_unavailable")
    ACCESSIBILITY_UNAVAILABLE,

    @SerialName("idle_gate_blocked")
    IDLE_GATE_BLOCKED,

    @SerialName("grounding_unavailable")
    GROUNDING_UNAVAILABLE,

    @SerialName("unsettled")
    UNSETTLED,

    @SerialName("brain_unavailable")
    BRAIN_UNAVAILABLE,

    @SerialName("sandbox_violation")
    SANDBOX_VIOLATION,

    @SerialName("internal")
    INTERNAL,
}

/**
 * A failure, with a stable machine code and a human-readable message.
 *
 * Every failure the system surfaces is one of these, so the console, the
 * notification and the trace all describe the same event the same way.
 */
@Serializable
public data class ApiError(
    val code: ErrorCode,
    val message: String,
    val detail: Map<String, String> = emptyMap(),
) {
    init {
        require(message.isNotBlank()) { "an error message must not be blank" }
    }

    /** The wire form of the code, for example `quota_exceeded`. */
    public val codeName: String
        get() = code.name.lowercase()

    public companion object {
        /** A short error with no detail. */
        public fun of(code: ErrorCode, message: String): ApiError = ApiError(code, message)
    }
}
