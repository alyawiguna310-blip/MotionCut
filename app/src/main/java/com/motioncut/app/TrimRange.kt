package com.motioncut.app

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Shortest clip the trim controls allow, in milliseconds. About three frames
 * at 30 fps: short enough for any realistic edit, long enough that the
 * encoder is not asked to produce a clip with less than one frame in it.
 * Videos shorter than this can only be exported whole (see [TrimRange.minLengthMs]).
 */
const val MIN_CLIP_MS = 100L

/**
 * How far the exported file's duration may differ from the selected length
 * before the app warns about it. Container and audio-frame rounding make an
 * exact match unrealistic.
 */
const val EXPORT_DURATION_TOLERANCE_MS = 250L

/**
 * The selected part of a video. All values are milliseconds, which is also
 * the unit Media3's `ClippingConfiguration` expects, so no unit conversion
 * is needed anywhere between the UI, the preview player and the exporter.
 *
 * Invariant, enforced on construction: `0 <= startMs < endMs <= durationMs`
 * and the selection is at least [minLengthMs] long. Instances are immutable;
 * the `with...` functions return a clamped copy and never produce an invalid range.
 */
data class TrimRange(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long
) {
    /** [MIN_CLIP_MS], or the whole video if the video is shorter than that. */
    val minLengthMs: Long get() = minOf(MIN_CLIP_MS, durationMs)

    val lengthMs: Long get() = endMs - startMs

    init {
        require(startMs >= 0L && startMs < endMs && endMs <= durationMs) {
            "Invalid trim range: start=$startMs end=$endMs duration=$durationMs"
        }
        require(endMs - startMs >= minOf(MIN_CLIP_MS, durationMs)) {
            "Trim range shorter than the minimum clip length: " +
                "start=$startMs end=$endMs duration=$durationMs"
        }
    }

    /** Moves the start, stopping at 0 and at [minLengthMs] before the end. */
    fun withStart(newStartMs: Long): TrimRange =
        copy(startMs = newStartMs.coerceIn(0L, endMs - minLengthMs))

    /** Moves the end, stopping at [minLengthMs] after the start and at the video duration. */
    fun withEnd(newEndMs: Long): TrimRange =
        copy(endMs = newEndMs.coerceIn(startMs + minLengthMs, durationMs))

    /**
     * Applies the two values reported by a range slider whose scale is
     * `0..durationMs`. Only a thumb whose rounded value differs from the
     * current one is treated as moved, so a drag on one thumb can never
     * shift the other.
     *
     * Slider values are floats, which are exact for whole milliseconds up to
     * 16,777,216 ms (about 4.6 hours). Beyond that the untouched thumb may
     * read a few milliseconds off and be treated as moved.
     */
    fun withSliderValues(startValue: Float, endValue: Float): TrimRange {
        val newStartMs = startValue.roundToLong()
        val newEndMs = endValue.roundToLong()
        var result = this
        if (newStartMs != startMs) result = result.withStart(newStartMs)
        if (newEndMs != endMs) result = result.withEnd(newEndMs)
        return result
    }

    companion object {
        /**
         * The whole video selected, or null when [durationMs] is not usable
         * (zero, negative, or Media3's `C.TIME_UNSET` for an unknown duration).
         */
        fun full(durationMs: Long): TrimRange? =
            if (durationMs > 0L) TrimRange(0L, durationMs, durationMs) else null
    }
}

/** True when an exported file's duration is close enough to the selected length. */
fun isExportDurationClose(
    expectedMs: Long,
    actualMs: Long,
    toleranceMs: Long = EXPORT_DURATION_TOLERANCE_MS
): Boolean = abs(expectedMs - actualMs) <= toleranceMs
