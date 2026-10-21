package dev.lumen.platform.accessibility;

/**
 * The service's state, pushed to whoever is listening.
 *
 * The listener is how the agent learns that the accessibility service was
 * revoked, so the run can pause and say why instead of failing obscurely.
 */
interface IAccessibilityListener {

    /**
     * The service connected.
     */
    void onServiceConnected();

    /**
     * The service was destroyed or revoked.
     */
    void onServiceDisconnected();

    /**
     * The foreground package changed.
     *
     * @param packageName the package in front, or null
     */
    void onForegroundChanged(String packageName);
}
