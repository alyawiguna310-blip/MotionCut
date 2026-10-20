package com.motioncut.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

private val Background = Color(0xFF101010)
private val Panel = Color(0xFF1C1C1C)
private val Accent = Color(0xFFB7F397)
private val TextMain = Color(0xFFF4F4F4)
private val TextMuted = Color(0xFFAAAAAA)

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
private fun MotioncutEditor(context: Context) {
    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    val scope = rememberCoroutineScope()

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var status by remember {
        mutableStateOf("import a video to get started")
    }
    var exporting by remember { mutableStateOf(false) }

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
            durationMs = 0L
            startMs = 0L
            endMs = 0L
            status = "loading video…"

            player.pause()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    val saveVideo = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { destinationUri ->
        val inputUri = sourceUri

        if (destinationUri == null || inputUri == null) {
            status = "export cancelled"
            exporting = false
            return@rememberLauncherForActivityResult
        }

        if (endMs <= startMs || endMs > durationMs) {
            status = "invalid trim range"
            exporting = false
            return@rememberLauncherForActivityResult
        }

        player.pause()
        exporting = true
        status = "exporting MP4…"

        val temporaryFile = File(
            context.cacheDir,
            "motioncut-export.mp4"
        )

        if (temporaryFile.exists()) {
            temporaryFile.delete()
        }

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()

        val clippedMediaItem = MediaItem.Builder()
            .setUri(inputUri)
            .setClippingConfiguration(clipping)
            .build()

        val editedItem = EditedMediaItem.Builder(clippedMediaItem)
            .build()

        try {
            val transformer = Transformer.Builder(context)
                .addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult
                        ) {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val output = context.contentResolver
                                            .openOutputStream(destinationUri, "w")
                                            ?: throw IOException(
                                                "Cannot open destination file"
                                            )

                                        output.use { destination ->
                                            temporaryFile.inputStream().use {
                                                input -> input.copyTo(destination)
                                            }
                                        }
                                    }

                                    status = "export complete"
                                } catch (e: Exception) {
                                    status = "save failed: ${
                                        e.message ?: "unknown error"
                                    }"
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
                            temporaryFile.delete()
                            exporting = false
                            status = "export failed: ${
                                exportException.message ?: "unknown error"
                            }"
                        }
                    }
                )
                .build()

            transformer.start(editedItem, temporaryFile.absolutePath)
        } catch (e: Exception) {
            temporaryFile.delete()
            exporting = false
            status = "could not start export: ${
                e.message ?: "unknown error"
            }"
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val actualDuration = player.duration

                    if (actualDuration > 0L) {
                        durationMs = actualDuration
                        startMs = 0L
                        endMs = actualDuration
                        status = "video ready"
                    }
                }

                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0L)
                }
            }

            override fun onPlayerError(
                error: androidx.media3.common.PlaybackException
            ) {
                status = "preview failed: ${error.message ?: "unsupported video"}"
            }
        }

        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

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
                    enabled = !exporting,
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

                Text(
                    text = "start  ${formatTime(startMs)}",
                    color = TextMain
                )

                Slider(
                    value = startMs.toFloat(),
                    onValueChange = { value ->
                        startMs = value.toLong().coerceIn(
                            0L,
                            (endMs - 1L).coerceAtLeast(0L)
                        )
                        player.seekTo(startMs)
                    },
                    valueRange = 0f..(endMs - 1L)
                        .coerceAtLeast(0L).toFloat(),
                    enabled = durationMs > 1L && !exporting,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent
                    )
                )

                Text(
                    text = "end  ${formatTime(endMs)}",
                    color = TextMain
                )

                Slider(
                    value = endMs.toFloat(),
                    onValueChange = { value ->
                        endMs = value.toLong().coerceIn(
                            startMs + 1L,
                            durationMs
                        )
                        player.seekTo(endMs)
                    },
                    valueRange = (startMs + 1L)
                        .coerceAtMost(durationMs).toFloat()
                        ..durationMs.coerceAtLeast(1L).toFloat(),
                    enabled = durationMs > 1L && !exporting,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent
                    )
                )

                Text(
                    text = "selected duration: ${
                        formatTime((endMs - startMs).coerceAtLeast(0L))
                    }",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sourceUri != null &&
                        durationMs > 1L &&
                        endMs > startMs &&
                        !exporting,
                    onClick = {
                        saveVideo.launch("Motioncut-export.mp4")
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

private fun formatTime(milliseconds: Long): String {
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