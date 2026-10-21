package com.motioncut.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun startOfVideoFormatsAsZero() {
        assertEquals("00:00.00", formatTime(0L))
    }

    @Test
    fun formatsSecondsAndCentiseconds() {
        assertEquals("00:01.00", formatTime(1_000L))
        assertEquals("00:12.34", formatTime(12_345L))
        assertEquals("00:59.99", formatTime(59_999L))
    }

    @Test
    fun formatsMinutes() {
        assertEquals("01:00.00", formatTime(60_000L))
        assertEquals("01:01.23", formatTime(61_234L))
        assertEquals("02:05.99", formatTime(125_999L))
    }

    @Test
    fun centisecondsAreTruncatedNotRounded() {
        assertEquals("00:00.00", formatTime(9L))
        assertEquals("00:00.99", formatTime(999L))
    }

    @Test
    fun endOfVideoNeverDisplaysPastItsRealDuration() {
        // 12,349 ms must not read as 00:12.35
        assertEquals("00:12.34", formatTime(12_349L))
    }

    @Test
    fun minutesAreNotCappedAtFiftyNine() {
        assertEquals("60:00.00", formatTime(3_600_000L))
    }

    @Test
    fun negativeTimeShowsAsZero() {
        assertEquals("00:00.00", formatTime(-1L))
    }
}
