package com.nuvio.tv.core.player

/**
 * Grabs a single video frame at a given timestamp and returns it encoded
 * as JPEG bytes. Implementations are expected to be stateful: [open] to
 * bind a source URL, [grab] called repeatedly for timestamps, [close] to
 * release. Instances are not thread-safe — each worker should own one.
 */
interface FrameGrabber {

    /** Binds the grabber to a remote or local media source. */
    @Throws(Exception::class)
    fun open(url: String, headers: Map<String, String>)

    /**
     * Returns JPEG bytes for the frame nearest [tsMs], scaled to
     * [widthPx] x [heightPx]. Returns null if no frame could be decoded
     * at this position (network failure, unsupported timestamp, etc.).
     * Must not throw for per-frame failures; throw only if the underlying
     * source is unusable entirely.
     */
    fun grab(tsMs: Long, widthPx: Int, heightPx: Int, jpegQuality: Int): ByteArray?

    /** Releases native resources. Idempotent. */
    fun close()
}

/** Creates a fresh [FrameGrabber] per call. Allows per-worker isolation. */
fun interface FrameGrabberFactory {
    fun create(): FrameGrabber
}
