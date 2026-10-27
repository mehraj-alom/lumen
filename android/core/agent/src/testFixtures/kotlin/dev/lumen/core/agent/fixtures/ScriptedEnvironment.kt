package dev.lumen.core.agent.fixtures

import dev.lumen.core.action.ActionResult
import dev.lumen.core.agent.ApprovalSignal
import dev.lumen.core.agent.StepEnvironment
import dev.lumen.core.model.Action
import dev.lumen.core.model.ApprovalId
import dev.lumen.core.model.ApprovalState
import dev.lumen.core.model.ErrorCode
import dev.lumen.core.model.NodeRef
import dev.lumen.core.model.Observation
import dev.lumen.core.model.RelativeRect
import kotlinx.coroutines.delay

/**
 * A device that replays a script.
 *
 * The scripted environment is what makes the hundred-step scenario
 * possible: it produces a screen per step, and the screens are the ones the
 * scenario needs — a payment form, a publish screen, a message screen.
 */
public class ScriptedEnvironment(private val screens: List<Observation> = emptyList()) : StepEnvironment {

    /** Every action that was performed, in order. */
    public val performed: MutableList<Action> = mutableListOf()

    /** The index of the screen the environment is showing. */
    public var index: Int = 0

    /** The foreground package. */
    public var foreground: String? = "com.example.browser"

    /** The number of the action that fails, or null. */
    public var failAt: Int? = null

    override suspend fun observe(): Observation? = screens.getOrNull(index)

    override suspend fun perform(action: Action, allowedPackages: Set<String>): ActionResult {
        performed += action

        if (failAt == performed.size) {
            return ActionResult.failed(
                action = action,
                code = ErrorCode.INTERNAL,
                message = "the scripted environment was told to fail",
            )
        }

        if (screens.isNotEmpty()) {
            index = (index + 1).coerceAtMost(screens.size - 1)
        }
        return ActionResult.ok(action, screens.getOrNull(index))
    }

    override suspend fun foregroundPackage(): String? = foreground

    public companion object {
        /** A screen with one button that says [label]. */
        public fun screen(
            app: String = "com.example.browser",
            label: String = "Continue",
            extra: List<NodeRef> = emptyList(),
        ): Observation = Observation(
            app = app,
            nodes = listOf(
                NodeRef(
                    id = 0,
                    className = "android.widget.Button",
                    text = label,
                    packageName = app,
                    bounds = RelativeRect(0.0, 0.0, 1.0, 0.1),
                    clickable = true,
                ),
            ) + extra,
        )

        /** A screen with a card field, which makes it a payment surface. */
        public fun paymentScreen(): Observation = Observation(
            app = "com.example.travel",
            nodes = listOf(
                NodeRef(
                    id = 0,
                    className = "android.widget.EditText",
                    text = "Card number",
                    packageName = "com.example.travel",
                    bounds = RelativeRect(0.0, 0.0, 1.0, 0.1),
                    editable = true,
                ),
                NodeRef(
                    id = 1,
                    className = "android.widget.Button",
                    text = "Pay 382 EUR",
                    packageName = "com.example.travel",
                    bounds = RelativeRect(0.0, 0.2, 1.0, 0.3),
                    clickable = true,
                ),
            ),
        )

        /** A screen with a publish button. */
        public fun publishScreen(): Observation = Observation(
            app = "com.example.notes",
            nodes = listOf(
                NodeRef(
                    id = 0,
                    className = "android.widget.Button",
                    text = "Publish note",
                    packageName = "com.example.notes",
                    bounds = RelativeRect(0.0, 0.0, 1.0, 0.1),
                    clickable = true,
                ),
            ),
        )

        /** A screen with a send button. */
        public fun messageScreen(): Observation = Observation(
            app = "com.example.messenger",
            nodes = listOf(
                NodeRef(
                    id = 0,
                    className = "android.widget.Button",
                    text = "Send message",
                    packageName = "com.example.messenger",
                    bounds = RelativeRect(0.0, 0.0, 1.0, 0.1),
                    clickable = true,
                ),
            ),
        )
    }
}

/**
 * A signal that reads the decision from the store.
 *
 * The decision may arrive from another process, so the signal polls the
 * durable store rather than being pushed to.
 */
public class InMemoryApprovalSignal(private val store: dev.lumen.core.model.StateStore, private val pollIntervalMs: Long = 10L) :
    ApprovalSignal {

    override suspend fun await(approvalId: ApprovalId): ApprovalState {
        while (true) {
            val record = store.loadApproval(approvalId) ?: return ApprovalState.EXPIRED
            if (record.state != ApprovalState.PENDING) return record.state
            delay(pollIntervalMs)
        }
    }
}
