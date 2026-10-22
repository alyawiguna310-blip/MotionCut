package com.motioncut.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

private const val PROBE_TAG = "Motioncut"

/** What the platform can tell about a video file. Null fields mean unknown. */
data class VideoInfo(
    val durationMs: Long?,
    val width: Int?,
    val height: Int?
) {
    /** Display size such as "1920×1080", or null when either side is unknown. */
    val resolutionLabel: String?
        get() = if (width != null && height != null && width > 0 && height > 0) {
            "${width}×${height}"
        } else {
            null
        }
}

/** Blocking: call off the main thread. */
internal fun probeVideo(context: Context, uri: Uri): VideoInfo =
    probe { setDataSource(context, uri) }

/** Blocking: call off the main thread. */
internal fun probeVideoFile(file: File): VideoInfo =
    probe { setDataSource(file.absolutePath) }

/** The file name a document provider shows for [uri], or null if it has none. */
internal fun displayName(context: Context, uri: Uri): String? =
    try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: RuntimeException) {
        Log.w(PROBE_TAG, "Could not read the file name for $uri", e)
        null
    }

private fun probe(setSource: MediaMetadataRetriever.() -> Unit): VideoInfo {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setSource()

        val rotation = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
        val width = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val height = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val turned = rotation == 90 || rotation == 270

        VideoInfo(
            durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull(),
            width = if (turned) height else width,
            height = if (turned) width else height
        )
    } catch (e: RuntimeException) {
        Log.w(PROBE_TAG, "Could not read video metadata", e)
        VideoInfo(null, null, null)
    } finally {
        retriever.release()
    }
}

private fun MediaMetadataRetriever.intMetadata(key: Int): Int? =
    extractMetadata(key)?.toIntOrNull()
