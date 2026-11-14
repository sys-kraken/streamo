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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamo.app.data.ChannelAvatars
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
import kotlinx.coroutines.launch

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
                        CommentAvatar(top, 24.dp)
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
                        items(comments, key = { it.id }) { c -> CommentRow(c, url) }
                        if (loadingMore) item { Loading() }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(c: Comment, url: String, isReply: Boolean = false) {
    var expanded by remember(c.id) { mutableStateOf(false) }
    var showReplies by remember(c.id) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }
            .padding(start = if (isReply) 0.dp else 16.dp, end = 16.dp, top = if (isReply) 8.dp else 12.dp, bottom = 8.dp),
    ) {
        CommentAvatar(c, if (isReply) 24.dp else 36.dp)
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
                if (c.replyCount > 0 && c.replies == null) {
                    Spacer(Modifier.width(8.dp))
                    Text(if (c.replyCount == 1) "1 reply" else "${c.replyCount} replies",
                        style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
            val handle = c.replies
            if (!isReply && handle != null && c.replyCount > 0) {
                // YouTube-style "▾ 12 replies" that opens the thread right here.
                Row(
                    Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).clickable { showReplies = !showReplies }
                        .heightIn(min = 36.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (showReplies) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (showReplies) "Hide replies" else if (c.replyCount == 1) "1 reply" else "${formatCount(c.replyCount.toLong(), "").trim()} replies",
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    )
                }
                if (showReplies) Replies(url, handle)
            }
        }
    }
}

/**
 * The commenter's real picture. YouTube's comment data no longer includes it (through NewPipe), so
 * it's looked up from their channel, only for comments on screen, a few at a time, and remembered.
 */
@Composable
private fun CommentAvatar(c: Comment, size: androidx.compose.ui.unit.Dp) {
    val picture by produceState(c.authorAvatar ?: ChannelAvatars.peek(c.authorUrl), c.authorUrl) {
        val channel = c.authorUrl
        if (value == null && channel != null) value = ChannelAvatars.get(channel)
    }
    Avatar(picture, c.author.removePrefix("@"), size)
}

/** A comment's replies, loaded when opened; more come with "Show more replies". */
@Composable
private fun Replies(url: String, first: Any) {
    val replies = remember(first) { mutableStateListOf<Comment>() }
    var next by remember(first) { mutableStateOf<Any?>(null) }
    var loadingMore by remember(first) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loader = rememberLoad(first) {
        val page = YouTubeRepo.commentReplies(url, first)
        replies.clear(); replies.addAll(page.comments); next = page.next
    }
    Column {
        when (val s = loader.state) {
            Load.Loading -> CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onBackground)
            is Load.Failed -> Text("${s.message} Tap to try again.", Modifier.clickable { loader.reload() }.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is Load.Done -> {
                replies.forEach { r -> CommentRow(r, url, isReply = true) }
                val more = next
                if (more != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !loadingMore) {
                            loadingMore = true
                            scope.launch {
                                runCatching { YouTubeRepo.commentReplies(url, more) }.onSuccess { page ->
                                    val seen = replies.map { it.id }.toSet()
                                    replies.addAll(page.comments.filter { it.id !in seen }); next = page.next
                                }
                                loadingMore = false
                            }
                        }.heightIn(min = 36.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.SubdirectoryArrowRight, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (loadingMore) "Loading…" else "Show more replies", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
