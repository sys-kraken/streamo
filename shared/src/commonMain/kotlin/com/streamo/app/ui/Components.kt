package com.streamo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.streamo.app.Platform
import com.streamo.app.data.ChannelAvatars
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.VideoItem
import com.streamo.app.data.YouTubeRepo

/**
 * Starts looking a video up as soon as a finger rests on it, a moment before the tap completes.
 * A finger that moves (scrolling past) doesn't count, so scrolling never triggers lookups.
 */
fun Modifier.prefetchOnTouch(url: String): Modifier = pointerInput(url) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val outcome = withTimeoutOrNull(90) {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (!change.pressed) return@withTimeoutOrNull true // quick tap
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        // null = still resting on it after 90 ms; true = tapped; false = scrolled away.
        if (outcome != false) YouTubeRepo.prefetch(url)
    }
}

/** Extra entries for a video's ⋮ menu, e.g. "Remove from watch history". */
class MenuAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** Full-width card like YouTube's home feed: big thumbnail, then avatar, title, details and ⋮. */
@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    onChannel: (() -> Unit)? = null,
    extraActions: List<MenuAction> = emptyList(),
    recommendable: Boolean = true,
    reason: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .prefetchOnTouch(video.url)
            .clickable(onClick = onClick)
            .padding(bottom = 20.dp)
    ) {
        Thumbnail(video, Modifier.fillMaxWidth())
        Row(Modifier.padding(start = 12.dp, top = 12.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(enabled = onChannel != null) { onChannel?.invoke() },
                contentAlignment = Alignment.TopStart,
            ) { ChannelAvatar(video, 36.dp) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                Text(
                    video.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle(video.channelName, video.views, video.uploaded, video.isLive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (reason != null) {
                    Text(
                        reason, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            VideoMenu(video, extraActions, recommendable)
        }
    }
}

/** Compact row: thumbnail on the left, used for history, playlists and channel pages. */
@Composable
fun VideoRow(
    video: VideoItem,
    extraActions: List<MenuAction> = emptyList(),
    showMenu: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .prefetchOnTouch(video.url)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = if (showMenu) 0.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumbnail(video, Modifier.width(160.dp), corner = 8.dp)
        Column(Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(4.dp))
            Text(video.channelName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                subtitle("", video.views, video.uploaded, video.isLive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        if (showMenu) VideoMenu(video, extraActions, recommendable = false)
    }
}

/** The ⋮ button with Save, Share, Not interested and anything the screen adds. */
@Composable
fun VideoMenu(video: VideoItem, extraActions: List<MenuAction> = emptyList(), recommendable: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    if (saving) SaveToPlaylistSheet(video) { saving = false }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.MoreVert, "More", Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            MenuRow(Icons.AutoMirrored.Filled.PlaylistAdd, "Save to playlist") { open = false; saving = true }
            MenuRow(Icons.Default.Share, "Share") {
                open = false
                Platform.ui.share(video.title, "${video.title}\n${video.url}")
            }
            extraActions.forEach { a -> MenuRow(a.icon, a.label) { open = false; a.onClick() } }
            if (recommendable) {
                MenuRow(Icons.Default.DoNotDisturbOn, "Not interested") {
                    open = false
                    LibraryStore.hideVideo(video.url)
                    Platform.ui.toast("Got it. You'll see fewer videos like this.")
                }
                video.channelUrl?.let { ch ->
                    MenuRow(Icons.Default.Block, "Don't recommend channel") {
                        open = false
                        LibraryStore.hideChannel(ch)
                        Platform.ui.toast("Videos from ${video.channelName} won't be recommended.")
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    )
}

@Composable
fun Thumbnail(video: VideoItem, modifier: Modifier = Modifier, corner: Dp = 0.dp) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = video.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val badge = if (video.isLive) "● LIVE" else formatDuration(video.durationSec)
        if (badge.isNotEmpty()) {
            Text(
                badge,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 8.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        WatchedBar(video.url, Modifier.align(Alignment.BottomStart))
    }
}

/** The thin bar along the bottom of a thumbnail showing how much of it you've watched. */
@Composable
fun WatchedBar(url: String, modifier: Modifier = Modifier) {
    val watch = LibraryStore.watch.collectAsState()
    // Only redraw when this video's own progress changes, not on every save for another video.
    val fraction by remember(url) { derivedStateOf { watch.value[url]?.fraction?.takeIf { it > 0.02f } } }
    val f = fraction ?: return
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x99777777))) {
        Box(Modifier.fillMaxWidth(f).fillMaxSize().background(Color.White))
    }
}

/** The uploader's picture; looked up once per channel if the list didn't include it. */
@Composable
fun ChannelAvatar(video: VideoItem, size: Dp) {
    val url by produceState(video.channelAvatar ?: ChannelAvatars.peek(video.channelUrl), video.channelUrl) {
        val channel = video.channelUrl
        if (value == null && channel != null) value = ChannelAvatars.get(channel)
    }
    Avatar(url, video.channelName, size)
}

@Composable
fun Avatar(url: String?, name: String, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold)
        AsyncImage(model = url, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** YouTube's rounded filter chip: grey when off, white-on-black (or black-on-white) when on. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.onBackground else colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (selected) colors.background else colors.onBackground,
            style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
        )
    }
}

/** Section title with an optional action on the right, e.g. "History  ·  View all". */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (action != null && onAction != null) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text(action, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground, strokeWidth = 3.dp)
    }
}

@Composable
fun ErrorMessage(text: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onRetry) { Text("Try again", color = MaterialTheme.colorScheme.onBackground) }
    }
}

@Composable
fun EmptyMessage(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A small round × for removing something from a list. */
@Composable
fun RemoveButton(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Default.Close, label, Modifier.size(20.dp)) }
}
