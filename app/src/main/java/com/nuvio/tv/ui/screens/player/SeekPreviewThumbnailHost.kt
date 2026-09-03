package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.seekr.previews.compose.SeekrThumbnail
import java.util.concurrent.TimeUnit

private val ThumbnailWidth = 176.dp
private val ThumbnailHeight = 99.dp
private const val LingerAfterScrubMs = 1500L

@Composable
fun SeekPreviewThumbnailHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val track by viewModel.seekrTrack.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timeline by viewModel.playbackTimeline.collectAsStateWithLifecycle()

    val previewTs = uiState.pendingPreviewSeekPosition
    val scrubActive = previewTs != null || uiState.showSeekOverlay

    // Prevent flicker between repeated seek inputs.
    var lingerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(scrubActive) {
        if (scrubActive) {
            lingerVisible = true
        } else {
            delay(LingerAfterScrubMs)
            lingerVisible = false
        }
    }

    val displayTs = previewTs ?: timeline.currentPosition
    val duration = timeline.duration.coerceAtLeast(1L)
    val fraction = (displayTs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    AnimatedVisibility(
        visible = lingerVisible && track != null,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(ThumbnailHeight + 28.dp)
        ) {
            val left = previewOffset(maxWidth, ThumbnailWidth, fraction)

            Column(
                modifier = Modifier
                    .offset(x = left)
                    .width(ThumbnailWidth)
                    .align(Alignment.TopStart),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(ThumbnailWidth, ThumbnailHeight)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black)
                        .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                ) {
                    SeekrThumbnail(
                        track = track,
                        positionMs = displayTs,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(ThumbnailWidth, ThumbnailHeight)
                    )
                }
                Text(
                    text = formatScrubTime(displayTs),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun previewOffset(trackWidth: Dp, thumbWidth: Dp, fraction: Float): Dp {
    val centerX = trackWidth * fraction
    val leftUnclamped = centerX - thumbWidth / 2
    val maxLeft = (trackWidth - thumbWidth).coerceAtLeast(0.dp)
    return leftUnclamped.coerceIn(0.dp, maxLeft)
}

private fun formatScrubTime(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val hours = TimeUnit.MILLISECONDS.toHours(safe)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
