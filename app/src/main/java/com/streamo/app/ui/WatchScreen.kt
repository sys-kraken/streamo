package com.streamo.app.ui

import android.content.Intent
import androidx.annotation.OptIn
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.YouTubeRepo
import com.streamo.app.player.NowPlaying
import com.streamo.app.player.PlayQueue
import com.streamo.app.player.PlayerConnection
import kotlinx.coroutines.delay

/** The video surface with YouTube-style controls (seek bar, play/pause, fullscreen). */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
    modifier: Modifier,
    controls: Boolean,
    fullscreen: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    val player by PlayerConnection.player.collectAsState()
    val now by PlayerConnection.nowPlaying.collectAsState()
    val settingsClick by rememberUpdatedState(onSettings)
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    setShowShuffleButton(false)
                    setShowSubtitleButton(true)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // The gear opens Streamo's own menu (quality + speed) instead of the default one.
                    findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
                        ?.setOnClickListener { settingsClick?.invoke() }
                    // Seek bar in the logo's ember orange.
                    findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                        setPlayedColor(Ember.toArgb())
                        setScrubberColor(Ember.toArgb())
                        setBufferedColor(Parchment.copy(alpha = 0.45f).toArgb())
                    }
                }
            },
            update = { view ->
                view.player = player
                view.useController = controls
                view.keepScreenOn = now?.isPlaying == true // screen stays on while watching
                view.setFullscreenButtonState(fullscreen)
                if (onFullscreen != null) view.setFullscreenButtonClickListener { onFullscreen() }
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
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
            if (controls) CircularProgressIndicator(color = Ember, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        } else if (state != null && state.isBuffering && !state.isPlaying && controls) {
            CircularProgressIndicator(color = Ember, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
        }
    }
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
    // Look up the first few "up next" videos ahead of time so tapping one starts quickly.
    LaunchedEffect(details.state) {
        val related = (details.state as? Load.Done)?.value?.related ?: return@LaunchedEffect
        // The player already prepares the first suggestion for Next. Wait until this video has
        // had time to start before looking up one more, so it doesn't compete for bandwidth.
        delay(4_000)
        related.getOrNull(1)?.let { YouTubeRepo.prefetch(it.url) }
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
                    Text(video.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle("", video.views, video.uploaded, video.isLive) + if (expanded) "" else "  ...more",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val desc = (details.state as? Load.Done)?.value?.description
                    if (expanded && !desc.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            android.text.Html.fromHtml(desc, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp),
                        )
                    }
                }
            }
            item { ChannelRow(now, details, subs.map { it.url }.toSet(), nav, onCollapse) }
            item { ActionRow(now, details, onPip) }
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
                is Load.Done -> items(s.value.related, key = { "r" + it.url }) { v ->
                    VideoRow(v) { PlayerConnection.play(v) }
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
    val context = LocalContext.current
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
        if (likes >= 0) Pill(Icons.Outlined.ThumbUp, formatCount(likes, "").trim()) {}
        Pill(Icons.Default.Share, "Share") {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, now.video.title)
                .putExtra(Intent.EXTRA_TEXT, "${now.video.title}\n${now.video.url}")
            context.startActivity(Intent.createChooser(send, "Share video"))
        }
        Pill(Icons.AutoMirrored.Filled.PlaylistAdd, "Save") { saving = true }
        Pill(Icons.Default.Download, "Download") { downloading = true }
        Pill(Icons.Default.PictureInPictureAlt, "Pop-up", onPip)
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
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
}

/** Bar above the bottom navigation while a video plays and you browse elsewhere. */
@Composable
fun MiniPlayer(now: NowPlaying, onExpand: () -> Unit) {
    val player by PlayerConnection.player.collectAsState()
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            val p: Player? = player
            if (p != null && p.duration > 0) progress = p.currentPosition.toFloat() / p.duration
            delay(500)
        }
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onExpand),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerSurface(Modifier.width(107.dp).height(60.dp), controls = false)
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
            color = Ember,
            trackColor = Color.Transparent,
            drawStopIndicator = {},
        )
    }
}

