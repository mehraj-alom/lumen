package dev.lumen.core.policy

import dev.lumen.core.grounding.NodeIndex
import dev.lumen.core.grounding.NodeMatcher
import dev.lumen.core.grounding.TreeSnapshot
import dev.lumen.core.model.Action
import dev.lumen.core.model.NodeRef
import dev.lumen.core.model.Observation
import dev.lumen.core.model.RiskClass

/**
 * The deterministic risk classification.
 *
 * Everything here is a rule over the action and the screen, with no model in
 * the loop. That is deliberate: the classification decides whether a human
 * has to be interrupted, and a question that important must not depend on
 * whether the brain was feeling confident.
 */
public class DangerClassifier {

    /**
     * The risk class of [action] in [observation], or [RiskClass.NONE].
     *
     * The classifiers are independent of each other and the first match
     * wins, ordered from the most consequential to the least. A surface that
     * is both a payment and a publish is a payment.
     */
    public fun classify(action: Action, observation: Observation?): RiskClass {
        if (observation == null) return RiskClass.NONE

        val index = NodeIndex(
            TreeSnapshot(
                app = observation.app,
                nodes = observation.nodes,
                overlays = observation.overlays,
                capturedAtMs = observation.capturedAtMs,
            ),
        )

        return riskFor(action, index, labelFor(action, index))
    }

    /**
     * The first matching risk class, ordered from the most consequential to
     * the least. The checks are independent and the first match wins: a
     * surface that is both a payment and a publish is a payment.
     */
    private fun riskFor(action: Action, index: NodeIndex, label: String?): RiskClass = when {
        isCredential(action, index) -> RiskClass.CREDENTIAL
        labelMatches(label, PAYMENT_LABELS) -> RiskClass.PAYMENT
        hasPaymentSurface(index) -> RiskClass.PAYMENT
        labelMatches(label, DELETE_LABELS) -> RiskClass.DELETE
        labelMatches(label, PUBLISH_LABELS) -> RiskClass.PUBLISH
        labelMatches(label, PERMISSION_LABELS) -> RiskClass.PERMISSION
        labelMatches(label, COMMUNICATION_LABELS) -> RiskClass.COMMUNICATION
        labelMatches(label, ACCOUNT_LABELS) -> RiskClass.ACCOUNT
        labelMatches(label, LEGAL_LABELS) -> RiskClass.LEGAL
        else -> RiskClass.NONE
    }

    /**
     * The label of the thing the action will touch.
     *
     * For a coordinate action it is the node under the point, resolved to its
     * clickable ancestor — which is the same resolution the actor uses, so
     * the policy sees what the action will really hit.
     */
    public fun labelFor(action: Action, index: NodeIndex): String? = when (action) {
        is Action.Tap -> nodeLabel(index, action.x.fraction, action.y.fraction)
        is Action.DoubleTap -> nodeLabel(index, action.x.fraction, action.y.fraction)
        is Action.LongPress -> nodeLabel(index, action.x.fraction, action.y.fraction)
        is Action.ClickText -> labelledNode(index, NodeMatcher.byTextOccurrence(index, action.text, action.occurrence))
        is Action.ClickById -> labelledNode(index, index.nodesWithViewId(action.viewId).firstOrNull())
        is Action.InputText -> NodeMatcher.focusedField(index)?.let { index.labelOf(it) }
        else -> null
    }

    /** The label of [node], resolved to its clickable ancestor when it has one. */
    private fun labelledNode(index: NodeIndex, node: NodeRef?): String? =
        node?.let { index.clickableAncestor(it) ?: it }?.let { index.labelOf(it) }

    /** Whether the action types into a secret. */
    private fun isCredential(action: Action, index: NodeIndex): Boolean {
        if (action !is Action.InputText && action !is Action.PasteText) return false
        val field = NodeMatcher.focusedField(index) ?: return false
        return field.isPassword
    }

    private fun nodeLabel(index: NodeIndex, x: Double, y: Double): String? {
        val hit = index.nodeAt(x, y) ?: return null
        val actionable = index.clickableAncestor(hit) ?: hit
        return index.labelOf(actionable).takeIf { it.isNotBlank() }
    }

    private fun labelMatches(label: String?, words: Set<String>): Boolean {
        if (label.isNullOrBlank()) return false
        val lowered = label.lowercase()
        return words.any { word -> lowered.contains(word) }
    }

    /**
     * Whether the screen is a payment form.
     *
     * This is a narrow check on purpose. A page that merely contains the word
     * "pay" is not a payment surface, and treating it as one would turn every
     * action on the page into an approval. A card field is.
     */
    private fun hasPaymentSurface(index: NodeIndex): Boolean = index.source.nodes.any { node ->
        val label = node.text ?: node.contentDescription ?: return@any false
        labelMatches(label, PAYMENT_SURFACE_LABELS)
    }

    public companion object {
        /** Words that mean money is about to move. */
        public val PAYMENT_LABELS: Set<String> = setOf(
            "pay", "payment", "checkout", "buy", "purchase", "subscribe", "subscription",
            "donate", "deposit", "transfer", "confirm payment", "place order", "tip",
        )

        /**
         * Words that only appear on a payment form.
         *
         * These are the surface indicators, not the button labels: a card
         * field means the screen is a payment form whatever the user taps.
         */
        public val PAYMENT_SURFACE_LABELS: Set<String> = setOf(
            "card number",
            "cvv",
            "cvc",
            "expiry date",
            "expiration date",
            "billing address",
            "credit card",
            "name on card",
        )

        /** Words that mean something will be destroyed. */
        public val DELETE_LABELS: Set<String> = setOf(
            "delete", "remove", "erase", "wipe", "uninstall", "revoke", "clear all",
            "cancel subscription", "close account", "deactivate",
        )

        /** Words that mean something will be seen by someone else. */
        public val PUBLISH_LABELS: Set<String> = setOf(
            "post", "publish", "share", "send", "comment", "reply", "tweet", "submit",
            "upload", "broadcast", "forward",
        )

        /** Words that mean a permission is being granted. */
        public val PERMISSION_LABELS: Set<String> = setOf(
            "allow",
            "grant",
            "enable",
            "permit",
            "install",
            "trust",
            "turn on",
        )

        /** Words that mean a person will be reached. */
        public val COMMUNICATION_LABELS: Set<String> = setOf(
            "call",
            "ring",
            "dial",
            "join",
            "message",
            "sms",
            "voice call",
            "start call",
        )

        /** Words that mean the account itself changes. */
        public val ACCOUNT_LABELS: Set<String> = setOf(
            "change password", "change email", "change phone", "two-factor",
            "two factor", "recovery", "link account", "unlink", "add account",
            "deactivate account",
        )

        /** Words that mean a term is being accepted. */
        public val LEGAL_LABELS: Set<String> = setOf(
            "terms", "agree", "accept", "consent", "privacy policy", "arbitration",
            "authorise", "authorize", "i agree",
        )
    }
}
