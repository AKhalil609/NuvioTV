package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.SeekPreviewCacheKey
import com.nuvio.tv.core.player.SeekPreviewGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val SEEK_PREVIEW_LOG_TAG = "SeekPreview"

/**
 * Mirrors the persisted "seek preview enabled" flag into the controller.
 * Called once from [PlayerRuntimeController]'s init block.
 */
internal fun PlayerRuntimeController.observeSeekPreviewSettings() {
    scope.launch {
        playerSettingsDataStore.playerSettings.collectLatest { settings ->
            val changed = seekPreviewEnabled != settings.seekPreviewEnabled ||
                seekPreviewGenerationType != settings.seekPreviewGenerationType
            seekPreviewEnabled = settings.seekPreviewEnabled
            seekPreviewGenerationType = settings.seekPreviewGenerationType
            seekPreviewCacheBudgetBytes = settings.seekPreviewCacheLimitMb.toLong() * 1024L * 1024L
            if (changed) {
                Log.i(SEEK_PREVIEW_LOG_TAG, "setting → enabled=${settings.seekPreviewEnabled} type=${settings.seekPreviewGenerationType}")
                if (settings.seekPreviewEnabled) {
                    // Pre-fetch streams so pickSeekPreviewSource has candidates
                    // ready when generation starts. No-op on cache hit.
                    loadSourceStreams(forceRefresh = false)
                }
            }
        }
    }
}

/**
 * Retries [startSeekPreviewIfReady] when the source stream list first
 * becomes non-empty. Handles the race where streams load after the
 * player opens and duration is already known.
 */
internal fun PlayerRuntimeController.observeSourceStreamsForSeekPreview() {
    scope.launch {
        _uiState
            .map { (it.sourceAllStreams + it.episodeAllStreams).isNotEmpty() }
            .distinctUntilChanged()
            .collect { nonEmpty ->
                if (!nonEmpty) return@collect
                // Streams just became available — retry start in case it was
                // deferred waiting for an MP4 source.
                if (!seekPreviewStartedForCurrentStream) {
                    val duration = _playbackTimeline.value.duration
                    if (duration > 0L) startSeekPreviewIfReady(duration)
                }
            }
    }
}

/**
 * Watches the generator for chunk completions and advances to the next
 * chunk only when the 5-minute window since playback start hasn't
 * elapsed. Run once for the controller's lifetime.
 */
internal fun PlayerRuntimeController.observeSeekPreviewGeneratorState() {
    seekPreviewStateObserverJob?.cancel()
    seekPreviewStateObserverJob = scope.launch {
        var lastLoggedFraction = -1
        seekPreviewGenerator.state.collect { state ->
            // Log transitions and coarse progress so we can see the generator
            // from logcat without spamming one line per frame.
            when (state) {
                is SeekPreviewGenerator.State.Probing,
                is SeekPreviewGenerator.State.Done,
                is SeekPreviewGenerator.State.Unsupported,
                is SeekPreviewGenerator.State.Failed,
                is SeekPreviewGenerator.State.ChunkDone -> {
                    Log.i(SEEK_PREVIEW_LOG_TAG, "state → $state")
                    lastLoggedFraction = -1
                }
                is SeekPreviewGenerator.State.Generating -> {
                    val frac = if (state.framesTotal > 0) {
                        (state.framesDone * 10 / state.framesTotal)
                    } else 0
                    if (frac != lastLoggedFraction) {
                        lastLoggedFraction = frac
                        Log.i(
                            SEEK_PREVIEW_LOG_TAG,
                            "generating chunk=${state.chunkIndex + 1}/${state.totalChunks} " +
                                "${state.framesDone}/${state.framesTotal}"
                        )
                    }
                }
                SeekPreviewGenerator.State.Idle -> Unit
            }

            if (state !is SeekPreviewGenerator.State.ChunkDone || !state.hasMoreChunks) return@collect
            seekPreviewGenerator.continueNextChunk(scope = scope)
        }
    }
}

/**
 * Kicks off generation for the current stream once duration is known.
 * No-op when disabled, duration unknown, or source is unsupported by
 * MMR (HLS/DASH/torrent stream).
 */
internal fun PlayerRuntimeController.startSeekPreviewIfReady(durationMs: Long) {
    if (!seekPreviewEnabled) {
        if (!seekPreviewDisabledLogged) {
            Log.i(SEEK_PREVIEW_LOG_TAG, "start skipped: setting disabled")
            seekPreviewDisabledLogged = true
        }
        return
    }
    if (seekPreviewStartedForCurrentStream) return
    if (durationMs <= 0L) {
        Log.i(SEEK_PREVIEW_LOG_TAG, "start skipped: durationMs=$durationMs not yet known")
        return
    }
    if (isTorrentStream) {
        Log.i(SEEK_PREVIEW_LOG_TAG, "start skipped: torrent stream")
        seekPreviewStartedForCurrentStream = true
        return
    }
    val url = currentStreamUrl.takeIf { it.isNotBlank() } ?: return

    val source = pickSeekPreviewSource(url)
    if (source == null) {
        val allStreams = _uiState.value.sourceAllStreams + _uiState.value.episodeAllStreams
        if (allStreams.isEmpty()) {
            // Streams not loaded yet — defer silently. The observeSourceStreamsForSeekPreview
            // observer will call us again once sourceAllStreams becomes non-empty.
            return
        }
        // Streams loaded but no MP4 source available — skip permanently.
        Log.i(SEEK_PREVIEW_LOG_TAG, "start skipped: no MP4 source in ${allStreams.size} streams")
        seekPreviewStartedForCurrentStream = true
        return
    }

    val key = SeekPreviewCacheKey.compute(
        SeekPreviewCacheKey.Input(
            videoHash = currentVideoHash,
            filename = currentFilename,
            videoSize = currentVideoSize,
            infoHash = currentInfoHash,
            fileIdx = currentFileIdx,
            url = url
        )
    )
    seekPreviewStartedForCurrentStream = true

    fun formatSize(bytes: Long?) = bytes?.let { "%.2f GB".format(it / 1_073_741_824.0) } ?: "unknown"
    fun formatDuration(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1_000
        return if (h > 0) "%dh %02dm %02ds".format(h, m, s) else "%dm %02ds".format(m, s)
    }
    Log.i(
        SEEK_PREVIEW_LOG_TAG,
        "start: key=$key\n" +
            "  watching:   duration=${formatDuration(durationMs)}  size=${formatSize(currentVideoSize)}\n" +
            "  thumbnails: duration=${formatDuration(durationMs)}  size=${formatSize(source.videoSize)}  source=${if (source.url == url) "main" else "alt=${source.qualityValue}p"}"
    )
    scope.launch(Dispatchers.IO) {
        runCatching { seekPreviewStore.trimLru(seekPreviewCacheBudgetBytes) }
    }
    seekPreviewGenerator.start(
        input = SeekPreviewGenerator.Input(
            key = key,
            url = source.url,
            headers = source.headers,
            durationMs = durationMs,
            mimeTypeHint = null, // pickSeekPreviewSource already filters for .mp4 only

            generationType = seekPreviewGenerationType
        ),
        scope = scope
    )
}

private data class SeekPreviewSource(
    val url: String,
    val headers: Map<String, String>,
    val qualityValue: Int,
    val videoSize: Long? = null
)

// Parses quality from any name string (filename or URL path segment).
private fun qualityFromName(name: String): Int {
    val s = name.lowercase()
    return when {
        s.contains("2160p") || s.contains("4k") || s.contains("uhd") -> 2160
        s.contains("1080p") || s.contains("1080i") -> 1080
        s.contains("720p") -> 720
        s.contains("480p") -> 480
        s.contains("360p") -> 360
        else -> -1
    }
}

private fun isBluRay(name: String): Boolean {
    val s = name.lowercase()
    return s.contains("bluray") || s.contains("blu-ray") || s.contains("bdrip") || s.contains("brrip")
}

// Lower score = more preferred.
// Priority: 1080p BluRay → 1080p → BluRay (any) → any MP4 (lowest quality)
private fun sourceScore(name: String, quality: Int): Int = when {
    quality == 1080 && isBluRay(name) -> 0
    quality == 1080 -> 1
    isBluRay(name) -> 2
    else -> 3
}

private fun PlayerRuntimeController.pickSeekPreviewSource(currentUrl: String): SeekPreviewSource? {
    val allStreams = _uiState.value.sourceAllStreams + _uiState.value.episodeAllStreams

    // Combined name: filename (most reliable) → URL last segment.
    fun streamName(stream: com.nuvio.tv.domain.model.Stream, url: String): String =
        stream.behaviorHints?.filename
            ?: url.substringBefore('?').substringAfterLast('/')

    fun effectiveQuality(stream: com.nuvio.tv.domain.model.Stream, url: String): Int =
        stream.qualityValue.takeIf { it > 0 } ?: qualityFromName(streamName(stream, url))

    fun isMp4(stream: com.nuvio.tv.domain.model.Stream, url: String): Boolean {
        val urlIsMp4 = url.substringBefore('?').lowercase().endsWith(".mp4")
        val filenameIsMp4 = stream.behaviorHints?.filename?.lowercase()?.endsWith(".mp4") == true
        return urlIsMp4 || filenameIsMp4
    }

    val alternative = allStreams
        .mapNotNull { stream ->
            val url = stream.getStreamUrl() ?: return@mapNotNull null
            if (url == currentUrl || !isMp4(stream, url) || effectiveQuality(stream, url) <= 0 || stream.isTorrent())
                return@mapNotNull null
            stream to url
        }
        .minWithOrNull(compareBy(
            { (s, u) -> sourceScore(streamName(s, u), effectiveQuality(s, u)) },
            { (s, u) -> effectiveQuality(s, u) }
        ))

    if (alternative != null) {
        val (stream, url) = alternative
        return SeekPreviewSource(
            url = url,
            headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
            qualityValue = effectiveQuality(stream, url),
            videoSize = stream.behaviorHints?.videoSize
        )
    }

    // Fall back to the main stream only if it is itself an MP4.
    val currentUrlIsMp4 = currentUrl.substringBefore('?').lowercase().endsWith(".mp4")
    if (currentUrlIsMp4) {
        return SeekPreviewSource(url = currentUrl, headers = currentHeaders, qualityValue = -1, videoSize = currentVideoSize)
    }

    return null
}

/**
 * Called when the current stream is being torn down (release or switch).
 * Resets per-stream state so the next stream can kick off its own run.
 */
internal fun PlayerRuntimeController.resetSeekPreviewForNewStream() {
    seekPreviewStartedForCurrentStream = false
    seekPreviewDisabledLogged = false
    seekPreviewGenerator.stop()
}

internal fun PlayerRuntimeController.releaseSeekPreview() {
    resetSeekPreviewForNewStream()
    seekPreviewStateObserverJob?.cancel()
    seekPreviewStateObserverJob = null
}

/** UI-thread–safe lookup for the overlay. */
internal fun PlayerRuntimeController.nearestSeekPreviewJpeg(tsMs: Long): ByteArray? =
    seekPreviewGenerator.nearestJpeg(tsMs)
