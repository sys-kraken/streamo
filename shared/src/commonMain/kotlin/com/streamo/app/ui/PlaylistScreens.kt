package com.streamo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.LocalPlaylist
import com.streamo.app.data.PlaylistRef
import com.streamo.app.data.VideoItem
import com.streamo.app.data.YouTubeRepo
import com.streamo.app.player.PlayerConnection

// ---------------- A playlist you made ----------------

@Composable
fun LocalPlaylistScreen(id: String, nav: Nav, contentPadding: PaddingValues) {
    val playlists by LibraryStore.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    if (playlist == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            BackRow(nav)
            EmptyMessage("Playlist not found", "It may have been deleted.")
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Default.DeleteOutline, "Delete playlist") }
            }
            PlaylistHeader(
                title = playlist.name,
                subtitle = "${playlist.videos.size} videos · Your playlist",
                thumbnail = playlist.videos.firstOrNull()?.thumbnail,
                videos = playlist.videos,
            )
        }
        if (playlist.videos.isEmpty()) item {
            EmptyMessage("No videos yet", "While watching a video, tap Save to add it here.")
        }
        itemsIndexed(playlist.videos, key = { _, v -> v.url }) { i, v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    VideoRow(v) { PlayerConnection.playQueue(playlist.name, playlist.videos, i) }
                }
                IconButton(onClick = { LibraryStore.removeFromPlaylist(playlist.id, v.url) }) {
                    Icon(Icons.Default.Close, "Remove from playlist", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (renaming) NameDialog("Rename playlist", playlist.name, "Rename", onDismiss = { renaming = false }) {
        LibraryStore.renamePlaylist(playlist.id, it); renaming = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete \"${playlist.name}\"?") },
        text = { Text("The videos stay on YouTube; only this playlist is removed.") },
        confirmButton = {
            TextButton(onClick = { deleting = false; LibraryStore.deletePlaylist(playlist.id); nav.back() }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
}

// ---------------- A YouTube playlist ----------------

@Composable
fun YouTubePlaylistScreen(url: String, nav: Nav, contentPadding: PaddingValues) {
    val videos = remember(url) { mutableStateListOf<VideoItem>() }
    var next by remember(url) { mutableStateOf<Any?>(null) }
    var loadingMore by remember(url) { mutableStateOf(false) }
    var saved by remember(url) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val loader = rememberLoad(url) {
        YouTubeRepo.playlist(url).also { videos.clear(); videos.addAll(it.videos); next = it.next }
    }

    // Load the next page when the end of the list comes into view.
    LaunchedEffect(listState, url) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val more = next
            if (more != null && !loadingMore && last >= videos.size - 3 && videos.isNotEmpty()) {
                loadingMore = true
                runCatching { YouTubeRepo.playlistMore(url, more) }.onSuccess { (items, n) ->
                    videos.addAll(items.filter { v -> videos.none { it.url == v.url } }); next = n
                }
                loadingMore = false
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
        item { BackRow(nav) }
        when (val s = loader.state) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { ErrorMessage(s.message, loader.reload) }
            is Load.Done -> {
                val page = s.value
                item {
                    PlaylistHeader(
                        title = page.name,
                        subtitle = listOf(page.uploader.takeIf { it.isNotBlank() }, "${maxOf(page.count, videos.size.toLong())} videos")
                            .filterNotNull().joinToString(" · "),
                        thumbnail = page.thumbnail,
                        videos = videos,
                    ) {
                        OutlinedButton(
                            onClick = { LibraryStore.createPlaylist(page.name, videos.toList()); saved = true },
                            enabled = !saved,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (saved) "Saved" else "Save")
                        }
                    }
                }
                itemsIndexed(videos, key = { _, v -> v.url }) { i, v ->
                    VideoRow(v) { PlayerConnection.playQueue(page.name, videos.toList(), i) }
                }
                if (loadingMore) item { Loading() }
            }
        }
    }
}

// ---------------- Shared pieces ----------------

@Composable
private fun BackRow(nav: Nav) {
    Row(Modifier.padding(top = 8.dp)) {
        IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }
}

@Composable
private fun PlaylistHeader(
    title: String,
    subtitle: String,
    thumbnail: String?,
    videos: List<VideoItem>,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                AsyncImage(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { PlayerConnection.playQueue(title, videos.toList(), 0) },
                enabled = videos.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Parchment, contentColor = Ink),
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play all")
            }
            OutlinedButton(
                onClick = { PlayerConnection.playQueue(title, videos.shuffled(), 0) },
                enabled = videos.isNotEmpty(),
            ) {
                Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle")
            }
            extra?.invoke()
        }
    }
}

/** Playlist row used in Library and in search results. */
@Composable
fun PlaylistRow(title: String, subtitle: String, thumbnail: String?, count: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.width(168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                AsyncImage(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Count strip on the right, like YouTube.
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(64.dp).background(Color(0xCC000000)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(if (count >= 0) count.toString() else "", color = Color.White, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
fun YouTubePlaylistResult(p: PlaylistRef, onClick: () -> Unit) =
    PlaylistRow(p.name, listOf(p.uploader, "Playlist").filter { it.isNotBlank() }.joinToString(" · "), p.thumbnail, p.count, onClick)

@Composable
fun LocalPlaylistRow(p: LocalPlaylist, onClick: () -> Unit) =
    PlaylistRow(p.name, "${p.videos.size} videos", p.videos.firstOrNull()?.thumbnail, p.videos.size.toLong(), onClick)

/** "Save to playlist" sheet from the watch screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToPlaylistSheet(video: VideoItem, onDismiss: () -> Unit) {
    val playlists by LibraryStore.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Save to…", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            playlists.forEach { p ->
                val inIt = p.videos.any { it.url == video.url }
                Row(
                    Modifier.fillMaxWidth().clickable { LibraryStore.toggleInPlaylist(p.id, video) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (inIt) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null,
                        tint = if (inIt) Ember else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(18.dp))
                    Text(p.name, Modifier.weight(1f))
                    Text("${p.videos.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(18.dp))
                Text("New playlist")
            }
        }
    }
    if (creating) NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
        LibraryStore.createPlaylist(name, listOf(video)); creating = false
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("Playlist name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
