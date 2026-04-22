package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.core.player.SeekPreviewCacheKey
import com.nuvio.tv.core.player.SeekPreviewGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val SEEK_PREVIEW_LOG_TAG = "SeekPreview"

/**
 * Mirrors the persisted "seek preview enabled" flag into the controller.
 * Called once from [PlayerRuntimeController]'s init block.
 */
internal fun PlayerRuntimeController.observeSeekPreviewSettings() {
    scope.launch {
        playerSettingsDataStore.playerSettings.collectLatest { settings ->
            val changed = seekPreviewEnabled != settings.seekPreviewEnabled
            seekPreviewEnabled = settings.seekPreviewEnabled
            seekPreviewCacheBudgetBytes = settings.seekPreviewCacheLimitMb.toLong() * 1024L * 1024L
            if (changed) {
                Log.i(SEEK_PREVIEW_LOG_TAG, "setting → enabled=${settings.seekPreviewEnabled}")
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
    Log.i(
        SEEK_PREVIEW_LOG_TAG,
        "start: key=$key durationMs=$durationMs mimeHint=$currentStreamMimeType " +
            "urlHost=${url.substringBefore('?').substringAfter("://").substringBefore('/')}"
    )
    scope.launch(Dispatchers.IO) {
        runCatching { seekPreviewStore.trimLru(seekPreviewCacheBudgetBytes) }
    }
    seekPreviewGenerator.start(
        input = SeekPreviewGenerator.Input(
            key = key,
            url = url,
            headers = currentHeaders,
            durationMs = durationMs,
            mimeTypeHint = currentStreamMimeType
        ),
        scope = scope
    )
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
