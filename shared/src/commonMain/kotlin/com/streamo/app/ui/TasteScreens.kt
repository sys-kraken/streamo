package com.streamo.app.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamo.app.data.ChannelRef
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.Recommender
import com.streamo.app.data.currentTasteProfile
import com.streamo.app.nowMillis
import com.streamo.app.player.PlayerConnection
import com.streamo.app.utcOffsetMillis

@Composable
private fun PageTitle(title: String, nav: Nav) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

// ---------------- Liked videos ----------------

@Composable
fun LikedScreen(nav: Nav, contentPadding: PaddingValues) {
    val liked by LibraryStore.liked.collectAsState()
    val c = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { PageTitle("Liked videos", nav) }
        if (liked.isEmpty()) {
            item { EmptyMessage("No liked videos yet", "Tap Like under a video. Liked videos stay on this phone and shape your recommendations.") }
            return@LazyColumn
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { PlayerConnection.playQueue("Liked videos", liked, 0) },
                    colors = ButtonDefaults.buttonColors(containerColor = c.onBackground, contentColor = c.background),
                ) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play all") }
                OutlinedButton(onClick = { PlayerConnection.playQueue("Liked videos", liked.shuffled(), 0) }) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp), tint = c.onBackground)
                    Spacer(Modifier.width(6.dp)); Text("Shuffle", color = c.onBackground)
                }
            }
        }
        itemsIndexed(liked, key = { _, v -> v.url }) { i, v ->
            VideoRow(
                v,
                extraActions = listOf(MenuAction(Icons.Default.ThumbDown, "Remove from liked videos") { LibraryStore.toggleLike(v) }),
            ) { PlayerConnection.playQueue("Liked videos", liked, i) }
        }
    }
}

// ---------------- Your interests ----------------

/** Shows what the recommender has learned and lets you take things out of it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InterestsScreen(nav: Nav, contentPadding: PaddingValues) {
    val hidden by LibraryStore.hidden.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val liked by LibraryStore.liked.collectAsState()
    val subs by LibraryStore.subscriptions.collectAsState()
    val profile = remember(hidden, history.size, liked.size, subs.size) { currentTasteProfile() }
    // Channel links -> names and pictures, from everything saved.
    val channels = remember(history.size, liked.size, subs.size) {
        val map = LinkedHashMap<String, ChannelRef>()
        subs.forEach { map[it.url] = it }
        (liked + history).forEach { v ->
            val key = Recommender.channelKey(v) ?: return@forEach
            if (key !in map) map[key] = ChannelRef(key, v.channelName, v.channelAvatar)
        }
        map
    }
    val topics = profile.topTopics(24)
    val topChannels = profile.channelAffinity.filter { it.value > 0.2 && it.key !in hidden.channels }
        .entries.sortedByDescending { it.value }.take(12).mapNotNull { channels[it.key] }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { PageTitle("Your interests", nav) }
        item {
            Text(
                "Streamo learns what you like from what you watch, finish, rewatch, like, search for and subscribe to — " +
                    "all on this phone. Remove anything that doesn't fit and you'll see less of it.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { SectionHeader("Topics") }
        item {
            if (topics.isEmpty()) {
                Text("Watch a few videos and your topics will show up here.", Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(
                    Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    topics.forEach { t -> RemovableChip(t) { LibraryStore.hideTopic(t) } }
                }
            }
        }

        item { SectionHeader("Channels you're into") }
        if (topChannels.isEmpty()) item {
            Text("None yet.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(topChannels, key = { "c" + it.url }) { ch ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { nav.openChannel(ch.url) }.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(ch.avatar, ch.name, 36.dp)
                Spacer(Modifier.width(14.dp))
                Text(ch.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { LibraryStore.hideChannel(ch.url) }) {
                    Text("Don't recommend", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { SectionHeader("Your habits") }
        item {
            val lengthNames = listOf("short videos (under 4 min)", "4–20 minute videos", "20–60 minute videos", "videos over an hour")
            val favouriteLength = profile.lengthPreference.maxByOrNull { it.value }?.key
            val part = listOf("at night", "in the morning", "in the afternoon", "in the evening")[
                Recommender.dayPart(nowMillis(), utcOffsetMillis(nowMillis()))]
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (favouriteLength != null) "You mostly watch ${lengthNames[favouriteLength]}." else "Not enough history yet to know what length you prefer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (profile.timeOfDayChannels.isNotEmpty()) "Right now ($part) Home leans towards what you usually watch $part."
                    else "Streamo also learns what you like to watch at different times of day.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (hidden.topics.isNotEmpty() || hidden.channels.isNotEmpty()) {
            item { SectionHeader("Removed") }
            if (hidden.topics.isNotEmpty()) item {
                FlowRow(
                    Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    hidden.topics.forEach { t -> RemovableChip(t, restore = true) { LibraryStore.unhideTopic(t) } }
                }
            }
            items(hidden.channels.toList(), key = { "h" + it }) { url ->
                val ch = channels[url]
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ch?.name ?: url.substringAfterLast('/'), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { LibraryStore.unhideChannel(url) }) {
                        Text("Restore", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RemovableChip(label: String, restore: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(50))
            .background(if (restore) c.background else c.surfaceVariant)
            .then(if (restore) Modifier.background(c.surfaceContainer) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (restore) c.onSurfaceVariant else c.onBackground)
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (restore) Text("+", style = MaterialTheme.typography.labelLarge, color = c.onBackground)
            else Icon(Icons.Default.Close, "Remove $label", Modifier.size(16.dp))
        }
    }
}
