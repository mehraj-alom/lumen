package dev.lumen.core.policy

import dev.lumen.core.grounding.NodeIndex
import dev.lumen.core.grounding.TreeSnapshot
import dev.lumen.core.model.Action
import dev.lumen.core.model.ActionPreview
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalRequest
import dev.lumen.core.model.RiskClass

/**
 * Builds the request the user is asked with.
 *
 * The request has to answer three questions without the user having to
 * think: what is about to happen, where, and what cannot be undone. The
 * preview is redacted: it shows the shape of the action, not the whole of
 * the content.
 */
public object ApprovalRequestBuilder {

    /**
     * The request for [request].
     *
     * @param approvalId the identifier the approval will carry
     * @param nowMs the current time, used for the lease
     * @param leaseMs how long the user has to answer
     */
    public fun build(
        request: PolicyRequest,
        riskClass: RiskClass,
        classifier: DangerClassifier,
        approvalId: ApprovalId = ApprovalId(PLACEHOLDER_ID),
        nowMs: Long = 0L,
        leaseMs: Long = DEFAULT_LEASE_MS,
    ): ApprovalRequest {
        val observation = request.observation
        val index = observation?.let {
            NodeIndex(
                TreeSnapshot(
                    app = it.app,
                    nodes = it.nodes,
                    overlays = it.overlays,
                    capturedAtMs = it.capturedAtMs,
                ),
            )
        }

        val label = index?.let { classifier.labelFor(request.action, it) }
        val title = titleFor(request.action, riskClass, label)
        val detail = detailFor(request.action, label)

        return ApprovalRequest(
            runId = request.runId,
            approvalId = approvalId,
            stepId = request.stepId,
            riskClass = riskClass,
            title = title,
            detail = detail,
            app = observation?.app.orEmpty(),
            appLabel = request.appLabel,
            consequences = consequencesFor(riskClass),
            actionPreview = previewFor(request.action, label),
            expiresAtMs = nowMs + leaseMs,
            leaseSeconds = (leaseMs / 1000L).toInt(),
        )
    }

    /** The headline of the request. */
    public fun titleFor(action: Action, riskClass: RiskClass, label: String?): String {
        val target = label?.takeIf { it.isNotBlank() } ?: actionTarget(action)
        val base = STATIC_TITLES.getValue(riskClass)
        return if (riskClass in TARGETED_TITLES && target != null) "$base $target" else base
    }

    /** The concrete content of the request. */
    public fun detailFor(action: Action, label: String?): String =
        typedDetail(action, label) ?: navigationDetail(action) ?: gestureDetail(action)

    /** Actions that put content onto the screen, or hit a coordinate. */
    private fun typedDetail(action: Action, label: String?): String? = when (action) {
        is Action.InputText -> "The agent will type: \"${redact(action.text)}\""
        is Action.PasteText -> "The agent will paste: \"${redact(action.text)}\""
        is Action.Tap -> tappedDetail("The agent will tap", label, "The agent will tap the screen")
        is Action.DoubleTap -> tappedDetail("The agent will double tap", label, "The agent will double tap")
        is Action.LongPress -> tappedDetail("The agent will long press", label, "The agent will long press")
        else -> null
    }

    /** Actions that move between places or pick a named target. */
    private fun navigationDetail(action: Action): String? = when (action) {
        is Action.OpenUrl -> "The agent will open ${action.url}"
        is Action.OpenApp -> "The agent will open ${action.packageName}"
        is Action.ClickText -> "The agent will tap \"${redact(action.text)}\""
        is Action.ClickById -> "The agent will tap the element ${action.viewId}"
        is Action.Swipe -> "The agent will swipe across the screen"
        else -> null
    }

    /** The remaining primitive gestures and read-only actions. */
    private fun gestureDetail(action: Action): String = when (action) {
        is Action.Scroll -> "The agent will scroll ${action.direction.name.lowercase()}"
        is Action.Drag -> "The agent will drag across the screen"
        is Action.PressEnter -> "The agent will press enter"
        is Action.ListApps -> "The agent will list the applications"
        is Action.ObserveScreen -> "The agent will read the screen"
        is Action.Screenshot -> "The agent will take a screenshot"
        is Action.ReadFile -> "The agent will read ${action.path}"
        else -> "The agent will perform an action"
    }

    /** "verb \"label\"" when the target has a label, otherwise the fallback. */
    private fun tappedDetail(verb: String, label: String?, fallback: String): String = label?.let { "$verb \"$it\"" } ?: fallback

    /** What cannot be undone. */
    public fun consequencesFor(riskClass: RiskClass): List<String> = CONSEQUENCES[riskClass].orEmpty()

    /**
     * A redacted projection of the action.
     *
     * The preview is what the user sees while deciding, so it must be
     * truthful about the action and must not dump the content. Long text is
     * shortened; a secret is not shown at all.
     */
    public fun previewFor(action: Action, label: String?): ActionPreview =
        typedPreview(action, label) ?: navigationPreview(action) ?: gesturePreview(action)

    /** Actions that put content onto the screen, or hit a coordinate. */
    private fun typedPreview(action: Action, label: String?): ActionPreview? = when (action) {
        is Action.InputText -> ActionPreview(kind = "TYPE", text = redact(action.text), target = label)
        is Action.PasteText -> ActionPreview(kind = "PASTE", text = redact(action.text), target = label)
        is Action.Tap -> ActionPreview(kind = "TAP", label = label)
        is Action.DoubleTap -> ActionPreview(kind = "DOUBLE_TAP", label = label)
        is Action.LongPress -> ActionPreview(kind = "LONG_PRESS", label = label)
        else -> null
    }

    /** Actions that move between places or pick a named target. */
    private fun navigationPreview(action: Action): ActionPreview? = when (action) {
        is Action.OpenUrl -> ActionPreview(kind = "OPEN_URL", target = action.url)
        is Action.OpenApp -> ActionPreview(kind = "OPEN_APP", target = action.packageName)
        is Action.ClickText -> ActionPreview(kind = "CLICK", label = redact(action.text))
        is Action.ClickById -> ActionPreview(kind = "CLICK", label = action.viewId)
        is Action.Swipe -> ActionPreview(kind = "SWIPE")
        else -> null
    }

    /** The remaining primitive gestures and read-only actions. */
    private fun gesturePreview(action: Action): ActionPreview = when (action) {
        is Action.Scroll -> ActionPreview(kind = "SCROLL", label = action.direction.name.lowercase())
        is Action.Drag -> ActionPreview(kind = "DRAG")
        is Action.PressEnter -> ActionPreview(kind = "PRESS_ENTER")
        is Action.ListApps -> ActionPreview(kind = "LIST_APPS")
        is Action.ObserveScreen -> ActionPreview(kind = "OBSERVE")
        is Action.Screenshot -> ActionPreview(kind = "SCREENSHOT")
        is Action.ReadFile -> ActionPreview(kind = "READ_FILE", target = action.path)
        else -> ActionPreview(kind = "UNKNOWN")
    }

    /**
     * Shortens text for display.
     *
     * A secret is never shown. The user is told that a secret will be typed,
     * not what it is — the approval must not become a way to read a
     * credential out of the notification shade.
     */
    public fun redact(text: String): String = when {
        text.length <= MAX_PREVIEW_TEXT -> text
        else -> text.take(MAX_PREVIEW_TEXT) + "…"
    }

    private fun actionTarget(action: Action): String? = when (action) {
        is Action.OpenApp -> action.packageName
        is Action.OpenUrl -> action.url
        is Action.ClickText -> action.text
        is Action.InputText -> null
        else -> null
    }

    /** The default lease: five minutes. */
    public const val DEFAULT_LEASE_MS: Long = 5L * 60L * 1000L

    /** The longest text shown in a preview. */
    public const val MAX_PREVIEW_TEXT: Int = 120

    /** A placeholder identifier, replaced by the broker when it stages. */
    public const val PLACEHOLDER_ID: String = "00000000000000000000000000"

    /**
     * The headline for each risk class, before any target is appended.
     *
     * A table rather than a `when` so adding a class is a data change, not a
     * new branch. Every `RiskClass` must have an entry: `getValue` on a
     * missing one fails loudly instead of inventing a weaker headline.
     */
    private val STATIC_TITLES: Map<RiskClass, String> = mapOf(
        RiskClass.PAYMENT to "Pay",
        RiskClass.PUBLISH to "Send or publish",
        RiskClass.DELETE to "Delete",
        RiskClass.CREDENTIAL to "Type a secret",
        RiskClass.PERMISSION to "Grant a permission",
        RiskClass.COMMUNICATION to "Reach a person",
        RiskClass.ACCOUNT to "Change the account",
        RiskClass.LEGAL to "Accept a term",
        RiskClass.IRREVERSIBLE to "An irreversible action",
        RiskClass.USER_PINNED to "You asked to be asked about this",
        RiskClass.NONE to "Continue",
    )

    /** The classes whose headline names the target: pay, publish, delete. */
    private val TARGETED_TITLES: Set<RiskClass> = setOf(RiskClass.PAYMENT, RiskClass.PUBLISH, RiskClass.DELETE)

    /** The things that cannot be undone, per risk class. `NONE` carries none. */
    private val CONSEQUENCES: Map<RiskClass, List<String>> = mapOf(
        RiskClass.PAYMENT to listOf("The money leaves the account", "This cannot be undone"),
        RiskClass.PUBLISH to listOf("Other people will see this", "This cannot be undone"),
        RiskClass.DELETE to listOf("The data is destroyed", "This cannot be undone"),
        RiskClass.CREDENTIAL to listOf("The secret is typed into the app"),
        RiskClass.PERMISSION to listOf("The app keeps the permission until you revoke it"),
        RiskClass.COMMUNICATION to listOf("The person is contacted now"),
        RiskClass.ACCOUNT to listOf("The account changes now"),
        RiskClass.LEGAL to listOf("A term is accepted on your behalf"),
        RiskClass.IRREVERSIBLE to listOf("This cannot be undone"),
        RiskClass.USER_PINNED to listOf("You asked to approve this yourself"),
    )
}
