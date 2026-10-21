package com.motioncut.app

import java.util.Locale

/**
 * Formats milliseconds as `mm:ss.cc` (centiseconds, truncated rather than
 * rounded so the end of a video never displays past its real duration).
 * Minutes are not capped at 59. Negative input is shown as zero.
 */
fun formatTime(milliseconds: Long): String {
    val totalMs = milliseconds.coerceAtLeast(0L)
    val minutes = totalMs / 60_000L
    val seconds = (totalMs / 1_000L) % 60L
    val centiseconds = (totalMs % 1_000L) / 10L

    return String.format(
        Locale.US,
        "%02d:%02d.%02d",
        minutes,
        seconds,
        centiseconds
    )
}
