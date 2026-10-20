package dev.lumen.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The action classes that require a human decision before they are dispatched.
 *
 * The classification is deterministic and runs on-device. A brain model may
 * make a step *more* sensitive than this, never less (`ARCH.md` §8).
 */
@Serializable
public enum class RiskClass {
    /** No approval needed. */
    @SerialName("none")
    NONE,

    /** Spends money: a purchase, a transfer, a subscription, a tip. */
    @SerialName("payment")
    PAYMENT,

    /** Publishes or sends: a post, a comment, an email, a message to another person. */
    @SerialName("publish")
    PUBLISH,

    /** Destroys: a delete, an uninstall, a cancellation, a revocation. */
    @SerialName("delete")
    DELETE,

    /** Handles a secret: a password, a one-time code, a card number. */
    @SerialName("credential")
    CREDENTIAL,

    /** Grants a permission to the agent or to the target app. */
    @SerialName("permission")
    PERMISSION,

    /** Reaches a person: a call, a meeting, a direct message. */
    @SerialName("communication")
    COMMUNICATION,

    /** Changes the account: the email, the password, the recovery route, two-factor. */
    @SerialName("account")
    ACCOUNT,

    /** Accepts a legal or contractual term. */
    @SerialName("legal")
    LEGAL,

    /** Creates an irreversible external state that is not covered above. */
    @SerialName("irreversible")
    IRREVERSIBLE,

    /** The user pinned this step to always ask. */
    @SerialName("user_pinned")
    USER_PINNED,
    ;

    /** Whether a step of this class must stop and ask. */
    public val requiresApproval: Boolean
        get() = this != NONE

    /** The human-readable class label, shown on the approval card. */
    public val label: String
        get() = when (this) {
            NONE -> "No approval"
            PAYMENT -> "Payment"
            PUBLISH -> "Publish or send"
            DELETE -> "Delete"
            CREDENTIAL -> "Credential"
            PERMISSION -> "Permission"
            COMMUNICATION -> "Communication"
            ACCOUNT -> "Account change"
            LEGAL -> "Legal acceptance"
            IRREVERSIBLE -> "Irreversible action"
            USER_PINNED -> "You asked to be asked"
        }
}
