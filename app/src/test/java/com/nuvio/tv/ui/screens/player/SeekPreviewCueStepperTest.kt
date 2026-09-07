package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekPreviewCueStepperTest {

    private val duration = 3_600_000L

    /** A 10s cue covering 37:40..37:50, matching the generator's default grid. */
    private val cue = SeekPreviewCue(startMs = 2_260_000L, endMs = 2_270_000L)

    @Test
    fun `single forward step lands on the next cue start`() {
        assertEquals(
            2_270_000L,
            SeekPreviewCueStepper.targetMs(cue, fromMs = 2_264_000L, deltaMs = 10_000L, durationMs = duration)
        )
    }

    @Test
    fun `single backward step lands on the previous cue start`() {
        assertEquals(
            2_250_000L,
            SeekPreviewCueStepper.targetMs(cue, fromMs = 2_264_000L, deltaMs = -10_000L, durationMs = duration)
        )
    }

    @Test
    fun `accelerated step moves a whole number of cues`() {
        assertEquals(
            2_320_000L,
            SeekPreviewCueStepper.targetMs(cue, fromMs = 2_260_000L, deltaMs = 60_000L, durationMs = duration)
        )
        assertEquals(
            2_200_000L,
            SeekPreviewCueStepper.targetMs(cue, fromMs = 2_260_000L, deltaMs = -60_000L, durationMs = duration)
        )
    }

    @Test
    fun `stepping from an exact cue start still moves a full cue`() {
        assertEquals(
            2_270_000L,
            SeekPreviewCueStepper.targetMs(cue, fromMs = cue.startMs, deltaMs = 10_000L, durationMs = duration)
        )
    }

    @Test
    fun `no resolved cue falls back to the raw delta`() {
        assertEquals(
            2_274_000L,
            SeekPreviewCueStepper.targetMs(null, fromMs = 2_264_000L, deltaMs = 10_000L, durationMs = duration)
        )
    }

    @Test
    fun `cue that does not cover the position falls back to the raw delta`() {
        assertEquals(
            110_000L,
            SeekPreviewCueStepper.targetMs(cue, fromMs = 100_000L, deltaMs = 10_000L, durationMs = duration)
        )
    }

    @Test
    fun `degenerate cue falls back to the raw delta`() {
        val empty = SeekPreviewCue(startMs = 1_000L, endMs = 1_000L)
        assertEquals(
            11_000L,
            SeekPreviewCueStepper.targetMs(empty, fromMs = 1_000L, deltaMs = 10_000L, durationMs = duration)
        )
    }

    @Test
    fun `targets stay inside the media bounds`() {
        val first = SeekPreviewCue(startMs = 0L, endMs = 10_000L)
        assertEquals(
            0L,
            SeekPreviewCueStepper.targetMs(first, fromMs = 4_000L, deltaMs = -30_000L, durationMs = duration)
        )
        val last = SeekPreviewCue(startMs = duration - 10_000L, endMs = duration)
        assertEquals(
            duration,
            SeekPreviewCueStepper.targetMs(last, fromMs = duration - 4_000L, deltaMs = 60_000L, durationMs = duration)
        )
    }

    @Test
    fun `the end of the track stays reachable once the cue no longer covers the position`() {
        val last = SeekPreviewCue(startMs = duration - 10_000L, endMs = duration)
        assertEquals(
            duration,
            SeekPreviewCueStepper.targetMs(last, fromMs = duration, deltaMs = 10_000L, durationMs = duration)
        )
    }

    @Test
    fun `alignment snaps a mid cue position onto the frame it is showing`() {
        assertEquals(
            2_260_000L,
            SeekPreviewCueStepper.alignedTargetMs(cue, pendingMs = 2_264_000L, durationMs = duration)
        )
    }

    @Test
    fun `alignment is a no-op once already on the cue start`() {
        assertNull(SeekPreviewCueStepper.alignedTargetMs(cue, pendingMs = cue.startMs, durationMs = duration))
    }

    @Test
    fun `alignment leaves positions outside the cue alone`() {
        assertNull(SeekPreviewCueStepper.alignedTargetMs(cue, pendingMs = 100_000L, durationMs = duration))
        assertNull(SeekPreviewCueStepper.alignedTargetMs(cue, pendingMs = cue.endMs, durationMs = duration))
        assertNull(SeekPreviewCueStepper.alignedTargetMs(null, pendingMs = 2_264_000L, durationMs = duration))
        assertNull(SeekPreviewCueStepper.alignedTargetMs(cue, pendingMs = null, durationMs = duration))
    }

    /**
     * A negative preview sync offset shifts every cue later in the playback timebase, so the
     * last cue overhangs the media end. Alignment must not drag the playhead a full cue back
     * from an end the user just reached, or the last cue's worth of the title — and the
     * next-episode flow behind it — becomes unreachable.
     */
    @Test
    fun `alignment does not pull the playhead back off the end of the media`() {
        val overhanging = SeekPreviewCue(startMs = duration - 8_000L, endMs = duration + 2_000L)
        assertNull(
            SeekPreviewCueStepper.alignedTargetMs(overhanging, pendingMs = duration, durationMs = duration)
        )
    }

    @Test
    fun `the end of the media stays reachable when the last cue overhangs it`() {
        val overhanging = SeekPreviewCue(startMs = duration - 8_000L, endMs = duration + 2_000L)
        var position = duration - 8_000L
        repeat(3) {
            val next = SeekPreviewCueStepper.targetMs(overhanging, position, 10_000L, duration)
            position = SeekPreviewCueStepper.alignedTargetMs(overhanging, next, duration) ?: next
        }
        assertEquals(duration, position)
    }

    /**
     * A positive preview sync offset shifts every cue earlier, so the first cue starts before
     * zero. Aligning into it would hand a negative position to the player while the label
     * clamps to 0:00 — the displayed and the committed position must never disagree.
     */
    @Test
    fun `alignment never produces a negative position`() {
        val underhanging = SeekPreviewCue(startMs = -2_000L, endMs = 8_000L)
        assertNull(SeekPreviewCueStepper.alignedTargetMs(underhanging, pendingMs = 0L, durationMs = duration))
        assertNull(SeekPreviewCueStepper.alignedTargetMs(underhanging, pendingMs = 4_000L, durationMs = duration))
    }

    @Test
    fun `the start of the media stays reachable when the first cue starts before zero`() {
        val underhanging = SeekPreviewCue(startMs = -2_000L, endMs = 8_000L)
        var position = 8_000L
        repeat(3) {
            val next = SeekPreviewCueStepper.targetMs(underhanging, position, -10_000L, duration)
            position = SeekPreviewCueStepper.alignedTargetMs(underhanging, next, duration) ?: next
        }
        assertEquals(0L, position)
    }

    @Test
    fun `repeated forward steps never stall on a non uniform grid`() {
        // Cue lengths vary, as they will once cue starts carry real keyframe times.
        val cues = listOf(
            SeekPreviewCue(0L, 9_000L),
            SeekPreviewCue(9_000L, 21_000L),
            SeekPreviewCue(21_000L, 28_000L),
            SeekPreviewCue(28_000L, 42_000L)
        )
        fun cueFor(positionMs: Long) = cues.lastOrNull { it.startMs <= positionMs }

        var position = 0L
        repeat(3) {
            val resolved = cueFor(position)
            val next = SeekPreviewCueStepper.targetMs(resolved, position, 10_000L, 42_000L)
            // Alignment is what keeps an extrapolated target honest.
            position = SeekPreviewCueStepper.alignedTargetMs(cueFor(next), next, 42_000L) ?: next
        }
        assertEquals(28_000L, position)
    }
}
