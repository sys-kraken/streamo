package com.streamo.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.SkipNext
import com.streamo.app.player.SponsorState
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamo.app.Platform
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.Recommender
import com.streamo.app.nowMillis
import com.streamo.app.data.YouTubeRepo
import com.streamo.app.htmlToText
import com.streamo.app.player.NowPlaying
import com.streamo.app.player.PlayQueue
import com.streamo.app.player.PlayerConnection
import kotlinx.coroutines.delay

/** The video surface with YouTube-style controls (seek bar, play/pause, fullscreen). */
@Composable
fun PlayerSurface(
    modifier: Modifier,
    controls: Boolean,
    fullscreen: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val now by PlayerConnection.nowPlaying.collectAsState()
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Platform.ui.VideoView(Modifier.fillMaxSize(), controls, fullscreen, onFullscreen, onSettings)
        val state = now
        if (state?.error != null) {
            Column(
                Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { PlayerConnection.retry() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text(state.error, color = Color.White) }
        } else if (state != null && !state.started) {
            // Until the new video starts, show its thumbnail instead of the last frame of the old one.
            coil3.compose.AsyncImage(
                model = state.video.thumbnail,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color(0x55000000)))
            if (controls) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        } else if (state != null && state.isBuffering && !state.isPlaying && controls) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        }
        // SponsorBlock: "Skip sponsor" for segments set to Ask, "Jump to highlight" near the start.
        val prompt by SponsorState.prompt.collectAsState()
        val p = prompt
        if (controls && p != null && state?.error == null) {
            val name = com.streamo.app.data.SponsorBlock.NAMES[p.category] ?: "segment"
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 56.dp)
                    .background(Color(0xE6FFFFFF), RoundedCornerShape(50))
                    .clickable { PlayerConnection.seekTo(if (p.isHighlight) p.startMs else p.endMs); SponsorState.setPrompt(null) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (p.isHighlight) "Jump to highlight" else "Skip ${name.lowercase()}", color = Color.Black,
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp), tint = Color.Black)
            }
        }
    }
}

/** "Off", "12 min left" or "End of video", for the sleep timer row. */
private fun sleepLabel(now: NowPlaying): String = when {
    now.sleepAtEnd -> "End of video"
    now.sleepAtMs != null -> "${((now.sleepAtMs - com.streamo.app.nowMillis()) / 60_000 + 1).coerceAtLeast(1)} min left"
    else -> "Off"
}

@Composable
fun WatchScreen(
    now: NowPlaying,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onCollapse: () -> Unit,
    onPip: () -> Unit,
    nav: Nav,
) {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) PlayerSettingsSheet(now) { showSettings = false }
    if (fullscreen) {
        PlayerSurface(
            Modifier.fillMaxSize(), controls = true, fullscreen = true,
            onFullscreen = onToggleFullscreen, onSettings = { showSettings = true },
        )
        return
    }
    val settings by LibraryStore.settings.collectAsState()
    val subs by LibraryStore.subscriptions.collectAsState()
    val url = now.video.url
    val details = rememberLoad(url) { YouTubeRepo.details(url, settings.maxHeight) }
    val queue by PlayQueue.queue.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val hidden by LibraryStore.hidden.collectAsState()
    // Look up the first few "up next" videos ahead of time so tapping one starts quickly.
    LaunchedEffect(details.state) {
        val related = (details.state as? Load.Done)?.value?.related ?: return@LaunchedEffect
        // The player already prepares the first suggestion for Next. Wait until this video has
        // had time to start before looking up one more, so it doesn't compete for bandwidth.
        delay(4_000)
        related.getOrNull(1)?.let { YouTubeRepo.prefetch(it.url) }
    }

    // Streamo's own order: what autoplay picks next comes first, watched videos last.
    val liked by LibraryStore.liked.collectAsState()
    val ranked = remember(details.state, history.size, hidden, liked.size) {
        val d = (details.state as? Load.Done)?.value ?: return@remember emptyList()
        Recommender.rankRelated(d.item, d.related, com.streamo.app.data.currentTasteProfile())
    }

    var dragged by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Box(
            // Swipe down on the video to shrink it into the mini player, like YouTube.
            Modifier.pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, dy -> dragged += dy },
                    onDragEnd = { if (dragged > 120f) onCollapse() },
                )
            }
        ) {
            PlayerSurface(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                controls = true,
                onFullscreen = onToggleFullscreen,
                onSettings = { showSettings = true },
            )
            IconButton(onClick = onCollapse, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.Default.KeyboardArrowDown, "Minimize", tint = Color.White)
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                val video = (details.state as? Load.Done)?.value?.item ?: now.video
                var expanded by remember(url) { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp).animateContentSize()
                ) {
                    Text(video.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(subtitle("", video.views, video.uploaded, video.isLive).replace(" • ", "  "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!expanded) Text("  ...more", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    val desc = (details.state as? Load.Done)?.value?.description
                    if (expanded && !desc.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            htmlToText(desc).trim(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp),
                        )
                    }
                }
            }
            item { ChannelRow(now, details, subs.map { it.url }.toSet(), nav, onCollapse) }
            item { ActionRow(now, details, onPip) }
            item { CommentsPreview(url) }
            item { HorizontalDivider(Modifier.padding(top = 4.dp)) }
            // Playing a playlist: show it first, with the current video marked.
            val q = queue
            if (q != null && q.videos.any { it.url == now.video.url }) {
                item {
                    Text(
                        "${q.title} · ${q.index + 1} / ${q.videos.size}",
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                itemsIndexed(q.videos, key = { i, v -> "q$i" + v.url }) { i, v ->
                    Box(Modifier.background(if (i == q.index) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)) {
                        VideoRow(v) { PlayerConnection.play(v, fromQueue = true) }
                    }
                }
                item {
                    Text("More videos", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleSmall)
                }
            }
            when (val s = details.state) {
                Load.Loading -> item { Loading() }
                is Load.Failed -> item { ErrorMessage(s.message, details.reload) }
                is Load.Done -> {
                    if (ranked.isNotEmpty() && (q == null || q.videos.none { it.url == now.video.url })) item {
                        Text("Up next", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    items(ranked, key = { "r" + it.url }) { v ->
                        VideoCard(v, onClick = { PlayerConnection.play(v) }, onChannel = v.channelUrl?.let { ch -> { onCollapse(); nav.openChannel(ch) } })
                    }
                }
            }
            item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
        }
    }
}

@Composable
private fun ChannelRow(
    now: NowPlaying,
    details: Loader<com.streamo.app.data.VideoDetails>,
    subscribed: Set<String>,
    nav: Nav,
    onCollapse: () -> Unit,
) {
    val d = (details.state as? Load.Done)?.value
    val channelUrl = d?.item?.channelUrl ?: now.video.channelUrl
    val name = d?.item?.channelName ?: now.video.channelName
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = channelUrl != null) { onCollapse(); channelUrl?.let(nav.openChannel) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(d?.channelAvatar, name, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d != null && d.channelSubscribers >= 0) {
                Text(formatCount(d.channelSubscribers, "subscribers"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (channelUrl != null) {
            SubscribeButton(channelUrl in subscribed) {
                LibraryStore.toggleSubscription(com.streamo.app.data.ChannelRef(channelUrl, name, d?.channelAvatar))
            }
        }
    }
}

@Composable
private fun ActionRow(now: NowPlaying, details: Loader<com.streamo.app.data.VideoDetails>, onPip: () -> Unit) {
    var saving by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    val video = (details.state as? Load.Done)?.value?.item ?: now.video
    if (saving) SaveToPlaylistSheet(video) { saving = false }
    if (downloading) DownloadSheet(video) { downloading = false }
    val likes = (details.state as? Load.Done)?.value?.likes ?: -1
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val likedList by LibraryStore.liked.collectAsState()
        val liked = likedList.any { it.url == video.url }
        Pill(
            if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            if (likes >= 0) formatCount(likes, "").trim() else "Like",
            selected = liked,
        ) { LibraryStore.toggleLike(video) }
        Pill(Icons.Default.Share, "Share") {
            Platform.ui.share(now.video.title, "${now.video.title}\n${now.video.url}")
        }
        Pill(Icons.AutoMirrored.Filled.PlaylistAdd, "Save") { saving = true }
        Pill(Icons.Default.Download, "Download") { downloading = true }
        Pill(Icons.Default.PictureInPictureAlt, "Pop-up", onClick = onPip)
    }
}

/** YouTube-style settings sheet opened from the gear on the video. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(now: NowPlaying, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf("main") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            when (page) {
                "main" -> {
                    SheetRow(Icons.Default.HighQuality, "Quality", if (now.height > 0) "${now.height}p" else "Auto") { page = "quality" }
                    SheetRow(Icons.Default.Speed, "Playback speed", if (now.speed == 1f) "Normal" else "${now.speed}x") { page = "speed" }
                    SheetRow(Icons.Default.Headphones, "Audio only", if (now.audioOnly) "On" else "Off") {
                        PlayerConnection.setAudioOnly(!now.audioOnly); onDismiss()
                    }
                    SheetRow(Icons.Default.Bedtime, "Sleep timer", sleepLabel(now)) { page = "sleep" }
                }
                "sleep" -> {
                    SheetTitle("Sleep timer") { page = "main" }
                    SheetChoice("Off", now.sleepAtMs == null && !now.sleepAtEnd) { PlayerConnection.setSleepTimer(null); onDismiss() }
                    listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                        SheetChoice(if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "1 hour 30 minutes", false) {
                            PlayerConnection.setSleepTimer(m); onDismiss()
                            Platform.ui.toast("Playback will stop in ${if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "1.5 hours"}.")
                        }
                    }
                    SheetChoice("End of this video", now.sleepAtEnd) { PlayerConnection.setSleepTimer(null, endOfVideo = true); onDismiss() }
                }
                "quality" -> {
                    SheetTitle("Quality") { page = "main" }
                    if (now.heights.isEmpty()) Text("Only one quality is available for this video.", Modifier.padding(20.dp))
                    now.heights.forEach { h ->
                        val tag = when { h >= 2160 -> " 4K"; h >= 720 -> " HD"; else -> "" }
                        SheetChoice("${h}p$tag", h == now.height) { PlayerConnection.setQuality(h); onDismiss() }
                    }
                }
                "speed" -> {
                    SheetTitle("Playback speed") { page = "main" }
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { sp ->
                        SheetChoice(if (sp == 1f) "Normal" else "${sp}x", sp == now.speed) { PlayerConnection.setSpeed(sp); onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(20.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SheetTitle(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SheetChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(44.dp)) { if (selected) Icon(Icons.Default.Check, null) }
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .background(if (selected) c.onBackground else c.surfaceVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (selected) c.background else c.onBackground)
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
                color = if (selected) c.background else c.onBackground)
        }
    }
}

/** Bar above the bottom navigation while a video plays and you browse elsewhere. */
@Composable
fun MiniPlayer(now: NowPlaying, onExpand: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            PlayerConnection.progress()?.let { progress = it }
            delay(500)
        }
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onExpand),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerSurface(Modifier.width(114.dp).height(64.dp), controls = false)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(now.video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(now.video.channelName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = PlayerConnection::togglePlay) {
                Icon(if (now.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause")
            }
            IconButton(onClick = PlayerConnection::close) { Icon(Icons.Default.Close, "Close") }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.onBackground,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
    }
}

