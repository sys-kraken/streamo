package com.streamo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamo.app.Platform
import com.streamo.app.data.DownloadOption
import com.streamo.app.data.VideoItem
import com.streamo.app.data.YouTubeRepo
import com.streamo.app.download.DownloadEntry
import com.streamo.app.download.Downloads
import com.streamo.app.player.PlayerConnection

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> oneDecimal(bytes / (1L shl 30).toDouble()) + " GB"
    bytes >= 1L shl 20 -> oneDecimal(bytes / (1L shl 20).toDouble()) + " MB"
    else -> "${bytes / 1024} KB"
}

/** "Download" sheet from the watch screen: pick a quality or sound only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(video: VideoItem, onDismiss: () -> Unit) {
    val options = rememberLoad(video.url) { YouTubeRepo.downloadOptions(video.url) }

    fun start(o: DownloadOption) {
        Downloads.enqueue(video, o.audioOnly, o.height)
        Platform.ui.toast("Downloading… see Library → Downloads")
        onDismiss()
    }
    // Android 9 and older ask for storage permission before saving to Movies / Music.
    val withPermission = Platform.ui.rememberDownloadPermission()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Download", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            when (val s = options.state) {
                Load.Loading -> Loading()
                is Load.Failed -> ErrorMessage(s.message, options.reload)
                is Load.Done -> {
                    if (s.value.isEmpty()) Text("This video can't be downloaded.", Modifier.padding(20.dp))
                    s.value.forEach { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { withPermission { start(o) } }.padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (o.audioOnly) Icons.Default.MusicNote else Icons.Default.Videocam, null)
                            Spacer(Modifier.width(18.dp))
                            Text(
                                if (o.audioOnly) "Sound only (M4A)" else "Video ${o.height}p" + if (o.height >= 720) " HD" else "",
                                Modifier.weight(1f),
                            )
                            Text(formatBytes(o.sizeBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "Videos are saved to Movies/Streamo, sound to Music/Streamo.",
                        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(nav: Nav, onPlayed: () -> Unit, contentPadding: PaddingValues) {
    val entries by Downloads.entries.collectAsState()
    val progress by Downloads.progress.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Downloads", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Saved on this phone: videos in Movies/Streamo, sound in Music/Streamo. They play without internet.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entries.isEmpty()) item {
            EmptyMessage("No downloads yet", "While watching a video, tap Download to save it to your phone.")
        }
        items(entries, key = { it.id }) { e ->
            DownloadRow(
                entry = e,
                progress = progress[e.id],
                onOpen = {
                    val uri = e.uri
                    if (e.status == DownloadEntry.Status.DONE && uri != null) {
                        PlayerConnection.playLocal(e.video, uri); onPlayed()
                    }
                },
                onRetry = { Downloads.retry(e.id) },
                onRemove = {
                    if (e.status == DownloadEntry.Status.QUEUED || e.status == DownloadEntry.Status.RUNNING) Downloads.cancel(e.id)
                    else Downloads.delete(e.id)
                },
            )
        }
    }
}

@Composable
private fun DownloadRow(entry: DownloadEntry, progress: Float?, onOpen: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = entry.status == DownloadEntry.Status.DONE, onClick = onOpen)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(140.dp)) { Thumbnail(entry.video, Modifier.fillMaxWidth(), corner = 8.dp) }
        Column(Modifier.weight(1f)) {
            Text(entry.video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val kind = if (entry.audioOnly) "Sound" else "Video ${entry.height}p"
            when (entry.status) {
                DownloadEntry.Status.QUEUED -> Status("$kind · Waiting…")
                DownloadEntry.Status.RUNNING -> {
                    Status("$kind · ${((progress ?: 0f) * 100).toInt()}%")
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress ?: 0f }, color = Ember,
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }
                DownloadEntry.Status.DONE -> Status(listOf(kind, formatBytes(entry.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "))
                DownloadEntry.Status.FAILED -> Text(entry.error ?: "Download failed.", style = MaterialTheme.typography.bodySmall, color = Coral)
            }
        }
        when (entry.status) {
            DownloadEntry.Status.FAILED -> IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "Retry") }
            DownloadEntry.Status.RUNNING -> if (progress == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else -> {}
        }
        IconButton(onClick = onRemove) {
            Icon(
                if (entry.status == DownloadEntry.Status.DONE || entry.status == DownloadEntry.Status.FAILED) Icons.Default.DeleteOutline
                else Icons.Default.Close,
                if (entry.status == DownloadEntry.Status.DONE) "Delete" else "Cancel",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Status(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)

