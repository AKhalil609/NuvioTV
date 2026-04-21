package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewCacheKeyTest {

    @Test
    fun `videoHash takes priority and is lowercased`() {
        val key = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(
                videoHash = "ABCD1234EF567890",
                filename = "movie.mkv",
                videoSize = 42L,
                infoHash = "deadbeef",
                fileIdx = 1,
                url = "https://example.com/movie.mkv"
            )
        )
        assertEquals("vh-abcd1234ef567890", key)
    }

    @Test
    fun `falls back to filename and size when videoHash is blank`() {
        val key = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(
                videoHash = "",
                filename = "The.Movie.2024.mkv",
                videoSize = 1_234_567_890L,
                infoHash = null,
                fileIdx = null,
                url = "https://example.com/x"
            )
        )
        assertTrue(key.startsWith("fs-"))
    }

    @Test
    fun `falls back to infoHash plus fileIdx when no filename and size`() {
        val key = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(
                videoHash = null,
                filename = null,
                videoSize = null,
                infoHash = "DEADBEEF",
                fileIdx = 3,
                url = null
            )
        )
        assertTrue(key.startsWith("ih-"))
    }

    @Test
    fun `url is last resort`() {
        val key = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(
                videoHash = null,
                filename = null,
                videoSize = null,
                infoHash = null,
                fileIdx = null,
                url = "https://example.com/path/file.mp4?token=abc"
            )
        )
        assertTrue(key.startsWith("u-"))
    }

    @Test
    fun `url fallback is stable for same url`() {
        val input = SeekPreviewCacheKey.Input(
            videoHash = null, filename = null, videoSize = null,
            infoHash = null, fileIdx = null,
            url = "https://example.com/video.mkv"
        )
        assertEquals(SeekPreviewCacheKey.compute(input), SeekPreviewCacheKey.compute(input))
    }

    @Test
    fun `url fallback differs from filename-size fallback even for related inputs`() {
        val byUrl = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(null, null, null, null, null, "video.mkv")
        )
        val byFs = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(null, "video.mkv", 100L, null, null, null)
        )
        assertNotEquals(byUrl, byFs)
    }

    @Test
    fun `fileIdx defaults to zero when null but infoHash present`() {
        val withExplicitZero = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(null, null, null, "abc", 0, null)
        )
        val withNullIdx = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(null, null, null, "abc", null, null)
        )
        assertEquals(withExplicitZero, withNullIdx)
    }

    @Test
    fun `empty input produces a deterministic non-blank key`() {
        val key = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(null, null, null, null, null, null)
        )
        assertEquals("empty", key)
    }

    @Test
    fun `zero and negative sizes are treated as missing`() {
        val withZero = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(null, "x.mkv", 0L, null, null, "u")
        )
        val withNegative = SeekPreviewCacheKey.compute(
            SeekPreviewCacheKey.Input(null, "x.mkv", -1L, null, null, "u")
        )
        assertTrue(withZero.startsWith("u-"))
        assertTrue(withNegative.startsWith("u-"))
    }
}
