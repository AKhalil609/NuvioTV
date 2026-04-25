package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.SeekPreviewGenerationType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/**
 * Orchestrates seek-preview thumbnail generation for a single stream,
 * writing results into a [SeekPreviewThumbnailStore].
 *
 * The work is split into chunks covering successive fractions of the
 * total duration (25% by default). Videos shorter than
 * [Config.shortVideoThresholdMs] are generated in a single chunk.
 *
 * Each chunk is executed by up to [Config.workerCount] parallel workers,
 * each owning its own [FrameGrabber]. Timestamps within a chunk are
 * assigned round-robin so early positions in the chunk are grabbed
 * soonest regardless of worker count.
 *
 * The runtime controller is responsible for deciding when to advance
 * to the next chunk via [continueNextChunk] — the generator stays out
 * of the "did playback start" business.
 */
class SeekPreviewGenerator(
    private val store: SeekPreviewThumbnailStore,
    private val grabberFactory: FrameGrabberFactory,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val config: Config = Config()
) {

    data class Config(
        val widthPx: Int = 160,
        val heightPx: Int = 90,
        // 30 s spacing: balanced for speed and scrub accuracy.
        // Reduces total frame count by 33% compared to 20 s.
        val intervalMs: Int = 30_000,
        // 3 min spacing for the first pass: gives a rough preview for the
        // whole movie in < 2 minutes.
        val sparseIntervalMs: Int = 180_000,
        val jpegQuality: Int = 60,
        // Widened to 1.5× intervalMs so the "nearest" lookup always finds
        // a neighbour.
        // Must be >= sparseIntervalMs/2 (90 s) so lookups always find a sparse
        // frame. Dense frames (30 s interval, 15 s max gap) fit comfortably within this.
        val nearestMaxDeltaMs: Long = 120_000L,
        val workerCount: Int = 2,
        val chunkFraction: Double = 0.25,
        val shortVideoThresholdMs: Long = 5 * 60_000L
    )

    data class Input(
        val key: String,
        val url: String,
        val headers: Map<String, String>,
        val durationMs: Long,
        val mimeTypeHint: String? = null,
        val generationType: SeekPreviewGenerationType = SeekPreviewGenerationType.SPARSE
    )

    sealed class State {
        data object Idle : State()
        data object Probing : State()
        data class Generating(
            val framesDone: Int,
            val framesTotal: Int,
            val chunkIndex: Int,
            val totalChunks: Int,
            val isSparse: Boolean = false
        ) : State()
        data class ChunkDone(
            val completedChunkIndex: Int,
            val totalChunks: Int,
            val hasMoreChunks: Boolean,
            val generatedThroughMs: Long,
            val isSparse: Boolean = false
        ) : State()
        data object Done : State()
        data object Unsupported : State()
        data class Failed(val message: String) : State()
        /** The probed source duration was too short — likely a debrid "not cached yet" clip. */
        data class BadSource(val triedUrl: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var currentEntry: SeekPreviewThumbnailStore.MovieEntry? = null
    private var currentInput: Input? = null
    private var currentChunkRanges: List<LongRange> = emptyList()
    private var currentAllTimestamps: List<Long> = emptyList()
    private var nextChunkIndex: Int = 0
    private var totalChunksWithPasses: Int = 0
    private var job: Job? = null

    /**
     * Starts a new generation session for [input], cancelling any prior
     * run. Runs the first chunk (sparse pass if enabled) and stops.
     * The returned [Job] completes when the first chunk finishes.
     */
    fun start(input: Input, scope: CoroutineScope): Job {
        synchronized(lock) {
            job?.cancel()
            closeCurrentEntryLocked()
            currentInput = input
            currentChunkRanges = emptyList()
            currentAllTimestamps = emptyList()
            nextChunkIndex = 0
            totalChunksWithPasses = 0
            val launched = scope.launch(workDispatcher) {
                runInitialAndFirstChunk(input)
            }
            job = launched
            return launched
        }
    }

    /**
     * Runs the next pending chunk if there is one and no run is in flight.
     * Returns the [Job] for the new chunk, or null if the session is
     * already complete, has no active input, or a run is already active.
     */
    fun continueNextChunk(scope: CoroutineScope): Job? {
        synchronized(lock) {
            if (job?.isActive == true) return null
            val input = currentInput ?: return null
            val index = nextChunkIndex
            if (index >= totalChunksWithPasses) return null
            val launched = scope.launch(workDispatcher) {
                runChunk(input, index)
            }
            job = launched
            return launched
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            closeCurrentEntryLocked()
            currentInput = null
            currentChunkRanges = emptyList()
            currentAllTimestamps = emptyList()
            nextChunkIndex = 0
            totalChunksWithPasses = 0
            _state.value = State.Idle
        }
    }

    /**
     * Returns the JPEG bytes of the cached thumbnail nearest [tsMs] for the
     * currently-open entry, or null if none is within
     * [Config.nearestMaxDeltaMs]. Safe to call from the UI thread.
     */
    fun nearestJpeg(tsMs: Long): ByteArray? {
        val entry = synchronized(lock) { currentEntry } ?: return null
        return entry.nearest(tsMs, config.nearestMaxDeltaMs)
    }

    private suspend fun runInitialAndFirstChunk(input: Input) {
        if (!isSupported(input.mimeTypeHint, input.url)) {
            _state.value = State.Unsupported
            return
        }
        if (input.durationMs <= 0L) {
            _state.value = State.Failed("invalid duration")
            return
        }
        _state.value = State.Probing

        // Quick duration probe: open the source, read its container-level
        // duration, then close. A "not cached yet" debrid error clip reports a
        // duration of a few seconds to at most a minute; real content is always
        // longer. This costs only one extra HTTP connection (moov-atom fetch)
        // and prevents workers from connecting to a known-bad source.
        val probedDuration = probeSourceDuration(input)
        if (probedDuration != null && probedDuration < SHORT_SOURCE_THRESHOLD_MS) {
            _state.value = State.BadSource(triedUrl = input.url)
            return
        }

        val entry = store.open(
            key = input.key,
            width = config.widthPx,
            height = config.heightPx,
            intervalMs = config.intervalMs,
            durationMs = input.durationMs
        )
        val allTs = buildTimestamps(input.durationMs, config.intervalMs.toLong())
        val ranges = computeChunkRanges(input.durationMs, config)
        
        val hasSparsePass = config.sparseIntervalMs > config.intervalMs
        val total = ranges.size + (if (hasSparsePass) 1 else 0)

        synchronized(lock) {
            currentEntry = entry
            currentAllTimestamps = allTs
            currentChunkRanges = ranges
            totalChunksWithPasses = total
        }

        if (entry.isCompleteThrough(input.durationMs) || allTs.isEmpty() || ranges.isEmpty()) {
            _state.value = State.Done
            synchronized(lock) { nextChunkIndex = total }
            return
        }
        runChunk(input, chunkIndex = 0)
    }

    private suspend fun runChunk(input: Input, chunkIndex: Int) {
        val entry = synchronized(lock) { currentEntry } ?: return
        val ranges = synchronized(lock) { currentChunkRanges }
        val allTs = synchronized(lock) { currentAllTimestamps }
        val total = synchronized(lock) { totalChunksWithPasses }
        if (chunkIndex >= total) return

        val hasSparsePass = config.sparseIntervalMs > config.intervalMs
        val isSparse = hasSparsePass && chunkIndex == 0

        if (!isSparse && input.generationType == SeekPreviewGenerationType.SPARSE) {
            _state.value = State.Done
            synchronized(lock) { nextChunkIndex = total }
            return
        }
        
        val chunkTs = if (isSparse) {
            // First pass: 1 frame every sparseIntervalMs across the whole movie.
            val step = (config.sparseIntervalMs / config.intervalMs).coerceAtLeast(1)
            allTs.filterIndexed { i, _ -> i % step == 0 }
        } else {
            // Detailed passes: sequential chunks.
            val rangeIndex = if (hasSparsePass) chunkIndex - 1 else chunkIndex
            if (rangeIndex !in ranges.indices) return
            val range = ranges[rangeIndex]
            allTs.filter { it in range }
        }

        val missing = chunkTs.filterNot { entry.hasTimestamp(it) }
        val totalInChunk = chunkTs.size
        val alreadyDone = totalInChunk - missing.size
        val doneCounter = AtomicInteger(alreadyDone)
        emitGenerating(doneCounter.get(), totalInChunk, chunkIndex, total, isSparse)

        val shards = shardTimestamps(missing, config.workerCount)
        val onFrameDone: () -> Unit = {
            val d = doneCounter.incrementAndGet()
            emitGenerating(d, totalInChunk, chunkIndex, total, isSparse)
        }

        try {
            coroutineScope {
                for (shard in shards) {
                    if (shard.isEmpty()) continue
                    launch(workDispatcher) { runWorker(input, shard, entry, onFrameDone) }
                }
            }
        } finally {
            // Watermark only advances via normal detailed chunks to maintain
            // resume integrity. Sparse pass doesn't advance generatedThroughMs.
            if (!isSparse) {
                val watermark = lastContiguous(entry, allTs)
                runCatching { entry.commit(watermark) }
            } else {
                // Just flush the frames to disk.
                runCatching { entry.commit(entry.generatedThroughMs) }
            }
        }

        synchronized(lock) { nextChunkIndex = chunkIndex + 1 }

        if (!coroutineContext.isActive) return

        val hasMore = chunkIndex + 1 < total
        _state.value = if (!hasMore) {
            State.Done
        } else {
            State.ChunkDone(
                completedChunkIndex = chunkIndex,
                totalChunks = total,
                hasMoreChunks = true,
                generatedThroughMs = entry.generatedThroughMs,
                isSparse = isSparse
            )
        }
    }

    private suspend fun runWorker(
        input: Input,
        timestamps: List<Long>,
        entry: SeekPreviewThumbnailStore.MovieEntry,
        onFrameDone: () -> Unit
    ) {
        val grabber = grabberFactory.create()
        try {
            val opened = try {
                grabber.open(input.url, input.headers)
                true
            } catch (_: Throwable) {
                // Worker gave up silently; count its remaining frames as
                // attempted so the overall progress counter can reach total.
                repeat(timestamps.size) { onFrameDone() }
                false
            }
            if (!opened) return
            for (ts in timestamps) {
                if (!coroutineContext.isActive) break
                // runInterruptible lets withTimeoutOrNull interrupt the
                // blocking MMR call via thread interruption. For high-bitrate
                // HTTP streams a single I-frame can stall a worker for tens of
                // seconds; we skip the frame and move on rather than blocking
                // the whole shard.
                val jpeg = withTimeoutOrNull(FRAME_GRAB_TIMEOUT_MS) {
                    runInterruptible {
                        runCatching {
                            grabber.grab(ts, config.widthPx, config.heightPx, config.jpegQuality)
                        }.getOrNull()
                    }
                }
                if (jpeg != null) entry.put(ts, jpeg)
                onFrameDone()
            }
        } finally {
            runCatching { grabber.close() }
        }
    }

    private suspend fun probeSourceDuration(input: Input): Long? {
        val grabber = grabberFactory.create()
        return try {
            grabber.open(input.url, input.headers)
            grabber.sourceDurationMs()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { grabber.close() }
        }
    }

    private fun emitGenerating(done: Int, total: Int, chunkIndex: Int, totalChunks: Int, isSparse: Boolean) {
        _state.value = State.Generating(
            framesDone = done,
            framesTotal = total,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            isSparse = isSparse
        )
    }

    private fun closeCurrentEntryLocked() {
        val entry = currentEntry ?: return
        currentEntry = null
        runCatching { entry.close() }
    }

    companion object {

        // Skip frames that stall longer than this. High-bitrate I-frames over
        // HTTP can be 10–20 MB; 20 s is generous but prevents workers from
        // blocking indefinitely on a single timestamp.
        private const val FRAME_GRAB_TIMEOUT_MS = 20_000L

        // Sources whose container-reported duration is shorter than this are
        // treated as "not cached yet" error clips and skipped. Real movie/TV
        // content is always longer than 1 minute.
        internal const val SHORT_SOURCE_THRESHOLD_MS = 60_000L

        internal fun buildTimestamps(durationMs: Long, intervalMs: Long): List<Long> {
            if (durationMs <= 0L || intervalMs <= 0L) return emptyList()
            val list = ArrayList<Long>()
            var t = 0L
            while (t <= durationMs) {
                list.add(t)
                t += intervalMs
            }
            return list
        }

        /**
         * Walks [timestamps] in order; returns the last timestamp for which
         * the entry has a cached frame such that all prior timestamps also
         * have one. Zero if the first timestamp is already missing.
         */
        internal fun lastContiguous(
            entry: SeekPreviewThumbnailStore.MovieEntry,
            timestamps: List<Long>
        ): Long {
            var last = 0L
            var any = false
            for (ts in timestamps) {
                if (entry.hasTimestamp(ts)) {
                    last = ts
                    any = true
                } else {
                    break
                }
            }
            return if (any) last else 0L
        }

        internal fun isSupported(mimeHint: String?, url: String): Boolean {
            val mime = mimeHint?.trim()?.lowercase().orEmpty()
            if (mime.startsWith("application/vnd.apple.mpegurl") ||
                mime.startsWith("application/x-mpegurl") ||
                mime.startsWith("application/dash+xml")
            ) return false
            val path = url.substringBefore('?').lowercase()
            return !path.endsWith(".m3u8") && !path.endsWith(".mpd")
        }

        /**
         * Splits [durationMs] into successive chunk ranges covering the
         * full duration without gaps. Short videos collapse to a single
         * chunk. Chunks are [start..end] inclusive on both ends — each
         * timestamp belongs to exactly one chunk because we build a
         * half-open partition except for the last chunk which includes
         * [durationMs].
         */
        internal fun computeChunkRanges(durationMs: Long, cfg: Config): List<LongRange> {
            if (durationMs <= 0L) return emptyList()
            if (durationMs < cfg.shortVideoThresholdMs) return listOf(0L..durationMs)
            val n = maxOf(1, ceil(1.0 / cfg.chunkFraction).toInt())
            return (0 until n).map { i ->
                val start = durationMs * i / n
                val end = if (i == n - 1) durationMs
                else (durationMs * (i + 1) / n) - 1L
                start..end
            }
        }

        /**
         * Round-robin assigns [timestamps] across [shards] workers. Early
         * positions are distributed to worker 0 first, then 1, etc., so
         * the first frame is grabbed as soon as any worker starts.
         */
        internal fun shardTimestamps(timestamps: List<Long>, shards: Int): List<List<Long>> {
            if (timestamps.isEmpty()) return emptyList()
            if (shards <= 1) return listOf(timestamps)
            val result = Array(shards) { mutableListOf<Long>() }
            for ((i, ts) in timestamps.withIndex()) result[i % shards].add(ts)
            return result.map { it.toList() }
        }
    }
}
