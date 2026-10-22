package com.motioncut.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSettingsTest {

    @Test
    fun defaultsChangeNothing() {
        val settings = EditSettings()

        assertFalse(settings.changesSpeed)
        assertFalse(settings.changesAudio)
        assertFalse(settings.changesFrames)
        assertEquals(1f, settings.effectiveVolume, 0f)
        assertEquals(0, settings.counterClockwiseDegrees)
    }

    @Test
    fun everyOfferedSpeedIsValid() {
        SPEED_OPTIONS.forEach { EditSettings(speed = it) }
        assertTrue(SPEED_OPTIONS.contains(1f))
    }

    // --- speed ----------------------------------------------------------

    @Test
    fun speedChangeIsDetected() {
        assertTrue(EditSettings(speed = 2f).changesSpeed)
        assertTrue(EditSettings(speed = 0.5f).changesSpeed)
        assertFalse(EditSettings(speed = 1f).changesSpeed)
    }

    @Test
    fun outputDurationFollowsSpeed() {
        assertEquals(10_000L, EditSettings().outputDurationMs(10_000L))
        assertEquals(5_000L, EditSettings(speed = 2f).outputDurationMs(10_000L))
        assertEquals(20_000L, EditSettings(speed = 0.5f).outputDurationMs(10_000L))
    }

    @Test
    fun outputDurationIsRoundedToTheNearestMillisecond() {
        assertEquals(9_073L, EditSettings(speed = 1.5f).outputDurationMs(13_610L))
        assertEquals(133L, EditSettings(speed = 0.75f).outputDurationMs(100L))
    }

    @Test
    fun outputDurationOfTheShortestClipAtTopSpeed() {
        assertEquals(50L, EditSettings(speed = 2f).outputDurationMs(MIN_CLIP_MS))
    }

    @Test
    fun outputDurationUsesTheTrimmedLengthNotTheSourceLength() {
        val range = TrimRange.full(40_000L)!!.withStart(10_000L).withEnd(20_000L)

        assertEquals(5_000L, EditSettings(speed = 2f).outputDurationMs(range.lengthMs))
    }

    @Test
    fun speedLabels() {
        assertEquals("1×", formatSpeed(1f))
        assertEquals("2×", formatSpeed(2f))
        assertEquals("0.5×", formatSpeed(0.5f))
        assertEquals("0.75×", formatSpeed(0.75f))
        assertEquals("1.5×", formatSpeed(1.5f))
    }

    // --- volume ----------------------------------------------------------

    @Test
    fun mutingSilencesThePreviewButKeepsTheVolumeSetting() {
        val settings = EditSettings(volume = 0.6f, muted = true)

        assertEquals(0f, settings.effectiveVolume, 0f)
        assertEquals(0.6f, settings.volume, 0f)
        assertTrue(settings.changesAudio)
    }

    @Test
    fun reducedVolumeChangesAudio() {
        assertTrue(EditSettings(volume = 0.5f).changesAudio)
        assertEquals(0.5f, EditSettings(volume = 0.5f).effectiveVolume, 0f)
    }

    // --- rotation --------------------------------------------------------

    @Test
    fun rotatingClockwiseCyclesThroughQuarterTurns() {
        var settings = EditSettings()
        val seen = mutableListOf<Int>()

        repeat(4) {
            settings = settings.rotatedClockwise()
            seen.add(settings.rotationDegrees)
        }

        assertEquals(listOf(90, 180, 270, 0), seen)
    }

    @Test
    fun mediaRotationIsTheCounterClockwiseEquivalent() {
        assertEquals(0, EditSettings(rotationDegrees = 0).counterClockwiseDegrees)
        assertEquals(270, EditSettings(rotationDegrees = 90).counterClockwiseDegrees)
        assertEquals(180, EditSettings(rotationDegrees = 180).counterClockwiseDegrees)
        assertEquals(90, EditSettings(rotationDegrees = 270).counterClockwiseDegrees)
    }

    @Test
    fun rotationOrFlipChangesFrames() {
        assertTrue(EditSettings(rotationDegrees = 90).changesFrames)
        assertTrue(EditSettings(flipHorizontal = true).changesFrames)
        assertFalse(EditSettings(rotationDegrees = 0, flipHorizontal = false).changesFrames)
    }

    // --- crop ------------------------------------------------------------

    @Test
    fun aspectOptionsHaveTheExpectedRatios() {
        assertNull(AspectOption.ORIGINAL.ratio)
        assertEquals(16f / 9f, AspectOption.LANDSCAPE.ratio!!, 0.0001f)
        assertEquals(9f / 16f, AspectOption.PORTRAIT.ratio!!, 0.0001f)
        assertEquals(1f, AspectOption.SQUARE.ratio!!, 0f)
    }

    @Test
    fun choosingAnAspectChangesFrames() {
        assertTrue(EditSettings(aspect = AspectOption.SQUARE).changesFrames)
        assertFalse(EditSettings(aspect = AspectOption.ORIGINAL).changesFrames)
    }

    // --- invalid input ---------------------------------------------------

    @Test
    fun invalidSpeedIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { EditSettings(speed = 0f) }
        assertThrows(IllegalArgumentException::class.java) { EditSettings(speed = -1f) }
    }

    @Test
    fun volumeOutsideZeroToOneIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { EditSettings(volume = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { EditSettings(volume = 1.1f) }
    }

    @Test
    fun rotationThatIsNotAQuarterTurnIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { EditSettings(rotationDegrees = 45) }
        assertThrows(IllegalArgumentException::class.java) { EditSettings(rotationDegrees = 360) }
        assertThrows(IllegalArgumentException::class.java) { EditSettings(rotationDegrees = -90) }
    }
}
