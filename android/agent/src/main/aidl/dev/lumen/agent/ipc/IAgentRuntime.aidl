// The contract between the console and the agent runtime.
//
// The console lives in the :ui process and the runtime in the :agent
// process. The console is a view: it renders what the runtime tells it and
// sends explicit control requests.
//
// The payloads are JSON, for the same reason the accessibility bridge is:
// the model types are already serialisable, and a JSON payload keeps this
// interface free of Parcelable boilerplate that would have to be kept in
// step with the model by hand.

package dev.lumen.agent.ipc;

import dev.lumen.agent.ipc.IAgentFrameCallback;
import dev.lumen.agent.ipc.IAgentStateCallback;

interface IAgentRuntime {

    /**
     * Starts a run from a high-level goal.
     *
     * @param goalJson the goal, its constraints and the budget
     * @return the run id
     */
    String startGoal(String goalJson);

    /**
     * Pauses a run.
     */
    void pauseRun(String runId, String reason);

    /**
     * Resumes a paused run.
     */
    void resumeRun(String runId);

    /**
     * Aborts a run. The abort takes effect within one action boundary.
     */
    void abortRun(String runId, String reason);

    /**
     * Answers a pending approval.
     *
     * @param decision APPROVE, DENY or ANSWER
     */
    void answerApproval(String runId, String approvalId, String decision, String answerText);

    /**
     * Sets the brain pin for a run: AUTO, LOCAL or CLOUD.
     */
    void setBrainPin(String runId, String pin);

    /**
     * Takes the run over, or releases it back to the agent.
     */
    void setTakeover(String runId, boolean takenOver);

    /**
     * The current state of a run, as JSON.
     */
    String getState(String runId);

    /**
     * The current budget of a run, as JSON.
     */
    String getBudget(String runId);

    /**
     * Registers for the state stream.
     */
    void registerStateCallback(IAgentStateCallback callback);

    /**
     * Removes a state listener.
     */
    void unregisterStateCallback(IAgentStateCallback callback);

    /**
     * Registers for the frame stream.
     */
    void registerFrameCallback(IAgentFrameCallback callback);

    /**
     * Removes a frame listener.
     */
    void unregisterFrameCallback(IAgentFrameCallback callback);
}
