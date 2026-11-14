package com.streamo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamo.app.Platform
import com.streamo.app.data.ChannelRef
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.SearchResult
import com.streamo.app.data.VideoItem
import com.streamo.app.data.YouTubeRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Nav(
    val openVideo: (VideoItem) -> Unit,
    val openChannel: (url: String) -> Unit,
    val openSearch: () -> Unit,
    val openSettings: () -> Unit,
    val openPlaylist: (id: String) -> Unit,
    val openYouTubePlaylist: (url: String) -> Unit,
    val openDownloads: () -> Unit,
    val back: () -> Unit,
)

// ---------------- Home ----------------

private val exploreNames = mapOf(
    FOR_YOU to "For you",
    FEED to "Subscriptions",
    "trending_music" to "Music",
    "trending_gaming" to "Gaming",
    "trending_movies_and_shows" to "Movies & TV",
    "live" to "Live",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav, contentPadding: PaddingValues) {
    val subs by LibraryStore.subscriptions.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val tabs = remember(subs.isEmpty(), history.isEmpty()) {
        listOfNotNull(
            FOR_YOU.takeIf { history.isNotEmpty() },
            FEED.takeIf { subs.isNotEmpty() },
        ) + YouTubeRepo.exploreLists()
    }
    var selected by rememberSaveable { mutableStateOf(tabs.firstOrNull() ?: "") }
    if (selected !in tabs) selected = tabs.firstOrNull() ?: ""

    // Load every tab in the background right away, so switching tabs is instant.
    val loader = rememberLoad(selected to subs.size, cached = Feeds.peek(homeKey(selected, subs))) { refresh ->
        try {
            homeFeed(selected, subs, history, refresh).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(800) // a brief network hiccup often clears up by itself
            homeFeed(selected, subs, history, refresh = true).await()
        }
    }
    // Once the tab on screen has loaded, quietly load the others so switching is instant.
    LaunchedEffect(tabs, subs.size, loader.state is Load.Done) {
        if (loader.state is Load.Done) tabs.forEach { homeFeed(it, subs, history) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { BrandTitle() },
            actions = {
                IconButton(onClick = nav.openSearch) { Icon(Icons.Default.Search, "Search") }
                IconButton(onClick = nav.openSettings) { Icon(Icons.Default.Settings, "Settings") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { tab ->
                FilterChip(
                    selected = tab == selected,
                    onClick = { selected = tab },
                    label = { Text(exploreNames[tab] ?: tab) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Ember,
                        selectedLabelColor = Ink,
                    ),
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = loader.reload,
            modifier = Modifier.fillMaxSize(),
        ) {
            key(selected) {
                VideoFeed(loader, nav, contentPadding, empty = {
                    EmptyMessage("Nothing here yet", "Subscribe to channels to see their newest videos here.")
                })
            }
        }
    }
}

private const val FEED = "_feed"

private fun homeKey(tab: String, subs: List<ChannelRef>) =
    if (tab == FEED) "feed:${subs.size}" else "home:$tab"

private fun homeFeed(tab: String, subs: List<ChannelRef>, history: List<VideoItem>, refresh: Boolean = false) =
    Feeds.load(homeKey(tab, subs), refresh) {
        when (tab) {
            FOR_YOU -> YouTubeRepo.forYou(history)
            FEED -> YouTubeRepo.subscriptionFeed(subs)
            else -> YouTubeRepo.explore(tab)
        }
    }
private const val FOR_YOU = "_for_you"

@Composable
private fun VideoFeed(
    loader: Loader<List<VideoItem>>,
    nav: Nav,
    contentPadding: PaddingValues,
    header: (@Composable () -> Unit)? = null,
    empty: @Composable () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        header?.let { item { it() } }
        when (val s = loader.state) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { ErrorMessage(s.message, loader.reload) }
            is Load.Done -> {
                if (s.value.isEmpty()) item { empty() }
                items(s.value, key = { it.url }) { video ->
                    VideoCard(video, onClick = { nav.openVideo(video) },
                        onChannel = video.channelUrl?.let { url -> { nav.openChannel(url) } })
                }
            }
        }
    }
}

// ---------------- Subscriptions ----------------

@Composable
fun SubscriptionsScreen(nav: Nav, contentPadding: PaddingValues) {
    val subs by LibraryStore.subscriptions.collectAsState()
    val loader = rememberLoad(subs.size, cached = Feeds.peek(homeKey(FEED, subs))) { refresh ->
        homeFeed(FEED, subs, emptyList(), refresh).await()
    }
    if (subs.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            EmptyMessage(
                "No subscriptions yet",
                "Open a channel and tap Subscribe. Their new videos will show up here — no account needed.",
            )
        }
        return
    }
    @OptIn(ExperimentalMaterial3Api::class)
    PullToRefreshBox(isRefreshing = false, onRefresh = loader.reload, modifier = Modifier.fillMaxSize()) {
        VideoFeed(loader, nav, contentPadding, header = {
            LazyRow(
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(subs, key = { it.url }) { ch -> ChannelBubble(ch) { nav.openChannel(ch.url) } }
            }
        }, empty = { EmptyMessage("No new videos", "Pull down to refresh.") })
    }
}

@Composable
private fun ChannelBubble(ch: ChannelRef, onClick: () -> Unit) {
    Column(
        Modifier.width(64.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(ch.avatar, ch.name, 56.dp)
        Text(ch.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Search ----------------

@Composable
fun SearchScreen(nav: Nav, contentPadding: PaddingValues) {
    // The text together with the cursor position, so text filled in for you (a suggestion,
    // a search) puts the cursor at the end instead of wherever it was before.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val query = field.text
    fun setQuery(text: String) { field = TextFieldValue(text, TextRange(text.length)) }
    var submitted by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val results = remember { mutableStateListOf<SearchResult>() }
    var next by remember { mutableStateOf<Any?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val loader = rememberLoad(submitted) {
        if (submitted.isBlank()) return@rememberLoad Unit
        val page = YouTubeRepo.search(submitted)
        results.clear(); results.addAll(page.items); next = page.next
    }

    LaunchedEffect(Unit) { if (submitted.isEmpty()) focus.requestFocus() }
    LaunchedEffect(query) {
        if (query.isBlank() || query == submitted) { suggestions = emptyList(); return@LaunchedEffect }
        delay(200)
        suggestions = YouTubeRepo.suggestions(query)
    }
    // Load the next page when the user nears the end of the results.
    LaunchedEffect(listState, submitted) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val more = next
            if (more != null && !loadingMore && last >= results.size - 4 && results.isNotEmpty()) {
                loadingMore = true
                runCatching { YouTubeRepo.searchMore(submitted, more) }.onSuccess {
                    results.addAll(it.items.filter { r -> r !in results }); next = it.next
                }
                loadingMore = false
            }
        }
    }

    fun submit(text: String) {
        setQuery(text); submitted = text; suggestions = emptyList()
        // Let go of the search box, so its cursor doesn't linger over the results or a video.
        keyboard?.hide(); focusManager.clearFocus()
        scope.launch { listState.scrollToItem(0) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            TextField(
                value = field,
                onValueChange = { field = it },
                placeholder = { Text("Search YouTube") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submit(query.trim()) }),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { setQuery(""); focus.requestFocus() }) {
                        Icon(Icons.Default.Close, "Clear")
                    }
                },
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }

        if (suggestions.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                items(suggestions) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { submit(s) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(s, Modifier.weight(1f))
                        IconButton(onClick = { setQuery("$s ") }) { Icon(Icons.Default.NorthWest, "Use") }
                    }
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
            when (val s = loader.state) {
                Load.Loading -> if (submitted.isNotBlank()) item { Loading() }
                is Load.Failed -> item { ErrorMessage(s.message, loader.reload) }
                is Load.Done -> {
                    if (submitted.isNotBlank() && results.isEmpty()) item { EmptyMessage("No results", "Try different words.") }
                    items(results.size) { i ->
                        when (val r = results[i]) {
                            is SearchResult.Video -> VideoRow(r.video) { nav.openVideo(r.video) }
                            is SearchResult.Channel -> ChannelResult(r) { nav.openChannel(r.channel.url) }
                            is SearchResult.Playlist -> YouTubePlaylistResult(r.playlist) { nav.openYouTubePlaylist(r.playlist.url) }
                        }
                    }
                    if (loadingMore) item { Loading() }
                }
            }
        }
    }
}

@Composable
private fun ChannelResult(r: SearchResult.Channel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(168.dp), contentAlignment = Alignment.Center) { Avatar(r.channel.avatar, r.channel.name, 72.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.channel.name, style = MaterialTheme.typography.titleSmall)
            Text(formatCount(r.subscribers, "subscribers"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- Channel ----------------

@Composable
fun ChannelScreen(url: String, nav: Nav, contentPadding: PaddingValues) {
    val subs by LibraryStore.subscriptions.collectAsState()
    val loader = rememberLoad(url) { YouTubeRepo.channel(url) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.padding(top = 8.dp)) {
                IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
        }
        when (val s = loader.state) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { ErrorMessage(s.message, loader.reload) }
            is Load.Done -> {
                val page = s.value
                item {
                    if (page.banner != null) {
                        AsyncImage(
                            page.banner, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(6.2f).padding(horizontal = 12.dp)
                                .then(Modifier),
                        )
                    }
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(page.channel.avatar, page.channel.name, 80.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(page.channel.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(formatCount(page.subscribers, "subscribers"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        SubscribeButton(subs.any { it.url == page.channel.url }) { LibraryStore.toggleSubscription(page.channel) }
                    }
                    HorizontalDivider()
                    Text("Videos", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                }
                if (page.videos.isEmpty()) item { EmptyMessage("No videos", "This channel hasn't posted videos.") }
                items(page.videos, key = { it.url }) { v -> VideoRow(v) { nav.openVideo(v) } }
            }
        }
    }
}

@Composable
fun SubscribeButton(subscribed: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = if (subscribed) ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = Parchment,
        ) else ButtonDefaults.buttonColors(containerColor = Parchment, contentColor = Ink),
    ) { Text(if (subscribed) "Subscribed" else "Subscribe") }
}

// ---------------- Library ----------------

@Composable
fun LibraryScreen(nav: Nav, contentPadding: PaddingValues) {
    val history by LibraryStore.history.collectAsState()
    val subs by LibraryStore.subscriptions.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()
    val downloads by com.streamo.app.download.Downloads.entries.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) {
        nav.openPlaylist(LibraryStore.createPlaylist(it)); creating = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Library", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = nav.openSettings) { Icon(Icons.Default.Settings, "Settings") }
            }
        }
        item {
            val active = downloads.count { it.status == com.streamo.app.download.DownloadEntry.Status.RUNNING || it.status == com.streamo.app.download.DownloadEntry.Status.QUEUED }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = nav.openDownloads).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.DownloadForOffline, null, tint = Ember)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Downloads", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (active > 0) "$active downloading…" else "${downloads.count { it.status == com.streamo.app.download.DownloadEntry.Status.DONE }} saved on this phone",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Playlists", Modifier.weight(1f))
                TextButton(onClick = { creating = true }) { Text("New") }
            }
        }
        if (playlists.isEmpty()) item {
            Text(
                "Make one with New, or tap Save while watching a video.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(playlists, key = { "p" + it.id }) { p -> LocalPlaylistRow(p) { nav.openPlaylist(p.id) } }
        if (subs.isNotEmpty()) {
            item { SectionTitle("Subscriptions (${subs.size})") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(subs, key = { it.url }) { ch -> ChannelBubble(ch) { nav.openChannel(ch.url) } }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("History", Modifier.weight(1f))
                if (history.isNotEmpty()) TextButton(onClick = LibraryStore::clearHistory) { Text("Clear") }
            }
        }
        if (history.isEmpty()) item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("Videos you watch will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(history, key = { "h" + it.url }) { v -> VideoRow(v) { nav.openVideo(v) } }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
}

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(nav: Nav, contentPadding: PaddingValues) {
    val settings by LibraryStore.settings.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
        }
        item { Platform.ui.AppUpdates() }
        item {
            ToggleRow("Play in background", "Keep the sound playing when you leave the app or turn the screen off.", settings.backgroundPlay) { on ->
                LibraryStore.updateSettings { it.copy(backgroundPlay = on) }
            }
            ToggleRow("Skip sponsors", "Automatically jump over sponsor messages and \"please subscribe\" reminders (SponsorBlock).", settings.sponsorBlock) { on ->
                LibraryStore.updateSettings { it.copy(sponsorBlock = on) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Video quality")
            Text(
                "Highest quality to start with. Lower saves mobile data.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(listOf(2160, 1440, 1080, 720, 480, 360)) { h ->
            Row(
                Modifier.fillMaxWidth().clickable { LibraryStore.updateSettings { it.copy(maxHeight = h) } }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.maxHeight == h, onClick = { LibraryStore.updateSettings { it.copy(maxHeight = h) } })
                Text("${h}p" + when (h) { 2160 -> " (4K)"; 1080 -> " (Full HD)"; 720 -> " (HD)"; else -> "" })
            }
        }
        item {
            Spacer(Modifier.size(24.dp))
            Text(
                "Streamo • no ads, no account, nothing tracked.\nVideos come straight from YouTube.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
