package dev.lumen.core.agent

/**
 * One step of the loop, as seen from outside it.
 *
 * The console's step counter, its intent line and its reason line are all
 * answers to this. A run of a hundred steps that reports nothing until it is
 * over is indistinguishable from a run that stopped, and `I9` says the reason
 * is part of what the user is owed while the step is going, not only after it.
 */
public data class StepProgress(
    val stepIndex: Int,
    val totalSteps: Int,
    val planVersion: Int,
    val intent: String,
    val reason: String,
)

/**
 * The loop's progress seam.
 *
 * Called once for each step the loop chooses, from the run's own coroutine,
 * before the step is dispatched. It reports and changes nothing, and the loop
 * depends on nothing it returns: an implementation that reaches outside the
 * process treats that reach as best effort and never as a step of its own,
 * because a watcher that is slow, or gone, must not be able to hold a run up.
 * The default goes nowhere, which is what every unit test of the loop uses.
 */
public fun interface RunProgress {
    /** The loop is working on [step]. */
    public fun onStep(step: StepProgress)

    public companion object {
        /** The progress nobody is watching. */
        public val NONE: RunProgress = RunProgress { }
    }
}
