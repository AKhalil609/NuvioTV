package com.nuvio.tv.core.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * [FrameGrabber] backed by [MediaMetadataRetriever]. Designed for
 * direct MP4 sources over HTTP Range requests (debrid-resolved URLs).
 * HLS/DASH manifests are not supported; callers must filter them out.
 */
class MmrFrameGrabber : FrameGrabber {

    private var retriever: MediaMetadataRetriever? = null

    override fun open(url: String, headers: Map<String, String>) {
        close()
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(url, headers)
            retriever = mmr
        } catch (t: Throwable) {
            runCatching { mmr.release() }
            throw t
        }
    }

    override fun grab(tsMs: Long, widthPx: Int, heightPx: Int, jpegQuality: Int): ByteArray? {
        val mmr = retriever ?: return null
        val timeUs = tsMs * 1000L
        // OPTION_CLOSEST_SYNC snaps to the nearest keyframe — no forward decoding
        // required. On API 27+ getScaledFrameAtTime decodes directly to the target
        // resolution, skipping a full-resolution intermediate (2–5× faster).
        val raw: Bitmap = try {
            val scaled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching {
                    mmr.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        widthPx,
                        heightPx
                    )
                }.getOrNull()
            } else null
            scaled ?: mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            return null
        } ?: return null

        // Non-ARGB_8888 configs (HDR10 MP4 → RGBA_F16, GPU-backed → HARDWARE,
        // 10-bit wide-gamut → RGBA_1010102) must be converted before JPEG compression.
        // Canvas draw applies color-managed gamma + gamut conversion to sRGB ARGB_8888.
        val frame: Bitmap = when (raw.config) {
            Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565 -> raw
            else -> {
                val sdr = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
                try {
                    android.graphics.Canvas(sdr).drawBitmap(raw, 0f, 0f, null)
                } catch (_: Throwable) {
                    sdr.recycle()
                    raw.recycle()
                    return null
                }
                raw.recycle()
                sdr
            }
        }

        // getScaledFrameAtTime may return at or near the requested
        // resolution but codec constraints can round. Skip the Java scale
        // pass only when dimensions match exactly; otherwise fall through.
        val scaled: Bitmap = if (frame.width == widthPx && frame.height == heightPx) {
            frame
        } else {
            try {
                Bitmap.createScaledBitmap(frame, widthPx, heightPx, true)
            } catch (_: Throwable) {
                frame.recycle()
                return null
            }
        }
        if (scaled !== frame) frame.recycle()
        val out = ByteArrayOutputStream(widthPx * heightPx / 4)
        val ok = try {
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        } catch (_: Throwable) {
            false
        } finally {
            scaled.recycle()
        }
        return if (ok) out.toByteArray() else null
    }

    override fun sourceDurationMs(): Long? =
        retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

    override fun close() {
        val mmr = retriever ?: return
        retriever = null
        runCatching { mmr.release() }
    }

    companion object Factory : FrameGrabberFactory {
        override fun create(): FrameGrabber = MmrFrameGrabber()
    }
}
