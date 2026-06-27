package com.aeronauticscml.bridge.api;

/**
 * Write-side abstraction over BBS CML's recorder.
 *
 * <p>Implementations push {@link ShipPose} frames into BBS CML's recording
 * pipeline. A single recording session may span many ticks; implementations
 * are responsible for:</p>
 * <ol>
 *   <li>Opening a new recording file or session when first started.</li>
 *   <li>Appending each frame to that session.</li>
 *   <li>Flushing / closing when the session is stopped.</li>
 * </ol>
 *
 * <p>The bridge ships with a {@code DefaultFileCmlRecorder} that writes
 * JSON lines to disk - useful for offline replay when CML is not installed.
 * When BBS CML is present, swap in an implementation that calls
 * {@code CmlRecorder.recordFrame(...)} directly.</p>
 */
public interface ICmlRecorder {
    /**
     * Begin a new recording session. Should be idempotent - calling on an
     * already-open recorder is a no-op.
     *
     * @param sessionName human-readable label for the session (e.g. a timestamp)
     */
    void start(String sessionName);

    /**
     * Append a single ship-pose frame to the active session.
     *
     * @param pose the snapshot to record (never null)
     */
    void recordFrame(ShipPose pose);

    /**
     * Capture the geometry of all physics ropes in the given level for this tick.
     * Ropes are level-wide (not per-ship), so this is called once per level per
     * captured tick, after the per-ship {@link #recordFrame(ShipPose)} calls.
     * Default is a no-op (e.g. the JSONL fallback recorder ignores ropes).
     */
    default void recordRopes(net.minecraft.server.level.ServerLevel level, long tick) {}

    /**
     * Flush any buffered data and close the active session. After this call
     * the recorder is ready to be {@link #start(String) started} again.
     */
    void stop();

    /**
     * @return {@code true} if a session is currently open and accepting frames.
     */
    boolean isRecording();

    /**
     * @return total number of frames written across all sessions in this
     *         recorder's lifetime. Useful for status commands.
     */
    long totalFramesWritten();

    /**
     * @return the underlying runtime (e.g. {@code "bbs-cml/0.1.0"} or
     *         {@code "file-json/fallback"}) so logs can disambiguate.
     */
    String backendName();
}
