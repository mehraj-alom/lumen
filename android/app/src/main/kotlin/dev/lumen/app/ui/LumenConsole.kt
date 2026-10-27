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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
 * The order of the screen is the order of what the user comes to do: the status,
 * then the control that acts, then the reasoning. The column scrolls, so a
 * control is never cut off by a short screen or by the keyboard — a goal field
 * the user cannot reach is a goal field that does not exist.
 *
 * [onStop] is the in-app half of `I12`: the run is abortable from the screen
 * the user is looking at, and the runtime notification carries the same stop.
 * There is no takeover control here yet: [ConsoleState.takeoverActive] arrives
 * with the state and nothing on this view reads it. The web console is where a
 * takeover is requested.
 *
 * [onOpenSettings] is the way out of this screen to the settings, which are the
 * one part of the app the runtime acts on but the console owns.
 */
@Composable
public fun LumenConsole(
    state: ConsoleState,
    onStart: (String, String) -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    onStop: () -> Unit,
    onTakeover: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var goal by remember { mutableStateOf("") }
    var allowedPackages by remember { mutableStateOf("") }
    val awaitingGoal = state.phase == "IDLE" || state.phase in TERMINAL_PHASES

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(GlassTokens.ABYSS))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(state = state, onOpenSettings = onOpenSettings)

        if (awaitingGoal) {
            GoalControl(
                goal = goal,
                onGoalChange = { goal = it },
                allowedPackages = allowedPackages,
                onAllowedPackagesChange = { allowedPackages = it },
                onStart = { onStart(goal.trim(), allowedPackages) },
            )
        } else {
            RunningControl(state = state, onStop = onStop, onTakeover = onTakeover)
        }

        state.approvals.forEach { approval ->
            ApprovalPanel(approval, onApprove, onDeny)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PlanPanel(state.plan, Modifier.weight(1f))
            TracePanel(state.trace, Modifier.weight(1f))
        }

        BudgetPanel(state)
    }
}

/**
 * The goal field.
 *
 * The Start button answers to the text beside it and to nothing else: a goal
 * that is blank cannot be sent, and the control says so rather than starting an
 * empty run.
 */
@Composable
private fun GoalControl(
    goal: String,
    onGoalChange: (String) -> Unit,
    allowedPackages: String,
    onAllowedPackagesChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    GlassSurface(kind = GlassSurfaceKind.INLINE, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = goal,
                    onValueChange = onGoalChange,
                    label = { Text("What should the agent do?") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = allowedPackages,
                    onValueChange = onAllowedPackagesChange,
                    label = { Text("Allowed packages (comma-separated, optional)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.Button(
                    onClick = onStart,
                    enabled = goal.isNotBlank(),
                ) {
                    Text("Start")
                }
            }
        }
    }
}

/**
 * What the console says when the accessibility service is off.
 *
 * The run cannot see or touch the screen without it, and the setting is the only
 * place the console process can find out. So the notice names what is missing,
 * what it stops the agent from doing, and the way to change it — and leaves the
 * rest of the screen usable.
 */
@Composable
public fun GroundingNotice(onEnable: () -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(kind = GlassSurfaceKind.INLINE, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Accessibility is off",
                    color = Color(GlassTokens.TEXT_PRIMARY),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "LUMEN cannot see or touch the screen until the service is switched " +
                        "on, so a run stops at its first step.",
                    color = Color(GlassTokens.TEXT_SECONDARY),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            androidx.compose.material3.Button(onClick = onEnable) {
                Text("Open")
            }
        }
    }
}

/**
 * The control shown while a run is live: what the agent is doing now, and the
 * stop and takeover.
 *
 * The stop is unconditional. `I12` says the user can abort within one action
 * boundary, so the control is present in every phase that is not idle, and the
 * request it sends is honoured whatever run id the runtime is holding.
 *
 * The takeover hands the screen back to the user, or gives it back to the
 * agent. When the user has taken over, the run is paused and the control says
 * so; giving it back resumes the run.
 */
@Composable
private fun RunningControl(state: ConsoleState, onStop: () -> Unit, onTakeover: (Boolean) -> Unit) {
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
                if (state.takeoverActive) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "you have taken over — give control back to resume",
                        color = Color(GlassTokens.TEXT_SECONDARY),
                        fontSize = 12.sp,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.takeoverActive) {
                    androidx.compose.material3.Button(
                        onClick = { onTakeover(false) },
                    ) {
                        Text("Give back")
                    }
                } else {
                    androidx.compose.material3.Button(
                        onClick = { onTakeover(true) },
                    ) {
                        Text("Take over")
                    }
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.OutlinedButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
    }
}

/**
 * The status row, and the way to the settings.
 *
 * The settings live on this row rather than behind a menu because they hold the
 * four things the user may need to change about a device that is acting on it:
 * how the agent shows itself, how long the phone must be idle before it touches
 * the screen, where the reasoning runs, and whether a crash is written down.
 */
@Composable
private fun Header(state: ConsoleState, onOpenSettings: () -> Unit) {
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
            Spacer(Modifier.width(16.dp))
            androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                Text(
                    text = "Settings",
                    color = Color(GlassTokens.TEXT_SECONDARY),
                    fontSize = 14.sp,
                )
            }
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
