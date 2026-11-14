package com.streamo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamo.app.data.Comment
import com.streamo.app.data.CommentsPage
import com.streamo.app.data.YouTubeRepo
import com.streamo.app.htmlToText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** The first page of comments per video, shared by the preview card and the sheet so it loads once. */
@OptIn(ExperimentalCoroutinesApi::class)
private object CommentsCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pages = HashMap<String, Deferred<CommentsPage>>()

    fun first(url: String, refresh: Boolean = false): Deferred<CommentsPage> {
        val old = pages[url]
        val failed = old != null && old.isCompleted && old.getCompletionExceptionOrNull() != null
        if (old != null && !refresh && !failed) return old
        if (pages.size > 30) pages.clear()
        return scope.async { YouTubeRepo.comments(url) }.also { pages[url] = it }
    }
}

/** The "Comments" card under a video, like YouTube: the count and the top comment; tap to see them all. */
@Composable
fun CommentsPreview(url: String) {
    var open by remember(url) { mutableStateOf(false) }
    if (open) CommentsSheet(url) { open = false }
    val loader = rememberLoad(url) { refresh -> CommentsCache.first(url, refresh).await() }
    val state = loader.state
    if (state is Load.Done && state.value.disabled) {
        CardBox(onClick = null) {
            Text("Comments are turned off.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    CardBox(onClick = { if (state is Load.Failed) loader.reload() else open = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Comments", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            val count = (state as? Load.Done)?.value?.count ?: -1
            if (count > 0) {
                Spacer(Modifier.width(8.dp))
                Text(formatCount(count, "").trim(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        when (state) {
            Load.Loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is Load.Failed -> Text("${state.message} Tap to try again.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            is Load.Done -> {
                val top = state.value.comments.firstOrNull()
                if (top == null) {
                    Text("No comments yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(top.authorAvatar, top.author.removePrefix("@"), 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(htmlToText(top.text).trim(), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardBox(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(12.dp)
    ) { content() }
}

/** All comments in a sheet that slides up over the video page; more load as you scroll. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(url: String, onDismiss: () -> Unit) {
    val comments = remember(url) { mutableStateListOf<Comment>() }
    var next by remember(url) { mutableStateOf<Any?>(null) }
    var count by remember(url) { mutableStateOf(-1L) }
    var loadingMore by remember(url) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val loader = rememberLoad(url) { refresh ->
        val page = CommentsCache.first(url, refresh).await()
        comments.clear(); comments.addAll(page.comments); next = page.next; count = page.count
    }
    LaunchedEffect(listState, url) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val more = next
            if (more != null && !loadingMore && comments.isNotEmpty() && last >= comments.size - 5) {
                loadingMore = true
                runCatching { YouTubeRepo.commentsMore(url, more) }.onSuccess { page ->
                    val seen = comments.map { it.id }.toSet()
                    comments.addAll(page.comments.filter { it.id !in seen })
                    next = page.next
                }.onFailure { next = null }
                loadingMore = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Comments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (count > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(formatCount(count, "").trim(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                when (val s = loader.state) {
                    Load.Loading -> item { Loading() }
                    is Load.Failed -> item { ErrorMessage(s.message, loader.reload) }
                    is Load.Done -> {
                        if (comments.isEmpty()) item { EmptyMessage("No comments yet", "Be the first on YouTube.") }
                        items(comments, key = { it.id }) { c -> CommentRow(c) }
                        if (loadingMore) item { Loading() }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(c: Comment) {
    var expanded by remember(c.id) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Avatar(c.authorAvatar, c.author.removePrefix("@"), 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (c.pinned) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PushPin, null, Modifier.size(14.dp), tint = muted)
                    Spacer(Modifier.width(4.dp))
                    Text("Pinned", style = MaterialTheme.typography.labelSmall, color = muted)
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(
                listOfNotNull(c.author, c.published?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (c.byChannelOwner) MaterialTheme.colorScheme.onBackground else muted,
                fontWeight = if (c.byChannelOwner) FontWeight.Bold else FontWeight.Normal,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                htmlToText(c.text).trim(), style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.ThumbUp, "Likes", Modifier.size(16.dp), tint = muted)
                if (c.likes > 0) Text(formatCount(c.likes, "").trim(), style = MaterialTheme.typography.labelSmall, color = muted)
                if (c.heartedByCreator) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Favorite, "Hearted by the creator", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onBackground)
                }
                if (c.replyCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(if (c.replyCount == 1) "1 reply" else "${c.replyCount} replies",
                        style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
}
