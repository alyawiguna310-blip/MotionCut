package com.motioncut.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val TAG = "Motioncut"
private const val EXPORT_TEMP_FILE_NAME = "motioncut-export.mp4"

/** How often preview playback checks whether it has reached the trim end. */
private const val PREVIEW_POLL_INTERVAL_MS = 30L

private val Background = Color(0xFF101010)
private val Panel = Color(0xFF1C1C1C)
private val Accent = Color(0xFFB7F397)
private val TextMain = Color(0xFFF4F4F4)
private val TextMuted = Color(0xFFAAAAAA)

/** The source and trim range as they were when the user tapped Export. */
private data class ExportRequest(
    val sourceUri: Uri,
    val range: TrimRange
)

/** A Transformer export that is still running, and the files it owns. */
private class ActiveExport(
    val transformer: Transformer,
    val destination: Uri,
    val temporaryFile: File
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Background,
                    surface = Panel,
                    onPrimary = Color.Black,
                    onBackground = TextMain,
                    onSurface = TextMain
                )
            ) {
                MotioncutEditor(this)
            }
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MotioncutEditor(context: Context) {
    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    val scope = rememberCoroutineScope()

    var sourceUri by remember { mutableStateOf<Uri?>(null) }

    // Single source of truth for the selection. Null until the loaded video
    // reports a usable duration. Only the slider changes it, and it is
    // replaced (not edited) when a different video is opened.
    var trim by remember { mutableStateOf<TrimRange?>(null) }
    var status by remember {
        mutableStateOf("import a video to get started")
    }
    var isPlaying by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    // Set when Export is tapped, cleared when the destination picker returns.
    var pendingExport by remember { mutableStateOf<ExportRequest?>(null) }
    var activeExport by remember { mutableStateOf<ActiveExport?>(null) }

    val openVideo = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers don't support persistable grants.
                // The URI can still be used while its temporary grant is valid.
            }

            sourceUri = uri
            trim = null
            status = "loading video…"

            player.pause()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    val saveVideo = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { destinationUri ->
        val request = pendingExport
        pendingExport = null

        if (destinationUri == null) {
            status = "export cancelled"
            exporting = false
            return@rememberLauncherForActivityResult
        }

        if (request == null) {
            // Nothing has been written to the new document yet.
            Log.e(TAG, "Export destination chosen but no export request was pending")
            discardEmptyDestination(context, destinationUri)
            status = "export failed: the request was lost, please try again"
            exporting = false
            return@rememberLauncherForActivityResult
        }

        val range = request.range

        player.pause()
        exporting = true
        status = "exporting MP4…"

        val temporaryFile = File(
            context.cacheDir,
            EXPORT_TEMP_FILE_NAME
        )

        if (temporaryFile.exists() && !temporaryFile.delete()) {
            Log.w(TAG, "Could not delete stale temporary export file")
        }

        // Media3 1.5.1 takes these in milliseconds, the same unit as TrimRange.
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(range.startMs)
            .setEndPositionMs(range.endMs)
            .build()

        val clippedMediaItem = MediaItem.Builder()
            .setUri(request.sourceUri)
            .setClippingConfiguration(clipping)
            .build()

        val editedItem = EditedMediaItem.Builder(clippedMediaItem)
            .build()

        Log.i(TAG, "Starting export: start=${range.startMs}ms end=${range.endMs}ms")

        try {
            val transformer = Transformer.Builder(context)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            // Transformer is done; from here on only the copy
                            // to the destination is left, and nothing may
                            // cancel or delete the finished file under it.
                            activeExport = null

                            scope.launch {
                                try {
                                    val actualDurationMs = withContext(Dispatchers.IO) {
                                        val measured = readDurationMs(temporaryFile)
                                        copyToDestination(
                                            context,
                                            temporaryFile,
                                            destinationUri
                                        )
                                        measured
                                    }

                                    Log.i(
                                        TAG,
                                        "Export finished: expected=${range.lengthMs}ms " +
                                            "actual=${actualDurationMs}ms"
                                    )
                                    status = exportCompleteMessage(range, actualDurationMs)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e

                                    // The destination may hold partial data, so it
                                    // is deliberately left in place.
                                    Log.e(TAG, "Saving the export failed", e)
                                    status = "save failed: ${
                                        e.message ?: "unknown error"
                                    }. The saved file may be incomplete."
                                } finally {
                                    temporaryFile.delete()
                                    exporting = false
                                }
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e(TAG, "Export failed", exportException)
                            activeExport = null
                            discardUnfinishedExport(
                                context,
                                destinationUri,
                                temporaryFile
                            )
                            exporting = false
                            status = "export failed: ${
                                exportException.message ?: "unknown error"
                            }"
                        }
                    }
                )
                .build()

            transformer.start(editedItem, temporaryFile.absolutePath)
            activeExport = ActiveExport(transformer, destinationUri, temporaryFile)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not start export", e)
            discardUnfinishedExport(context, destinationUri, temporaryFile)
            exporting = false
            status = "export failed to start: ${
                e.message ?: "unknown error"
            }"
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        // Initialise the selection once per opened video.
                        // READY also follows every seek and buffering stall,
                        // and those must never touch the user's selection.
                        if (trim == null) {
                            val initial = TrimRange.full(player.duration)
                            if (initial != null) {
                                trim = initial
                                status = "video ready"
                            }
                        }
                    }

                    Player.STATE_ENDED -> {
                        // Playing ran off the end of the file: stop and go
                        // back to the selected start. A paused seek to the
                        // very end (dragging the end handle) is left alone.
                        if (player.playWhenReady) {
                            player.pause()
                            player.seekTo(trim?.startMs ?: 0L)
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing

                // Pressing play outside the selection starts at the selected start.
                val range = trim
                if (playing && range != null) {
                    val position = player.currentPosition
                    if (position < range.startMs || position >= range.endMs) {
                        player.seekTo(range.startMs)
                    }
                }
            }

            override fun onPlayerError(
                error: androidx.media3.common.PlaybackException
            ) {
                Log.e(TAG, "Preview playback error", error)
                status = "preview failed: ${error.message ?: "unsupported video"}"
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)

            // Only a running Transformer is cancelled. Once it has completed,
            // activeExport is null and the copy to the destination is left to finish.
            activeExport?.let { export ->
                export.transformer.cancel()
                discardUnfinishedExport(
                    context,
                    export.destination,
                    export.temporaryFile
                )
            }

            player.release()
        }
    }

    // Stop preview at the selected end. Restarts whenever playback starts or
    // stops or the selection changes, so dragging a handle during playback
    // is picked up immediately.
    LaunchedEffect(isPlaying, trim) {
        val range = trim
        if (!isPlaying || range == null) return@LaunchedEffect

        while (player.currentPosition < range.endMs) {
            delay(PREVIEW_POLL_INTERVAL_MS)
        }

        player.pause()
        player.seekTo(range.startMs)
    }

    val range = trim
    val sliderStart = (range?.startMs ?: 0L).toFloat()
    val sliderEnd = (range?.endMs ?: 1L).toFloat()
    val sliderMax = (range?.durationMs ?: 1L).toFloat()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "motioncut",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextMain
                    )
                    Text(
                        text = "VIDEO EDITOR  •  0.1.0",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                Button(
                    enabled = !exporting && pendingExport == null,
                    onClick = {
                        openVideo.launch(arrayOf("video/*"))
                    }
                ) {
                    Text("Import")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(18.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color.Black, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (sourceUri != null) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext ->
                                PlayerView(viewContext).apply {
                                    useController = true
                                    resizeMode =
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    this.player = player
                                }
                            },
                            update = { view ->
                                view.player = player
                            }
                        )
                    } else {
                        Text(
                            text = "your preview appears here",
                            color = TextMuted
                        )
                    }
                }

                Text(
                    text = sourceUri?.lastPathSegment
                        ?: "no video selected",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "TRIM CLIP",
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "start  ${formatTime(range?.startMs ?: 0L)}",
                        color = TextMain
                    )
                    Text(
                        text = "end  ${formatTime(range?.endMs ?: 0L)}",
                        color = TextMain
                    )
                }

                // One track for the whole video: both thumbs share the
                // 0..duration scale, so neither can move the other.
                RangeSlider(
                    value = sliderStart..sliderEnd,
                    onValueChange = { values ->
                        val current = trim
                        if (current != null) {
                            val updated = current.withSliderValues(
                                values.start,
                                values.endInclusive
                            )

                            if (updated != current) {
                                trim = updated

                                // Show the frame under the handle being dragged.
                                if (updated.startMs != current.startMs) {
                                    player.seekTo(updated.startMs)
                                } else {
                                    player.seekTo(updated.endMs)
                                }
                            }
                        }
                    },
                    valueRange = 0f..sliderMax,
                    enabled = range != null && !exporting,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent
                    )
                )

                Text(
                    text = "selected duration: ${formatTime(range?.lengthMs ?: 0L)}" +
                        (range?.let { " of ${formatTime(it.durationMs)}" } ?: ""),
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sourceUri != null &&
                        range != null &&
                        !exporting &&
                        pendingExport == null,
                    onClick = {
                        val source = sourceUri
                        val selected = trim

                        if (source != null && selected != null) {
                            // The range is captured here, once, and used
                            // unchanged for the whole export.
                            pendingExport = ExportRequest(source, selected)
                            saveVideo.launch("Motioncut-export.mp4")
                        }
                    }
                ) {
                    Text(if (exporting) "Exporting…" else "Export MP4")
                }
            }

            Text(
                text = status,
                color = if (status.contains("failed") ||
                    status.contains("error")) {
                    Color(0xFFFF8A80)
                } else {
                    Accent
                },
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "v0.1 • single-clip editing • offline",
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun copyToDestination(context: Context, source: File, destination: Uri) {
    val output = context.contentResolver.openOutputStream(destination, "w")
        ?: throw IOException("Cannot open destination file")

    output.use { out ->
        source.inputStream().use { input -> input.copyTo(out) }
    }
}

/** Duration of a finished export as the platform reads it, or null if unreadable. */
private fun readDurationMs(file: File): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not read the exported file's duration", e)
        null
    } finally {
        retriever.release()
    }
}

private fun exportCompleteMessage(range: TrimRange, actualDurationMs: Long?): String {
    if (actualDurationMs == null) {
        return "export complete (its duration could not be checked)"
    }

    if (isExportDurationClose(range.lengthMs, actualDurationMs)) {
        return "export complete"
    }

    Log.w(
        TAG,
        "Exported duration differs from selection: " +
            "expected=${range.lengthMs}ms actual=${actualDurationMs}ms"
    )
    return "export complete, but the file is ${formatTime(actualDurationMs)} long " +
        "and the selection was ${formatTime(range.lengthMs)}"
}

/**
 * Cleans up after an export that never wrote to its destination (failed to
 * start, failed in Transformer, or was cancelled): removes the temporary
 * file and the empty document the picker created. Not for failures that
 * happen while copying, because the destination may then hold real data.
 */
private fun discardUnfinishedExport(
    context: Context,
    destination: Uri,
    temporaryFile: File
) {
    if (temporaryFile.exists() && !temporaryFile.delete()) {
        Log.w(TAG, "Could not delete temporary export file")
    }
    discardEmptyDestination(context, destination)
}

/**
 * Deletes [destination] only if it can be confirmed to be a zero-length
 * document. Anything else, or anything the provider will not confirm, is left
 * in place and logged.
 */
private fun discardEmptyDestination(context: Context, destination: Uri) {
    try {
        val size = context.contentResolver
            .openAssetFileDescriptor(destination, "r")
            ?.use { it.length }

        if (size != 0L) {
            Log.w(TAG, "Leaving export destination in place (size=$size): $destination")
            return
        }

        if (!DocumentsContract.deleteDocument(context.contentResolver, destination)) {
            Log.w(TAG, "Provider declined to delete empty export destination: $destination")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not remove empty export destination: $destination", e)
    }
}