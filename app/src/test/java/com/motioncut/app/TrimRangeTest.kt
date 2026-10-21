package com.motioncut.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TrimRangeTest {

    private val duration = 10_000L

    private fun full(durationMs: Long = duration) = TrimRange.full(durationMs)!!

    // --- initial state ---------------------------------------------------

    @Test
    fun fullRangeSelectsTheWholeVideo() {
        val range = full()

        assertEquals(0L, range.startMs)
        assertEquals(duration, range.endMs)
        assertEquals(duration, range.lengthMs)
    }

    @Test
    fun zeroOrUnknownDurationGivesNoRange() {
        assertNull(TrimRange.full(0L))
        assertNull(TrimRange.full(-1L))
        // Media3's C.TIME_UNSET
        assertNull(TrimRange.full(Long.MIN_VALUE + 1))
    }

    @Test
    fun switchingVideosStartsFromTheFullRangeOfTheNewVideo() {
        val narrowed = full().withStart(4_000L).withEnd(6_000L)
        val next = full(30_000L)

        assertEquals(2_000L, narrowed.lengthMs)
        assertEquals(0L, next.startMs)
        assertEquals(30_000L, next.endMs)
    }

    // --- moving the handles ---------------------------------------------

    @Test
    fun startCanMoveForward() {
        val range = full().withStart(2_500L)

        assertEquals(2_500L, range.startMs)
        assertEquals(duration, range.endMs)
    }

    @Test
    fun endCanMoveBackward() {
        val range = full().withEnd(7_500L)

        assertEquals(0L, range.startMs)
        assertEquals(7_500L, range.endMs)
    }

    @Test
    fun bothHandlesCanBeMovedRepeatedly() {
        var range = full()
        range = range.withStart(1_000L)
        range = range.withEnd(9_000L)
        range = range.withStart(3_000L)
        range = range.withEnd(5_000L)
        range = range.withStart(500L)
        range = range.withEnd(8_000L)

        assertEquals(500L, range.startMs)
        assertEquals(8_000L, range.endMs)
    }

    @Test
    fun movingOneHandleNeverMovesTheOther() {
        val range = full().withStart(2_000L).withEnd(8_000L)

        assertEquals(8_000L, range.withStart(3_000L).endMs)
        assertEquals(2_000L, range.withEnd(7_000L).startMs)
    }

    @Test
    fun startCannotPassTheEnd() {
        val range = full().withEnd(5_000L).withStart(9_000L)

        assertEquals(5_000L - MIN_CLIP_MS, range.startMs)
        assertEquals(5_000L, range.endMs)
    }

    @Test
    fun endCannotGoBeforeTheStart() {
        val range = full().withStart(5_000L).withEnd(1_000L)

        assertEquals(5_000L, range.startMs)
        assertEquals(5_000L + MIN_CLIP_MS, range.endMs)
    }

    @Test
    fun startCannotBeNegative() {
        assertEquals(0L, full().withStart(-500L).startMs)
    }

    @Test
    fun endCannotExceedTheDuration() {
        assertEquals(duration, full().withEnd(duration + 5_000L).endMs)
    }

    // --- short videos -----------------------------------------------------

    @Test
    fun videoShorterThanTheMinimumClipOnlyAllowsTheWholeVideo() {
        val range = full(50L)

        assertEquals(50L, range.minLengthMs)
        assertEquals(range, range.withStart(40L))
        assertEquals(range, range.withEnd(10L))
    }

    @Test
    fun oneMillisecondVideoIsValidAndImmovable() {
        val range = full(1L)

        assertEquals(0L, range.startMs)
        assertEquals(1L, range.endMs)
        assertEquals(range, range.withStart(1L))
        assertEquals(range, range.withEnd(0L))
    }

    @Test
    fun videoJustLongerThanTheMinimumHasOneMillisecondOfRoom() {
        val range = full(MIN_CLIP_MS + 1L)

        assertEquals(1L, range.withStart(MIN_CLIP_MS + 1L).startMs)
        assertEquals(MIN_CLIP_MS, range.withEnd(0L).endMs)
    }

    @Test
    fun selectionNeverShrinksBelowTheMinimumClip() {
        val range = full().withEnd(5_000L).withStart(10_000L)

        assertEquals(MIN_CLIP_MS, range.lengthMs)
    }

    // --- construction rejects invalid ranges -----------------------------

    @Test
    fun emptySelectionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrimRange(startMs = 3_000L, endMs = 3_000L, durationMs = duration)
        }
    }

    @Test
    fun reversedSelectionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrimRange(startMs = 4_000L, endMs = 3_000L, durationMs = duration)
        }
    }

    @Test
    fun negativeStartIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrimRange(startMs = -1L, endMs = 3_000L, durationMs = duration)
        }
    }

    @Test
    fun endPastTheDurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrimRange(startMs = 0L, endMs = duration + 1L, durationMs = duration)
        }
    }

    @Test
    fun selectionShorterThanTheMinimumIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TrimRange(startMs = 0L, endMs = MIN_CLIP_MS - 1L, durationMs = duration)
        }
    }

    // --- slider values (floats) -> milliseconds --------------------------

    @Test
    fun unchangedSliderValuesChangeNothing() {
        val range = full().withStart(2_000L).withEnd(8_000L)

        assertEquals(range, range.withSliderValues(2_000f, 8_000f))
    }

    @Test
    fun onlyTheStartThumbMovingOnlyChangesTheStart() {
        val range = full().withStart(2_000L).withEnd(8_000L)
        val moved = range.withSliderValues(3_000f, 8_000f)

        assertEquals(3_000L, moved.startMs)
        assertEquals(8_000L, moved.endMs)
    }

    @Test
    fun onlyTheEndThumbMovingOnlyChangesTheEnd() {
        val range = full().withStart(2_000L).withEnd(8_000L)
        val moved = range.withSliderValues(2_000f, 6_000f)

        assertEquals(2_000L, moved.startMs)
        assertEquals(6_000L, moved.endMs)
    }

    @Test
    fun sliderValuesAreRoundedToTheNearestMillisecond() {
        val moved = full().withSliderValues(1_234.6f, 9_000.4f)

        assertEquals(1_235L, moved.startMs)
        assertEquals(9_000L, moved.endMs)
    }

    @Test
    fun sliderValuesThatCrossAreClamped() {
        val range = full().withStart(2_000L).withEnd(8_000L)

        val startPushedPastEnd = range.withSliderValues(9_500f, 8_000f)
        assertEquals(8_000L - MIN_CLIP_MS, startPushedPastEnd.startMs)
        assertEquals(8_000L, startPushedPastEnd.endMs)

        val endPulledBeforeStart = range.withSliderValues(2_000f, 100f)
        assertEquals(2_000L, endPulledBeforeStart.startMs)
        assertEquals(2_000L + MIN_CLIP_MS, endPulledBeforeStart.endMs)
    }

    @Test
    fun wholeMillisecondsSurviveTheFloatRoundTripUpToAboutFourHours() {
        val longDuration = 16_000_000L
        val range = full(longDuration).withStart(15_999_001L)

        assertEquals(range, range.withSliderValues(
            range.startMs.toFloat(),
            range.endMs.toFloat()
        ))
    }

    // --- invariant under arbitrary input --------------------------------

    @Test
    fun invariantHoldsAfterManyRandomMoves() {
        val random = Random(42)

        for (durationMs in listOf(1L, 50L, 100L, 101L, 10_000L, 3_600_000L)) {
            var range = full(durationMs)

            repeat(5_000) {
                val target = random.nextLong(-durationMs, durationMs * 2 + 1)
                range = when (random.nextInt(3)) {
                    0 -> range.withStart(target)
                    1 -> range.withEnd(target)
                    else -> range.withSliderValues(
                        random.nextLong(-durationMs, durationMs * 2 + 1).toFloat(),
                        target.toFloat()
                    )
                }

                assertTrue(range.startMs >= 0L)
                assertTrue(range.startMs < range.endMs)
                assertTrue(range.endMs <= durationMs)
                assertTrue(range.lengthMs >= minOf(MIN_CLIP_MS, durationMs))
            }
        }
    }

    // --- values handed to export -----------------------------------------

    @Test
    fun exportingTheFullVideoUsesTheWholeDuration() {
        val range = full()

        assertEquals(0L, range.startMs)
        assertEquals(duration, range.endMs)
        assertEquals(duration, range.lengthMs)
    }

    @Test
    fun exportingAMiddleSegmentUsesExactlyTheSelectedBounds() {
        val range = full().withStart(2_000L).withEnd(5_000L)

        assertEquals(2_000L, range.startMs)
        assertEquals(5_000L, range.endMs)
        assertEquals(3_000L, range.lengthMs)
    }

    @Test
    fun exportingASegmentStartingAtZeroKeepsStartAtZero() {
        val range = full().withEnd(4_000L)

        assertEquals(0L, range.startMs)
        assertEquals(4_000L, range.lengthMs)
    }

    @Test
    fun exportingASegmentEndingAtTheSourceDurationKeepsEndAtDuration() {
        val range = full().withStart(6_000L)

        assertEquals(duration, range.endMs)
        assertEquals(4_000L, range.lengthMs)
    }

    @Test
    fun aCapturedRangeIsNotAffectedByLaterMoves() {
        val live = full().withStart(2_000L).withEnd(8_000L)
        val captured = live

        val laterLive = live.withStart(5_000L).withEnd(6_000L)

        assertEquals(2_000L, captured.startMs)
        assertEquals(8_000L, captured.endMs)
        assertEquals(5_000L, laterLive.startMs)
    }

    // --- checking the exported file's duration ---------------------------

    @Test
    fun exportDurationWithinToleranceIsAccepted() {
        assertTrue(isExportDurationClose(3_000L, 3_000L))
        assertTrue(isExportDurationClose(3_000L, 3_040L))
        assertTrue(isExportDurationClose(3_000L, 2_900L))
        assertTrue(isExportDurationClose(3_000L, 3_000L + EXPORT_DURATION_TOLERANCE_MS))
    }

    @Test
    fun exportDurationOutsideToleranceIsFlagged() {
        assertFalse(isExportDurationClose(3_000L, 3_000L + EXPORT_DURATION_TOLERANCE_MS + 1L))
        assertFalse(isExportDurationClose(3_000L, 10_000L))
        assertFalse(isExportDurationClose(3_000L, 0L))
    }
}
