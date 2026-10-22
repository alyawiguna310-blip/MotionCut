package com.motioncut.app

import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

private const val TAG = "Motioncut"
private const val EXPORT_TEMP_FILE_NAME = "motioncut-export.mp4"
private const val STATUS_READY = "video ready"

/** How often preview playback checks whether it has reached the trim end. */
private const val PREVIEW_POLL_INTERVAL_MS = 30L

/** How often the export progress bar asks Transformer for its progress. */
private const val PROGRESS_POLL_INTERVAL_MS = 250L

/** The source, trim range and edit settings as they were when the user tapped Export. */
private data class ExportRequest(
    val sourceUri: Uri,
    val range: TrimRange,
    val settings: EditSettings
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

@OptIn(UnstableApi::class)
@Composable
private fun MotioncutEditor(activity: ComponentActivity) {
    val context: Context = activity

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    val scope = rememberCoroutineScope()

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var sourceInfo by remember { mutableStateOf<VideoInfo?>(null) }

    // Single source of truth for the selection. Null until the loaded video
    // reports a usable duration. Only the slider changes it, and it is
    // replaced (not edited) when a different video is opened.
    var trim by remember { mutableStateOf<TrimRange?>(null) }

    // Speed, volume, rotate, flip and crop. Shared by the preview and the export.
    var edit by remember { mutableStateOf(EditSettings()) }
    var tool by remember { mutableStateOf(EditorTool.TRIM) }

    var status by remember {
        mutableStateOf("import a video to get started")
    }
    var isPlaying by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf<Float?>(null) }

    // Set when Export is tapped, cleared when the destination picker returns.
    var pendingExport by remember { mutableStateOf<ExportRequest?>(null) }
    var activeExport by remember { mutableStateOf<ActiveExport?>(null) }

    // Editing after an export makes its result message stale, so drop it.
    fun markEdited() {
        if (!exporting) status = STATUS_READY
    }

    fun updateEdit(change: (EditSettings) -> EditSettings) {
        edit = change(edit)
        markEdited()
    }

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
            sourceName = null
            sourceInfo = null
            trim = null
            edit = EditSettings()
            status = "loading video…"

            player.pause()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()

            scope.launch {
                val (name, info) = withContext(Dispatchers.IO) {
                    displayName(context, uri) to probeVideo(context, uri)
                }

                // Ignore the result if another video was opened meanwhile.
                if (sourceUri == uri) {
                    sourceName = name
                    sourceInfo = info
                }
            }
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
        val settings = request.settings

        player.pause()
        exporting = true
        exportProgress = null
        status = "exporting MP4…"

        val temporaryFile = File(
            context.cacheDir,
            EXPORT_TEMP_FILE_NAME
        )

        if (temporaryFile.exists() && !temporaryFile.delete()) {
            Log.w(TAG, "Could not delete stale temporary export file")
        }

        // Media3 1.5.1 takes these in milliseconds, the same unit as TrimRange.
        // Trimming is applied to the source first; the effects below then
        // work on the trimmed clip.
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(range.startMs)
            .setEndPositionMs(range.endMs)
            .build()

        val clippedMediaItem = MediaItem.Builder()
            .setUri(request.sourceUri)
            .setClippingConfiguration(clipping)
            .build()

        val editedItemBuilder = EditedMediaItem.Builder(clippedMediaItem)
            .setRemoveAudio(settings.muted)

        // With nothing to change, no effects are set and the export takes the
        // same path that was verified for plain trimming.
        val effects = buildExportEffects(settings)
        if (effects.audioProcessors.isNotEmpty() || effects.videoEffects.isNotEmpty()) {
            editedItemBuilder.setEffects(effects)
        }

        val editedItem = editedItemBuilder.build()

        Log.i(
            TAG,
            "Starting export: start=${range.startMs}ms end=${range.endMs}ms " +
                "settings=$settings"
        )

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
                                    val output = withContext(Dispatchers.IO) {
                                        val measured = probeVideoFile(temporaryFile)
                                        copyToDestination(
                                            context,
                                            temporaryFile,
                                            destinationUri
                                        )
                                        measured
                                    }

                                    status = exportCompleteMessage(range, settings, output)
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
                                status = STATUS_READY
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

    // Leaving the app pauses the preview instead of playing on in the background.
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }

        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
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

    // Real export progress, polled from Transformer while it is running.
    LaunchedEffect(activeExport) {
        val export = activeExport
        if (export == null) {
            exportProgress = null
            return@LaunchedEffect
        }

        val holder = ProgressHolder()
        while (true) {
            if (export.transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                exportProgress = holder.progress / 100f
            }
            delay(PROGRESS_POLL_INTERVAL_MS)
        }
    }

    // Preview of the edit settings. Speed and volume use the player's own
    // controls; rotate, flip and crop use the same effects the exporter gets.
    // With no frame changes the effect list is empty and the plain render
    // path is used.
    LaunchedEffect(edit.speed) {
        player.setPlaybackSpeed(edit.speed)
    }

    LaunchedEffect(edit.effectiveVolume) {
        player.volume = edit.effectiveVolume
    }

    LaunchedEffect(edit.rotationDegrees, edit.flipHorizontal, edit.aspect) {
        player.setVideoEffects(buildFrameEffects(edit))

        // Redraw the current frame with the new effects.
        if (player.mediaItemCount > 0) player.seekTo(player.currentPosition)
    }

    val range = trim
    val controlsEnabled = range != null && !exporting
    val canExport = sourceUri != null &&
        range != null &&
        !exporting &&
        pendingExport == null

    val subtitle = if (sourceUri == null) {
        "VIDEO EDITOR  •  0.1.0"
    } else {
        listOfNotNull(
            sourceName ?: sourceUri?.lastPathSegment,
            sourceInfo?.resolutionLabel,
            range?.let { formatTime(it.durationMs) }
        ).joinToString("  ·  ")
    }

    val exportLabel = if (activeExport != null) {
        exportProgress?.let { "exporting ${(it * 100).roundToInt()}%" } ?: "preparing export…"
    } else {
        "saving file…"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "motioncut",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextMain
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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

            // The preview takes all the height the editing dock below leaves.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 160.dp)
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
                text = status,
                color = if (status.contains("failed") ||
                    status.contains("error")) {
                    Danger
                } else {
                    Accent
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolTabs(
                    selected = tool,
                    onSelect = { tool = it }
                )

                // Fixed minimum height so switching tools never resizes the preview.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 104.dp)
                ) {
                    when (tool) {
                        EditorTool.TRIM -> TrimPanel(
                            range = range,
                            enabled = controlsEnabled,
                            onSliderValues = { startValue, endValue ->
                                val current = trim
                                if (current != null) {
                                    val updated = current.withSliderValues(
                                        startValue,
                                        endValue
                                    )

                                    if (updated != current) {
                                        trim = updated
                                        markEdited()

                                        // Show the frame under the handle being dragged.
                                        if (updated.startMs != current.startMs) {
                                            player.seekTo(updated.startMs)
                                        } else {
                                            player.seekTo(updated.endMs)
                                        }
                                    }
                                }
                            }
                        )

                        EditorTool.SPEED -> SpeedPanel(
                            speed = edit.speed,
                            enabled = controlsEnabled,
                            clipLengthMs = range?.lengthMs,
                            onSpeed = { value -> updateEdit { it.copy(speed = value) } }
                        )

                        EditorTool.AUDIO -> AudioPanel(
                            volume = edit.volume,
                            muted = edit.muted,
                            enabled = controlsEnabled,
                            onVolume = { value -> updateEdit { it.copy(volume = value) } },
                            onMuted = { value -> updateEdit { it.copy(muted = value) } }
                        )

                        EditorTool.ROTATE -> RotatePanel(
                            rotationDegrees = edit.rotationDegrees,
                            flipHorizontal = edit.flipHorizontal,
                            enabled = controlsEnabled,
                            onRotate = { updateEdit { it.rotatedClockwise() } },
                            onFlip = {
                                updateEdit { it.copy(flipHorizontal = !it.flipHorizontal) }
                            },
                            onReset = {
                                updateEdit {
                                    it.copy(rotationDegrees = 0, flipHorizontal = false)
                                }
                            }
                        )

                        EditorTool.CROP -> CropPanel(
                            selected = edit.aspect,
                            enabled = controlsEnabled,
                            onSelect = { value -> updateEdit { it.copy(aspect = value) } }
                        )
                    }
                }

                ExportBar(
                    exporting = exporting,
                    canExport = canExport,
                    progress = exportProgress,
                    label = exportLabel,
                    canCancel = activeExport != null,
                    onExport = {
                        val source = sourceUri
                        val selected = trim

                        if (source != null && selected != null) {
                            // The range and settings are captured here, once,
                            // and used unchanged for the whole export.
                            pendingExport = ExportRequest(source, selected, edit)
                            saveVideo.launch("Motioncut-export.mp4")
                        }
                    },
                    onCancel = {
                        val export = activeExport
                        if (export != null) {
                            export.transformer.cancel()
                            activeExport = null
                            discardUnfinishedExport(
                                context,
                                export.destination,
                                export.temporaryFile
                            )
                            exporting = false
                            status = "export cancelled"
                        }
                    }
                )
            }
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

/** Reports the real size and length of the exported file, not the requested ones. */
private fun exportCompleteMessage(
    range: TrimRange,
    settings: EditSettings,
    output: VideoInfo
): String {
    val actualMs = output.durationMs
        ?: return "export complete (the output could not be inspected)"
    val expectedMs = settings.outputDurationMs(range.lengthMs)
    val details = listOfNotNull(output.resolutionLabel, formatTime(actualMs))
        .joinToString("  ·  ")

    if (isExportDurationClose(expectedMs, actualMs)) {
        return "export complete  ·  $details"
    }

    Log.w(
        TAG,
        "Exported duration differs from selection: " +
            "expected=${expectedMs}ms actual=${actualMs}ms"
    )
    return "export complete, but the file is ${formatTime(actualMs)} long " +
        "and ${formatTime(expectedMs)} was expected"
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