// The bridge between the agent process and the accessibility service.
//
// The service lives in the console process, because Android requires an
// accessibility service to be in a process the user can enable. The agent
// lives in its own process, so the two talk over this interface rather than
// by direct call (ARCH.md §1, §6).
//
// Payloads are JSON. The model types are already serialisable, and a JSON
// payload keeps the AIDL surface free of Parcelable boilerplate that would
// have to be kept in step with the model by hand.

package dev.lumen.platform.accessibility;

interface IAccessibilityBridge {

    /**
     * Reads the current screen.
     *
     * @param maxNodes the ceiling on the number of nodes in the tree
     * @return the tree as JSON, or null when the service is not connected
     */
    String readTree(int maxNodes);

    /**
     * Performs an action.
     *
     * @param actionJson the action as JSON
     * @return true when the gesture was dispatched
     */
    boolean performAction(String actionJson);

    /**
     * The package currently in front, or null.
     */
    String foregroundPackage();

    /**
     * Whether the service is connected and able to act.
     */
    boolean isReady();

    /**
     * Captures the screen.
     *
     * @return the path of the captured frame inside the sandbox, or null
     */
    String captureScreen();

    /**
     * Opens an application by its package name.
     *
     * @return true when the intent was sent
     */
    boolean openApp(String packageName);

    /**
     * Registers a listener for the service's state.
     */
    void registerListener(IAccessibilityListener listener);

    /**
     * Removes a listener.
     */
    void unregisterListener(IAccessibilityListener listener);
}
