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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.streamo.app.data.ChannelAvatars
import com.streamo.app.data.VideoItem
import com.streamo.app.data.YouTubeRepo

/** Starts loading a video the moment a finger touches it, before the tap completes. */
fun Modifier.prefetchOnTouch(url: String): Modifier = pointerInput(url) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        YouTubeRepo.prefetch(url)
    }
}

/** Full-width card like YouTube's home feed. */
@Composable
fun VideoCard(video: VideoItem, onClick: () -> Unit, onChannel: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .prefetchOnTouch(video.url)
            .clickable(onClick = onClick)
            .padding(bottom = 16.dp)
    ) {
        Thumbnail(video, Modifier.fillMaxWidth())
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(Modifier.clip(CircleShape).clickable(enabled = onChannel != null) { onChannel?.invoke() }) {
                ChannelAvatar(video, 36.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle(video.channelName, video.views, video.uploaded, video.isLive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Compact row: thumbnail on the left, used for related videos, history and search. */
@Composable
fun VideoRow(video: VideoItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .prefetchOnTouch(video.url)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Thumbnail(video, Modifier.width(168.dp), corner = 8.dp)
        Column(Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(4.dp))
            Text(video.channelName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                subtitle("", video.views, video.uploaded, video.isLive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
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
        val badge = if (video.isLive) "LIVE" else formatDuration(video.durationSec)
        if (badge.isNotEmpty()) {
            Text(
                badge,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(if (video.isLive) Color(0xFFCC0000) else Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
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

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
        OutlinedButton(onClick = onRetry) { Text("Try again") }
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
