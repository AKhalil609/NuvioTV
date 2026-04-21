package com.nuvio.tv.core.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * [FrameGrabber] backed by [MediaMetadataRetriever]. Works well with
 * direct MP4/MKV sources that support HTTP Range requests — which is the
 * expected shape for debrid-resolved URLs. Adaptive manifests
 * (HLS/DASH) are not supported by MMR; callers should filter them out
 * before constructing a grabber.
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
        // OPTION_CLOSEST decodes forward from the previous keyframe to the
        // exact requested position. Slower per frame than OPTION_CLOSEST_SYNC
        // but gives a thumbnail whose content matches the scrub timestamp.
        //
        // On API 27+ we ask the decoder to emit at our target resolution
        // directly — skips the full-resolution intermediate (2–5× faster
        // on hardware decoders) and often uses hardware-assisted scaling.
        val frame: Bitmap = try {
            val scaled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching {
                    mmr.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        widthPx,
                        heightPx
                    )
                }.getOrNull()
            } else null
            scaled ?: mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Throwable) {
            return null
        } ?: return null
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

    override fun close() {
        val mmr = retriever ?: return
        retriever = null
        runCatching { mmr.release() }
    }

    companion object Factory : FrameGrabberFactory {
        override fun create(): FrameGrabber = MmrFrameGrabber()
    }
}
