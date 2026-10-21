package dev.lumen.agent.ipc;

/**
 * The state stream.
 *
 * The runtime pushes; the console renders. Every frame carries a monotonic
 * sequence number, because the console must order by sequence and never by
 * wall clock.
 */
interface IAgentStateCallback {

    /**
     * A step event.
     *
     * @param eventJson the event, as JSON
     */
    oneway void onEvent(String eventJson);

    /**
     * The state changed.
     *
     * @param stateJson the whole state, as JSON
     */
    oneway void onStateChanged(String stateJson);

    /**
     * An approval is waiting for a decision.
     *
     * @param approvalJson the approval request, as JSON
     */
    oneway void onApprovalRequired(String approvalJson);

    /**
     * Something failed.
     */
    oneway void onError(String code, String message, String correlationId);
}
