package com.nuvio.tv.core.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class SeekPreviewGeneratorTest {

    private lateinit var rootDir: File
    private lateinit var store: SeekPreviewThumbnailStore

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("nvst-gen-test").toFile()
        store = SeekPreviewThumbnailStore(rootDir)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    private class FakeGrabber(
        val onOpenFails: Boolean = false,
        val onOpenThrow: Throwable? = null,
        val onGrab: (tsMs: Long) -> ByteArray? = { "frame-$it".toByteArray() }
    ) : FrameGrabber {
        var openCalls = 0
        val grabCalls = mutableListOf<Long>()
        var closeCalls = 0

        override fun open(url: String, headers: Map<String, String>) {
            openCalls++
            if (onOpenFails) throw (onOpenThrow ?: IOException("open failed"))
        }

        override fun grab(tsMs: Long, widthPx: Int, heightPx: Int, jpegQuality: Int): ByteArray? {
            grabCalls.add(tsMs)
            return onGrab(tsMs)
        }

        override fun close() {
            closeCalls++
        }
    }

    /** Factory that tracks every grabber it created, for parallelism assertions. */
    private class GrabberFarm(
        val onOpenFails: (Int) -> Boolean = { false },
        val onGrab: (tsMs: Long) -> ByteArray? = { "frame-$it".toByteArray() }
    ) : FrameGrabberFactory {
        val all = mutableListOf<FakeGrabber>()
        override fun create(): FrameGrabber {
            val index = all.size
            val g = FakeGrabber(onOpenFails = onOpenFails(index), onGrab = onGrab)
            all.add(g)
            return g
        }

        fun allGrabs(): List<Long> = all.flatMap { it.grabCalls }.sorted()
    }

    private fun input(
        key: String = "k1",
        url: String = "https://example.com/video.mp4",
        durationMs: Long = 60_000L,
        mime: String? = null
    ) = SeekPreviewGenerator.Input(key, url, emptyMap(), durationMs, mime)

    private fun genWith(
        factory: FrameGrabberFactory,
        intervalMs: Int = 15_000,
        workers: Int = 6,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler
    ) = SeekPreviewGenerator(
        store = store,
        grabberFactory = factory,
        workDispatcher = UnconfinedTestDispatcher(scheduler),
        config = SeekPreviewGenerator.Config(
            intervalMs = intervalMs,
            workerCount = workers,
            chunkFraction = 0.25,
            shortVideoThresholdMs = 5 * 60_000L
        )
    )

    // ---- Short-video (single-chunk) happy paths ---------------------------

    @Test
    fun `short video runs as one chunk and reaches Done`() = runTest {
        val farm = GrabberFarm()
        val gen = genWith(farm, scheduler = testScheduler)

        gen.start(input(durationMs = 60_000L), scope = backgroundScope).join()

        assertEquals(SeekPreviewGenerator.State.Done, gen.state.value)
        assertEquals(listOf(0L, 15_000L, 30_000L, 45_000L, 60_000L), farm.allGrabs())
        // All created grabbers must have been released.
        assertTrue(farm.all.all { it.closeCalls == 1 })
    }

    @Test
    fun `nearestJpeg returns closest cached frame after generation`() = runTest {
        val gen = genWith(GrabberFarm(), scheduler = testScheduler)
        gen.start(input(durationMs = 60_000L), scope = backgroundScope).join()

        val bytes = gen.nearestJpeg(7_000L)
        assertNotNull(bytes)
        val content = String(bytes!!)
        assertTrue("got $content", content == "frame-0" || content == "frame-15000")
    }

    // ---- Format filtering ------------------------------------------------

    @Test
    fun `HLS url surfaces Unsupported without creating a grabber`() = runTest {
        val farm = GrabberFarm()
        val gen = genWith(farm, scheduler = testScheduler)
        gen.start(
            input(url = "https://example.com/stream.m3u8?token=x", durationMs = 60_000L),
            scope = backgroundScope
        ).join()
        assertEquals(SeekPreviewGenerator.State.Unsupported, gen.state.value)
        assertTrue(farm.all.isEmpty())
    }

    @Test
    fun `DASH mime hint is unsupported even with a neutral url`() = runTest {
        val farm = GrabberFarm()
        val gen = genWith(farm, scheduler = testScheduler)
        gen.start(
            input(url = "https://example.com/manifest", mime = "application/dash+xml"),
            scope = backgroundScope
        ).join()
        assertEquals(SeekPreviewGenerator.State.Unsupported, gen.state.value)
        assertTrue(farm.all.isEmpty())
    }

    @Test
    fun `invalid duration surfaces Failed`() = runTest {
        val gen = genWith(GrabberFarm(), scheduler = testScheduler)
        gen.start(input(durationMs = 0L), scope = backgroundScope).join()
        assertTrue(gen.state.value is SeekPreviewGenerator.State.Failed)
    }

    // ---- Worker failure modes -------------------------------------------

    @Test
    fun `per-frame failure keeps going and persists successful frames`() = runTest {
        val farm = GrabberFarm(onGrab = { ts -> if (ts == 30_000L) null else "frame-$ts".toByteArray() })
        val gen = genWith(farm, scheduler = testScheduler)
        gen.start(input(durationMs = 60_000L), scope = backgroundScope).join()

        val peek = store.peek("k1")
        assertNotNull(peek)
        assertNotNull(peek!!.nearest(45_000L, maxDeltaMs = 1L))
        assertNull(peek.nearest(30_000L, maxDeltaMs = 1L))
    }

    @Test
    fun `watermark stops at first gap so resume retries the hole`() = runTest {
        val farm = GrabberFarm(onGrab = { ts -> if (ts == 15_000L) null else "frame-$ts".toByteArray() })
        val gen = genWith(farm, scheduler = testScheduler)
        gen.start(input(durationMs = 60_000L), scope = backgroundScope).join()

        val peek = store.peek("k1")
        assertNotNull(peek)
        assertEquals(0L, peek!!.generatedThroughMs)
    }

    @Test
    fun `one worker open failure leaves others to complete their shards`() = runTest {
        // 12 timestamps, 6 workers → round-robin gives 2 frames per worker.
        // Worker 0 fails to open; all others succeed. Net: 10 frames grabbed.
        val farm = GrabberFarm(
            onOpenFails = { idx -> idx == 0 },
            onGrab = { ts -> "frame-$ts".toByteArray() }
        )
        val gen = genWith(farm, scheduler = testScheduler)
        gen.start(input(durationMs = 180_000L), scope = backgroundScope).join() // 13 timestamps (0..180000 step 15000)

        val peek = store.peek("k1")!!
        // Worker 0's shard (round-robin) got no data; its timestamps are missing.
        assertNull(peek.nearest(0L, maxDeltaMs = 1L)) // ts=0 belongs to worker 0
        // Worker 1's shard (ts=15000) has data.
        assertNotNull(peek.nearest(15_000L, maxDeltaMs = 1L))
        // Every grabber was created and released (even the one that failed to open).
        assertTrue(farm.all.all { it.closeCalls == 1 })
        // State reaches Done regardless (single-chunk short video).
        assertEquals(SeekPreviewGenerator.State.Done, gen.state.value)
    }

    // ---- Resume ----------------------------------------------------------

    @Test
    fun `resume skips already cached timestamps`() = runTest {
        store.open("k1", 160, 90, 15_000, 60_000L).apply {
            put(0L, "pre-0".toByteArray())
            put(15_000L, "pre-15000".toByteArray())
            commit(15_000L)
            close()
        }

        val farm = GrabberFarm()
        val gen = genWith(farm, scheduler = testScheduler)
        gen.start(input(durationMs = 60_000L), scope = backgroundScope).join()

        assertEquals(listOf(30_000L, 45_000L, 60_000L), farm.allGrabs())
        assertEquals(SeekPreviewGenerator.State.Done, gen.state.value)
    }

    @Test
    fun `already complete store short-circuits to Done with no workers`() = runTest {
        store.open("k1", 160, 90, 15_000, 30_000L).apply {
            put(0L, "a".toByteArray())
            put(15_000L, "b".toByteArray())
            put(30_000L, "c".toByteArray())
            commit(30_000L)
            close()
        }
        val farm = GrabberFarm()
        val gen = genWith(farm, scheduler = testScheduler)
        gen.start(input(durationMs = 30_000L), scope = backgroundScope).join()

        assertEquals(SeekPreviewGenerator.State.Done, gen.state.value)
        assertTrue(farm.all.isEmpty())
    }

    // ---- Chunking --------------------------------------------------------

    @Test
    fun `long video runs only chunk 0 on start and emits ChunkDone`() = runTest {
        // 20 min duration, 15s interval → 81 timestamps. 4 chunks at 25% each.
        val farm = GrabberFarm()
        val gen = genWith(farm, scheduler = testScheduler)
        val durationMs = 20 * 60_000L

        gen.start(input(durationMs = durationMs), scope = backgroundScope).join()

        val state = gen.state.value
        assertTrue("state=$state", state is SeekPreviewGenerator.State.ChunkDone)
        val done = state as SeekPreviewGenerator.State.ChunkDone
        assertEquals(0, done.completedChunkIndex)
        assertEquals(4, done.totalChunks)
        assertTrue(done.hasMoreChunks)

        // Only chunk-0 timestamps should have been grabbed. Chunk 0 covers
        // [0, durationMs/4 - 1] = [0, 299_999]. Timestamps 0, 15000, ..., 285000.
        assertTrue(farm.allGrabs().all { it <= 285_000L })
        assertTrue(farm.allGrabs().contains(0L))
        assertTrue(farm.allGrabs().contains(285_000L))
    }

    @Test
    fun `continueNextChunk advances one chunk at a time to Done`() = runTest {
        val farm = GrabberFarm()
        val gen = genWith(farm, scheduler = testScheduler)
        val durationMs = 20 * 60_000L

        gen.start(input(durationMs = durationMs), scope = backgroundScope).join()
        val s1 = gen.state.value as SeekPreviewGenerator.State.ChunkDone
        assertEquals(0, s1.completedChunkIndex)

        gen.continueNextChunk(scope = backgroundScope)!!.join()
        val s2 = gen.state.value as SeekPreviewGenerator.State.ChunkDone
        assertEquals(1, s2.completedChunkIndex)

        gen.continueNextChunk(scope = backgroundScope)!!.join()
        val s3 = gen.state.value as SeekPreviewGenerator.State.ChunkDone
        assertEquals(2, s3.completedChunkIndex)

        gen.continueNextChunk(scope = backgroundScope)!!.join()
        assertEquals(SeekPreviewGenerator.State.Done, gen.state.value)

        // One further call after Done returns null.
        assertNull(gen.continueNextChunk(scope = backgroundScope))
    }

    @Test
    fun `continueNextChunk returns null before any start`() = runTest {
        val gen = genWith(GrabberFarm(), scheduler = testScheduler)
        assertNull(gen.continueNextChunk(scope = backgroundScope))
    }

    @Test
    fun `continueNextChunk returns null while a run is already active`() = runTest {
        // StandardTestDispatcher defers dispatch so we can observe the job in
        // its Active state before it actually runs.
        val farm = GrabberFarm()
        val gen = SeekPreviewGenerator(
            store = store,
            grabberFactory = farm,
            workDispatcher = StandardTestDispatcher(testScheduler),
            config = SeekPreviewGenerator.Config(
                intervalMs = 15_000,
                workerCount = 6,
                chunkFraction = 0.25,
                shortVideoThresholdMs = 5 * 60_000L
            )
        )
        val durationMs = 20 * 60_000L
        val first = gen.start(input(durationMs = durationMs), scope = backgroundScope)
        // Job exists and is Active; continueNextChunk must refuse.
        assertNull(gen.continueNextChunk(scope = backgroundScope))
        first.join()
        // After completion the next call is accepted.
        assertNotNull(gen.continueNextChunk(scope = backgroundScope))
    }

    @Test
    fun `stop clears state to Idle and releases grabbers`() = runTest {
        val farm = GrabberFarm()
        val gen = genWith(farm, scheduler = testScheduler)
        val job = gen.start(input(durationMs = 60_000L), scope = backgroundScope)
        job.join()
        assertEquals(SeekPreviewGenerator.State.Done, gen.state.value)

        gen.stop()
        assertEquals(SeekPreviewGenerator.State.Idle, gen.state.value)
        assertTrue(farm.all.all { it.closeCalls == 1 })
        assertNull(gen.nearestJpeg(0L))
    }

    @Test
    fun `calling start twice cancels the first run and replaces it`() = runTest {
        val farm = GrabberFarm()
        val gen = genWith(farm, scheduler = testScheduler)

        gen.start(input(key = "k1", durationMs = 60_000L), scope = backgroundScope).join()
        gen.start(input(key = "k2", durationMs = 60_000L), scope = backgroundScope).join()

        assertEquals(SeekPreviewGenerator.State.Done, gen.state.value)
        // We should see frames grabbed for two distinct sessions: grabbers from
        // both runs have been closed.
        assertTrue(farm.all.all { it.closeCalls == 1 })
    }

    // ---- Pure helpers ----------------------------------------------------

    @Test
    fun `buildTimestamps covers zero through duration at interval`() {
        assertEquals(
            listOf(0L, 15_000L, 30_000L, 45_000L, 60_000L),
            SeekPreviewGenerator.buildTimestamps(60_000L, 15_000L)
        )
    }

    @Test
    fun `buildTimestamps rounds down when duration is not a multiple of interval`() {
        assertEquals(
            listOf(0L, 15_000L, 30_000L, 45_000L),
            SeekPreviewGenerator.buildTimestamps(50_000L, 15_000L)
        )
    }

    @Test
    fun `buildTimestamps guards against zero or negative inputs`() {
        assertTrue(SeekPreviewGenerator.buildTimestamps(0L, 15_000L).isEmpty())
        assertTrue(SeekPreviewGenerator.buildTimestamps(60_000L, 0L).isEmpty())
        assertTrue(SeekPreviewGenerator.buildTimestamps(-1L, 15_000L).isEmpty())
    }

    @Test
    fun `isSupported filters hls and dash by extension and mime hint`() {
        val s = SeekPreviewGenerator.Companion
        assertTrue(s.isSupported(null, "https://x/y/file.mp4"))
        assertTrue(s.isSupported(null, "https://x/y/file.mkv"))
        assertTrue(s.isSupported("video/mp4", "https://x/y/file"))
        assertFalse(s.isSupported(null, "https://x/y/file.m3u8"))
        assertFalse(s.isSupported(null, "https://x/y/FILE.MPD"))
        assertFalse(s.isSupported(null, "https://x/y/file.M3U8?token=1"))
        assertFalse(s.isSupported("application/x-mpegURL", "https://x/y/file"))
        assertFalse(s.isSupported("application/dash+xml", "https://x/y/file"))
    }

    @Test
    fun `computeChunkRanges returns single chunk for short videos`() {
        val cfg = SeekPreviewGenerator.Config(shortVideoThresholdMs = 5 * 60_000L, chunkFraction = 0.25)
        val ranges = SeekPreviewGenerator.computeChunkRanges(3 * 60_000L, cfg)
        assertEquals(1, ranges.size)
        assertEquals(0L..(3 * 60_000L), ranges.first())
    }

    @Test
    fun `computeChunkRanges splits long videos into four by default`() {
        val cfg = SeekPreviewGenerator.Config(shortVideoThresholdMs = 5 * 60_000L, chunkFraction = 0.25)
        val ranges = SeekPreviewGenerator.computeChunkRanges(20 * 60_000L, cfg)
        assertEquals(4, ranges.size)
        // Full coverage, adjacent, ending at durationMs.
        assertEquals(0L, ranges.first().first)
        assertEquals(20 * 60_000L, ranges.last().last)
        for (i in 0 until ranges.size - 1) {
            assertEquals(ranges[i].last + 1, ranges[i + 1].first)
        }
    }

    @Test
    fun `computeChunkRanges handles a one-third fraction with three chunks`() {
        val cfg = SeekPreviewGenerator.Config(shortVideoThresholdMs = 5 * 60_000L, chunkFraction = 1.0 / 3.0)
        val ranges = SeekPreviewGenerator.computeChunkRanges(30 * 60_000L, cfg)
        assertEquals(3, ranges.size)
        assertEquals(30 * 60_000L, ranges.last().last)
    }

    @Test
    fun `shardTimestamps round-robins across workers`() {
        val shards = SeekPreviewGenerator.shardTimestamps(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L), shards = 3)
        assertEquals(listOf(0L, 3L, 6L), shards[0])
        assertEquals(listOf(1L, 4L), shards[1])
        assertEquals(listOf(2L, 5L), shards[2])
    }

    @Test
    fun `shardTimestamps with one shard returns the input intact`() {
        assertEquals(
            listOf(listOf(0L, 1L, 2L)),
            SeekPreviewGenerator.shardTimestamps(listOf(0L, 1L, 2L), shards = 1)
        )
    }

    @Test
    fun `shardTimestamps on empty input is empty`() {
        assertTrue(SeekPreviewGenerator.shardTimestamps(emptyList(), shards = 6).isEmpty())
    }
}
