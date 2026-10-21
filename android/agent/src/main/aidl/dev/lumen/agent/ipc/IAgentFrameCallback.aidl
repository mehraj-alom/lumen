package dev.lumen.agent.ipc;

/**
 * The frame stream.
 *
 * The frames are the bulk channel. They are deliberately separate from the
 * state stream: a frame upload must never head-of-line-block a step
 * approval.
 *
 * The payload is a file descriptor, not a byte array. A 4 MB frame does not
 * fit in a Binder transaction, and trying to send one throws
 * TransactionTooLargeException.
 */
interface IAgentFrameCallback {

    /**
     * A frame is ready.
     *
     * @param path the frame's path inside the sandbox
     * @param width the frame's width
     * @param height the frame's height
     * @param seq the monotonic frame sequence number
     */
    oneway void onFrame(String path, int width, int height, long seq);

    /**
     * The frame stream's statistics.
     *
     * @param droppedFrames how many frames were dropped
     * @param framesPerSecond the current rate
     */
    oneway void onFrameStats(int droppedFrames, double framesPerSecond);
}
