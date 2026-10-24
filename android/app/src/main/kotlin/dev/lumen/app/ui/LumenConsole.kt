package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.design.GlassSurface
import dev.lumen.app.design.GlassSurfaceKind
import dev.lumen.platform.overlay.GlassTokens

/** What the console shows. */
public data class ConsoleState(
    val phase: String = "IDLE",
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val currentIntent: String = "",
    val currentReason: String = "",
    val brainRoute: String = "AUTO",
    val takeoverActive: Boolean = false,
    val ramUsedMiB: Int = 0,
    val ramLimitMiB: Int = 2_048,
    val storageUsedMiB: Int = 0,
    val storageLimitMiB: Int = 20_000,
    val stepsUsed: Int = 0,
    val stepsHardLimit: Int = 400,
    val plan: List<PlanNode> = emptyList(),
    val trace: List<TraceLine> = emptyList(),
    val approvals: List<ApprovalCard> = emptyList(),
)

/** One node of the plan graph. */
public data class PlanNode(val intent: String, val state: String, val active: Boolean = false, val riskClass: String = "NONE")

/** One line of the reasoning trace. */
public data class TraceLine(val text: String, val kind: String = "note")

/** An approval waiting for a decision. */
public data class ApprovalCard(val approvalId: String, val title: String, val detail: String, val riskClass: String)

/**
 * The phases a run ends in.
 *
 * The console offers the goal field again in these phases: the runtime holds
 * one run at a time and refuses a second goal while one is live, so a screen
 * that only offered *Stop* after a finished run would have no way to start the
 * next one.
 */
private val TERMINAL_PHASES: Set<String> = setOf("DONE", "FAILED", "ABORTED")

/**
 * The console.
 *
 * The console is a view. It holds no run state, it renders what the agent
 * process tells it, and every control on it is an explicit request.
 *
 * [onStop] is the in-app half of `I12`: the run is abortable from the screen
 * the user is looking at, and the runtime notification carries the same stop.
 * There is no takeover control here yet: [ConsoleState.takeoverActive] arrives
 * with the state and nothing on this view reads it. The web console is where a
 * takeover is requested.
 */
@Composable
public fun LumenConsole(
    state: ConsoleState,
    onStart: (String) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var goal by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(GlassTokens.ABYSS)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(state)

            if (state.approvals.isNotEmpty()) {
                state.approvals.forEach { approval ->
                    ApprovalPanel(approval, onApprove, onDeny)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PlanPanel(state.plan, Modifier.weight(1f))
                TracePanel(state.trace, Modifier.weight(1f))
            }

            BudgetPanel(state)

            if (state.phase == "IDLE" || state.phase in TERMINAL_PHASES) {
                GlassSurface(kind = GlassSurfaceKind.INLINE, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = goal,
                            onValueChange = { goal = it },
                            label = { Text("What should the agent do?") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        Spacer(Modifier.width(12.dp))
                        androidx.compose.material3.Button(
                            onClick = { onStart(goal) },
                            enabled = goal.isNotBlank(),
                        ) {
                            Text("Start")
                        }
                    }
                }
            } else {
                RunningControl(state = state, onStop = onStop)
            }
        }
    }
}

/**
 * The control shown while a run is live: what the agent is doing now, and the
 * stop.
 *
 * The stop is unconditional. `I12` says the user can abort within one action
 * boundary, so the control is present in every phase that is not idle, and the
 * request it sends is honoured whatever run id the runtime is holding.
 */
@Composable
private fun RunningControl(state: ConsoleState, onStop: () -> Unit) {
    GlassSurface(kind = GlassSurfaceKind.INLINE, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.currentIntent.ifBlank { "running" },
                    color = Color(GlassTokens.TEXT_PRIMARY),
                    fontSize = 14.sp,
                )
                if (state.currentReason.isNotBlank()) {
                    Text(
                        text = state.currentReason,
                        color = Color(GlassTokens.TEXT_SECONDARY),
                        fontSize = 13.sp,
                    )
                }
            }
            androidx.compose.material3.OutlinedButton(onClick = onStop) {
                Text("Stop")
            }
        }
    }
}

/** The status row. */
@Composable
private fun Header(state: ConsoleState) {
    GlassSurface(kind = GlassSurfaceKind.INLINE, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "LUMEN",
                color = Color(GlassTokens.TEXT_PRIMARY),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${state.phase} · ${state.stepIndex}/${state.stepCount}",
                color = Color(GlassTokens.TEXT_SECONDARY),
                fontSize = 14.sp,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "brain: ${state.brainRoute}",
                color = Color(GlassTokens.TEXT_TERTIARY),
                fontSize = 13.sp,
            )
        }
    }
}

/** The plan graph. */
@Composable
private fun PlanPanel(nodes: List<PlanNode>, modifier: Modifier = Modifier) {
    GlassSurface(kind = GlassSurfaceKind.CARD, modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "PLAN",
                color = Color(GlassTokens.TEXT_TERTIARY),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.height(180.dp)) {
                items(nodes) { node ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (node.active) {
                                        Color(GlassTokens.STATE_WORKING)
                                    } else {
                                        Color(GlassTokens.TEXT_TERTIARY)
                                    },
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = node.intent,
                            color = if (node.active) {
                                Color(GlassTokens.TEXT_PRIMARY)
                            } else {
                                Color(GlassTokens.TEXT_SECONDARY)
                            },
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

/** The reasoning trace. */
@Composable
private fun TracePanel(lines: List<TraceLine>, modifier: Modifier = Modifier) {
    GlassSurface(kind = GlassSurfaceKind.CARD, modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "REASONING",
                color = Color(GlassTokens.TEXT_TERTIARY),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.height(180.dp)) {
                items(lines) { line ->
                    Text(
                        text = line.text,
                        color = Color(GlassTokens.TEXT_SECONDARY),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

/** The budget meters. */
@Composable
private fun BudgetPanel(state: ConsoleState) {
    GlassSurface(kind = GlassSurfaceKind.INLINE, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Meter("RAM", state.ramUsedMiB, state.ramLimitMiB, "MiB")
            Meter("STORAGE", state.storageUsedMiB, state.storageLimitMiB, "MiB")
            Meter("STEPS", state.stepsUsed, state.stepsHardLimit, "")
        }
    }
}

/** One budget meter. */
@Composable
private fun Meter(label: String, used: Int, limit: Int, unit: String) {
    val fraction = if (limit <= 0) 0f else (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
    Column {
        Text(
            text = "$label $used/$limit $unit",
            color = Color(GlassTokens.TEXT_SECONDARY),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(GlassTokens.SLATE)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(Color(GlassTokens.STATE_WORKING)),
            )
        }
    }
}

/** The approval panel. */
@Composable
private fun ApprovalPanel(approval: ApprovalCard, onApprove: (String) -> Unit, onDeny: (String) -> Unit) {
    GlassSurface(kind = GlassSurfaceKind.SHEET, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = approval.riskClass,
                color = Color(GlassTokens.STATE_APPROVAL),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = approval.title,
                color = Color(GlassTokens.TEXT_PRIMARY),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = approval.detail,
                color = Color(GlassTokens.TEXT_SECONDARY),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Button(
                    onClick = { onApprove(approval.approvalId) },
                ) {
                    Text("Approve")
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = { onDeny(approval.approvalId) },
                ) {
                    Text("Deny")
                }
            }
        }
    }
}
