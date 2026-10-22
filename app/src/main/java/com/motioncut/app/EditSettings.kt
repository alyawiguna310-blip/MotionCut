package com.motioncut.app

import kotlin.math.roundToLong

/** Speeds offered in the UI. 1x is the original speed. */
val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.5f, 2f)

/** Output frame shapes. [ratio] is width / height, or null to keep the source's shape. */
enum class AspectOption(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    LANDSCAPE("16:9", 16f / 9f),
    PORTRAIT("9:16", 9f / 16f),
    SQUARE("1:1", 1f)
}

/**
 * Everything the editor applies to a clip besides the trim range. The same
 * instance drives the preview player and the exporter, so what is previewed
 * is what is exported.
 *
 * [volume] is 0..1 because ExoPlayer cannot preview a boost above 100%.
 * [rotationDegrees] is clockwise, as the user sees it, in quarter turns.
 */
data class EditSettings(
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val aspect: AspectOption = AspectOption.ORIGINAL
) {
    init {
        require(speed > 0f) { "Speed must be positive: $speed" }
        require(volume in 0f..1f) { "Volume must be between 0 and 1: $volume" }
        require(rotationDegrees in 0..270 && rotationDegrees % 90 == 0) {
            "Rotation must be 0, 90, 180 or 270: $rotationDegrees"
        }
    }

    val changesSpeed: Boolean get() = speed != 1f

    val changesAudio: Boolean get() = muted || volume < 1f

    val changesFrames: Boolean
        get() = rotationDegrees != 0 || flipHorizontal || aspect != AspectOption.ORIGINAL

    /** Volume the preview should use: 0 while muted. */
    val effectiveVolume: Float get() = if (muted) 0f else volume

    /** Media3 rotates counterclockwise, the UI clockwise. */
    val counterClockwiseDegrees: Int get() = (360 - rotationDegrees) % 360

    fun rotatedClockwise(): EditSettings = copy(rotationDegrees = (rotationDegrees + 90) % 360)

    /** Length of the exported clip for a trimmed clip of [clipLengthMs]. */
    fun outputDurationMs(clipLengthMs: Long): Long =
        (clipLengthMs / speed.toDouble()).roundToLong()
}

/** "1×", "0.5×", "1.5×". */
fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"
