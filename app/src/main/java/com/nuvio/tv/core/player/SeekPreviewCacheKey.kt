package com.nuvio.tv.core.player

import java.security.MessageDigest

/**
 * Produces a stable content-identity hash for seek-preview cache lookup.
 *
 * Debrid URLs are time-limited and IP-locked, so they cannot be used as a
 * cache key — the same movie opened tomorrow gets a different URL. Prefer
 * identifiers that travel with the underlying file.
 *
 * Priority:
 *   1. videoHash (OpenSubtitles hash, stable per byte-identical file)
 *   2. filename + videoSize
 *   3. infoHash + fileIdx (torrent-cached sources)
 *   4. url (last resort; effectively session-scoped)
 */
object SeekPreviewCacheKey {

    data class Input(
        val videoHash: String?,
        val filename: String?,
        val videoSize: Long?,
        val infoHash: String?,
        val fileIdx: Int?,
        val url: String?
    )

    fun compute(input: Input): String {
        input.videoHash?.takeIf { it.isNotBlank() }?.let {
            return "vh-${it.lowercase()}"
        }
        val filename = input.filename?.takeIf { it.isNotBlank() }
        val size = input.videoSize?.takeIf { it > 0 }
        if (filename != null && size != null) {
            return "fs-${sha1("$filename|$size")}"
        }
        val info = input.infoHash?.takeIf { it.isNotBlank() }
        if (info != null) {
            val idx = input.fileIdx ?: 0
            return "ih-${sha1("${info.lowercase()}|$idx")}"
        }
        val url = input.url?.takeIf { it.isNotBlank() }
        if (url != null) {
            return "u-${sha1(url)}"
        }
        return "empty"
    }

    private fun sha1(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            hex.append(HEX[v ushr 4])
            hex.append(HEX[v and 0x0f])
        }
        return hex.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
