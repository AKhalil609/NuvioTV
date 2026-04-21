package com.nuvio.tv.core.player

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SeekPreviewThumbnailStoreTest {

    private lateinit var rootDir: File
    private lateinit var store: SeekPreviewThumbnailStore

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("nvst-test").toFile()
        store = SeekPreviewThumbnailStore(rootDir)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    private fun jpeg(size: Int, fill: Byte = 0x5a.toByte()) = ByteArray(size) { fill }

    @Test
    fun `put and nearest returns the exact entry`() {
        val entry = store.open("vh-abc", 160, 90, 15_000, durationMs = 120_000L)
        val bytes = jpeg(1024)
        entry.put(30_000L, bytes)
        val found = entry.nearest(30_000L)
        assertNotNull(found)
        assertArrayEquals(bytes, found)
    }

    @Test
    fun `nearest returns closest entry on either side`() {
        val entry = store.open("vh-abc", 160, 90, 15_000, 120_000L)
        entry.put(0L, jpeg(8, 1))
        entry.put(15_000L, jpeg(8, 2))
        entry.put(30_000L, jpeg(8, 3))

        assertEquals(2.toByte(), entry.nearest(16_000L)!![0])
        assertEquals(3.toByte(), entry.nearest(24_000L)!![0])
        assertEquals(1.toByte(), entry.nearest(5_000L)!![0])
    }

    @Test
    fun `nearest respects maxDeltaMs`() {
        val entry = store.open("vh-abc", 160, 90, 15_000, 120_000L)
        entry.put(0L, jpeg(4))
        assertNull(entry.nearest(60_000L, maxDeltaMs = 30_000L))
        assertNotNull(entry.nearest(10_000L, maxDeltaMs = 30_000L))
    }

    @Test
    fun `commit persists entries and reopening restores them`() {
        val original = jpeg(256, 0x11.toByte())
        val key = "vh-commit"
        store.open(key, 160, 90, 15_000, 120_000L).apply {
            put(45_000L, original)
            commit(generatedThroughMs = 60_000L)
            close()
        }

        val reopened = store.open(key, 160, 90, 15_000, 120_000L)
        assertEquals(60_000L, reopened.generatedThroughMs)
        val bytes = reopened.nearest(45_000L)
        assertNotNull(bytes)
        assertArrayEquals(original, bytes)
    }

    @Test
    fun `reopen with different dimensions discards cached file`() {
        val key = "vh-dim"
        store.open(key, 160, 90, 15_000, 120_000L).apply {
            put(0L, jpeg(8))
            commit(15_000L)
            close()
        }
        val reopened = store.open(key, 320, 180, 15_000, 120_000L)
        assertEquals(0L, reopened.generatedThroughMs)
        assertNull(reopened.nearest(0L))
    }

    @Test
    fun `peek returns null when no file exists`() {
        assertNull(store.peek("vh-missing"))
    }

    @Test
    fun `peek returns populated entry for cached movie`() {
        val key = "vh-peek"
        store.open(key, 160, 90, 15_000, 120_000L).apply {
            put(30_000L, jpeg(16, 0x22.toByte()))
            commit(60_000L)
            close()
        }
        val peeked = store.peek(key)
        assertNotNull(peeked)
        assertEquals(60_000L, peeked!!.generatedThroughMs)
        assertNotNull(peeked.nearest(30_000L))
    }

    @Test
    fun `isCompleteThrough reflects generator watermark`() {
        val entry = store.open("vh-c", 160, 90, 15_000, 120_000L)
        assertFalse(entry.isCompleteThrough(30_000L))
        entry.put(15_000L, jpeg(8))
        entry.commit(30_000L)
        assertTrue(entry.isCompleteThrough(30_000L))
        assertTrue(entry.isCompleteThrough(29_000L))
        assertFalse(entry.isCompleteThrough(30_001L))
    }

    @Test
    fun `trimLru evicts oldest accessed files first`() {
        repeat(5) { i ->
            val entry = store.open("vh-$i", 160, 90, 15_000, 120_000L)
            entry.put(0L, jpeg(10_000, i.toByte()))
            entry.commit(15_000L)
            entry.close()
            // stagger lastModified so ordering is stable on fast filesystems
            File(rootDir, "vh-$i.nvst").setLastModified(1_000_000_000L + i * 1000L)
        }

        val before = store.totalBytes()
        assertTrue(before > 0)

        // Ask for a cap that forces eviction of roughly three-fifths of the files.
        store.trimLru(maxBytes = before * 2 / 5)

        val remainingNames = rootDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertFalse(remainingNames.contains("vh-0.nvst"))
        assertFalse(remainingNames.contains("vh-1.nvst"))
        assertTrue(remainingNames.contains("vh-4.nvst"))
        assertTrue(store.totalBytes() <= before * 2 / 5)
    }

    @Test
    fun `trimLru is a no-op when already under cap`() {
        val entry = store.open("vh-k", 160, 90, 15_000, 120_000L)
        entry.put(0L, jpeg(100))
        entry.commit(15_000L)
        entry.close()

        val before = store.totalBytes()
        store.trimLru(maxBytes = before * 10)
        assertEquals(before, store.totalBytes())
    }

    @Test
    fun `close flushes dirty state without explicit commit`() {
        val key = "vh-autoflush"
        store.open(key, 160, 90, 15_000, 120_000L).apply {
            put(0L, jpeg(32, 0x77.toByte()))
            close()
        }
        val reopened = store.open(key, 160, 90, 15_000, 120_000L)
        assertNotNull(reopened.nearest(0L))
    }

    @Test
    fun `hasTimestamp reports exact keys only`() {
        val entry = store.open("vh-has", 160, 90, 15_000, 120_000L)
        entry.put(15_000L, jpeg(8))
        assertTrue(entry.hasTimestamp(15_000L))
        assertFalse(entry.hasTimestamp(0L))
        assertFalse(entry.hasTimestamp(16_000L))
    }

    @Test
    fun `corrupted file is treated as a miss and overwritten on reopen`() {
        val key = "vh-corrupt"
        val file = File(rootDir, "$key.nvst")
        file.writeBytes(ByteArray(10) { 0xff.toByte() }) // garbage bytes

        val reopened = store.open(key, 160, 90, 15_000, 120_000L)
        assertEquals(0L, reopened.generatedThroughMs)
        reopened.put(0L, jpeg(8))
        reopened.commit(15_000L)
        reopened.close()

        val reread = store.open(key, 160, 90, 15_000, 120_000L)
        assertEquals(15_000L, reread.generatedThroughMs)
    }
}
