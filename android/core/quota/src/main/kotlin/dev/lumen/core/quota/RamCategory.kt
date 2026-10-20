package dev.lumen.core.quota

/**
 * The consumers of the RAM budget.
 *
 * The caps are the ones in `AGENTS.md` §2. They sum to 1,880 MiB, which
 * leaves 168 MiB of headroom inside the 2,048 MiB cap for the parts of the
 * runtime that are not budgeted explicitly.
 */
public enum class RamCategory(public val capMiB: Int) {
    /** The resident quantised brain model. */
    BRAIN_MODEL(1_400),

    /** The accessibility tree cache and the node index. */
    TREE_CACHE(192),

    /** Frame and screenshot buffers. */
    FRAME_BUFFER(128),

    /** The planner's and the critic's working memory. */
    PLANNER_MEMORY(96),

    /** Services, the watchdog, and the IPC baseline. */
    BASELINE(64),
    ;

    public companion object {
        /** The total RAM cap for the agent runtime, in MiB. */
        public const val TOTAL_LIMIT_MIB: Int = 2_048

        /**
         * The order in which memory is given up when the budget is under
         * pressure: frames first, because they are the cheapest to lose and
         * the least costly to rebuild; the local model last, because
         * unloading it changes the run's behaviour.
         */
        public val EVICTION_LADDER: List<RamCategory> = listOf(
            FRAME_BUFFER,
            TREE_CACHE,
            PLANNER_MEMORY,
            BRAIN_MODEL,
        )
    }
}
