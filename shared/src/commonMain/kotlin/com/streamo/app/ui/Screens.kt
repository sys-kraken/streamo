package com.streamo.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamo.app.Platform
import com.streamo.app.data.ChannelRef
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.SearchResult
import com.streamo.app.data.VideoItem
import com.streamo.app.data.YouTubeRepo
import com.streamo.app.download.DownloadEntry
import com.streamo.app.download.Downloads
import com.streamo.app.nowMillis
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
    val openHistory: () -> Unit,
    val openLiked: () -> Unit,
    val openInterests: () -> Unit,
    val openEqualizer: () -> Unit,
    val back: () -> Unit,
)

// ---------------- Home ----------------

private val exploreNames = mapOf(
    FOR_YOU to "All",
    FEED to "Subscriptions",
    "trending_music" to "Music",
    "trending_gaming" to "Gaming",
    "trending_movies_and_shows" to "Movies & TV",
    "live" to "Live",
)

/** The top bar used by the main tabs: logo on the left, search (and more) on the right. */
@Composable
private fun TopBar(nav: Nav, title: @Composable () -> Unit = { BrandTitle() }) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { title() }
        IconButton(onClick = nav.openSearch) { Icon(Icons.Default.Search, "Search") }
        IconButton(onClick = nav.openSettings) { Icon(Icons.Default.Settings, "Settings") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav, contentPadding: PaddingValues) {
    val subs by LibraryStore.subscriptions.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val searches by LibraryStore.searches.collectAsState()
    val signals = history.isNotEmpty() || searches.isNotEmpty() || subs.isNotEmpty()
    val tabs = remember(subs.isEmpty(), signals) {
        listOfNotNull(
            FOR_YOU.takeIf { signals },
            FEED.takeIf { subs.isNotEmpty() },
        ) + YouTubeRepo.exploreLists()
    }
    var selected by rememberSaveable { mutableStateOf(tabs.firstOrNull() ?: "") }
    if (selected !in tabs) selected = tabs.firstOrNull() ?: ""

    val loader = rememberLoad(selected to subs.size, cached = Feeds.peek(homeKey(selected, subs))) { refresh ->
        try {
            homeFeed(selected, subs, refresh).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(800) // a brief network hiccup often clears up by itself
            homeFeed(selected, subs, refresh = true).await()
        }
    }
    // Once the tab on screen has loaded, quietly load the others so switching is instant.
    LaunchedEffect(tabs, subs.size, loader.state is Load.Done) {
        if (loader.state is Load.Done) tabs.forEach { homeFeed(it, subs) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        TopBar(nav)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { tab -> Chip(exploreNames[tab] ?: tab, tab == selected) { selected = tab } }
        }
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = loader.reload,
            modifier = Modifier.fillMaxSize(),
        ) {
            key(selected) {
                val hidden by LibraryStore.hidden.collectAsState()
                val watch by LibraryStore.watch.collectAsState()
                // The feed is kept for a while; a video watched since then drops out of "All" right away.
                val since = remember(selected) { nowMillis() - 21L * 24 * 60 * 60 * 1000 }
                VideoFeed(
                    loader, nav, contentPadding,
                    state = listState,
                    filter = { v ->
                        v.url !in hidden.videos && v.channelUrl !in hidden.channels &&
                            !(selected == FOR_YOU && (watch[v.url]?.watchedAtMs ?: 0L) > since)
                    },
                    recommendable = true,
                    reasons = if (selected == FOR_YOU) HomeFeed.reasons else emptyMap(),
                    onShown = if (selected == FOR_YOU) HomeFeed::shown else null,
                    empty = {
                        EmptyMessage("Nothing here yet", "Watch, search or subscribe, and Streamo will recommend videos for you.")
                    },
                )
            }
        }
    }
}

internal const val FEED = "_feed"
internal const val FOR_YOU = "_for_you"

internal fun homeKey(tab: String, subs: List<ChannelRef>) =
    if (tab == FEED) "feed:${subs.size}" else "home:$tab"

private fun homeFeed(tab: String, subs: List<ChannelRef>, refresh: Boolean = false) =
    Feeds.load(homeKey(tab, subs), refresh) {
        when (tab) {
            FOR_YOU -> HomeFeed.forYou(refresh)
            FEED -> YouTubeRepo.subscriptionFeed(subs)
            else -> YouTubeRepo.explore(tab)
        }
    }

@Composable
private fun VideoFeed(
    loader: Loader<List<VideoItem>>,
    nav: Nav,
    contentPadding: PaddingValues,
    state: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
    filter: (VideoItem) -> Boolean = { true },
    recommendable: Boolean = false,
    reasons: Map<String, String> = emptyMap(),
    onShown: ((List<String>) -> Unit)? = null,
    empty: @Composable () -> Unit,
) {
    if (onShown != null) {
        // Count a video as shown once its card is on screen (for "shown often, never opened").
        LaunchedEffect(state) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
                .collect { keys -> onShown(keys.filter { it.startsWith("http") }) }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = contentPadding) {
        header?.let { item { it() } }
        when (val s = loader.state) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { ErrorMessage(s.message, loader.reload) }
            is Load.Done -> {
                val videos = s.value.filter(filter)
                if (videos.isEmpty()) item { empty() }
                items(videos, key = { it.url }) { video ->
                    VideoCard(
                        video, onClick = { nav.openVideo(video) },
                        onChannel = video.channelUrl?.let { url -> { nav.openChannel(url) } },
                        recommendable = recommendable,
                        reason = reasons[video.url],
                    )
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
        homeFeed(FEED, subs, refresh).await()
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(nav)
        if (subs.isEmpty()) {
            EmptyMessage(
                "No subscriptions yet",
                "Open a channel and tap Subscribe. Their new videos will show up here — no account needed.",
            )
            return@Column
        }
        @OptIn(ExperimentalMaterial3Api::class)
        PullToRefreshBox(isRefreshing = false, onRefresh = loader.reload, modifier = Modifier.fillMaxSize()) {
            VideoFeed(loader, nav, contentPadding, header = {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(subs, key = { it.url }) { ch -> ChannelBubble(ch) { nav.openChannel(ch.url) } }
                }
                HorizontalDivider(Modifier.padding(bottom = 8.dp))
            }, empty = { EmptyMessage("No new videos", "Pull down to refresh.") })
        }
    }
}

@Composable
private fun ChannelBubble(ch: ChannelRef, onClick: () -> Unit) {
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(ch.avatar, ch.name, 56.dp)
        Spacer(Modifier.height(6.dp))
        Text(ch.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Search ----------------

private enum class SearchFilter(val label: String) { ALL("All"), VIDEOS("Videos"), CHANNELS("Channels"), PLAYLISTS("Playlists") }

@Composable
fun SearchScreen(nav: Nav, contentPadding: PaddingValues) {
    // The text together with the cursor position, so text filled in for you (a suggestion,
    // a search) puts the cursor at the end instead of wherever it was before.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val query = field.text
    fun setQuery(text: String) { field = TextFieldValue(text, TextRange(text.length)) }
    var submitted by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(true) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    val past by LibraryStore.searches.collectAsState()
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
        suggestions = runCatching { YouTubeRepo.suggestions(query) }.getOrDefault(emptyList())
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
        setQuery(text); submitted = text; suggestions = emptyList(); editing = false
        LibraryStore.addSearch(text)
        // Let go of the search box, so its cursor doesn't linger over the results or a video.
        keyboard?.hide(); focusManager.clearFocus()
        scope.launch { listState.scrollToItem(0) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            TextField(
                value = field,
                onValueChange = { field = it; editing = true },
                placeholder = { Text("Search YouTube") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submit(query.trim()) }),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { setQuery(""); editing = true; focus.requestFocus() }) {
                        Icon(Icons.Default.Close, "Clear")
                    }
                },
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.onBackground,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(focus),
            )
        }

        // While typing (or before the first search): your past searches that match, then YouTube's suggestions.
        if (editing) {
            val matchingPast = past.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }.take(if (query.isBlank()) 20 else 4)
            val others = suggestions.filterNot { s -> matchingPast.any { it.equals(s, ignoreCase = true) } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                items(matchingPast, key = { "p$it" }) { s ->
                    SuggestionRow(s, Icons.Default.History, onClick = { submit(s) }, onFill = { setQuery("$s ") },
                        onRemove = { LibraryStore.removeSearch(s) })
                }
                items(others, key = { "s$it" }) { s ->
                    SuggestionRow(s, Icons.Default.Search, onClick = { submit(s) }, onFill = { setQuery("$s ") })
                }
                if (query.isBlank() && past.isEmpty()) item {
                    EmptyMessage("Search YouTube", "Your recent searches will show up here.")
                }
            }
            return@Column
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchFilter.entries.forEach { f -> Chip(f.label, f == filter) { filter = f } }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
            when (val s = loader.state) {
                Load.Loading -> if (submitted.isNotBlank()) item { Loading() }
                is Load.Failed -> item { ErrorMessage(s.message, loader.reload) }
                is Load.Done -> {
                    val shown = results.filter { r ->
                        when (filter) {
                            SearchFilter.ALL -> true
                            SearchFilter.VIDEOS -> r is SearchResult.Video
                            SearchFilter.CHANNELS -> r is SearchResult.Channel
                            SearchFilter.PLAYLISTS -> r is SearchResult.Playlist
                        }
                    }
                    if (submitted.isNotBlank() && shown.isEmpty() && !loadingMore) {
                        item { EmptyMessage("No results", if (filter == SearchFilter.ALL) "Try different words." else "Try another filter.") }
                    }
                    items(shown.size) { i ->
                        when (val r = shown[i]) {
                            is SearchResult.Video -> VideoCard(r.video, onClick = { nav.openVideo(r.video) },
                                onChannel = r.video.channelUrl?.let { url -> { nav.openChannel(url) } })
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
private fun SuggestionRow(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onFill: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onRemove != null) RemoveButton("Remove from search history", onRemove)
        IconButton(onClick = onFill) { Icon(Icons.Default.NorthWest, "Fill in", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ChannelResult(r: SearchResult.Channel, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(140.dp), contentAlignment = Alignment.Center) { Avatar(r.channel.avatar, r.channel.name, 88.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatCount(r.subscribers, "subscribers"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val subs by LibraryStore.subscriptions.collectAsState()
            SubscribeButton(subs.any { it.url == r.channel.url }) { LibraryStore.toggleSubscription(r.channel) }
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
            Row(Modifier.padding(top = 4.dp)) {
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
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).aspectRatio(6.2f)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(page.channel.avatar, page.channel.name, 72.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(page.channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(formatCount(page.subscribers, "subscribers"), "${page.videos.size}+ videos")
                                    .filter { it.isNotBlank() }.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (page.description.isNotBlank()) {
                        Text(
                            com.streamo.app.htmlToText(page.description).trim(), Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        SubscribeButton(subs.any { it.url == page.channel.url }, Modifier.fillMaxWidth()) {
                            LibraryStore.toggleSubscription(page.channel)
                        }
                    }
                    Text("Videos", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                }
                if (page.videos.isEmpty()) item { EmptyMessage("No videos", "This channel hasn't posted videos.") }
                items(page.videos, key = { it.url }) { v -> VideoRow(v) { nav.openVideo(v) } }
            }
        }
    }
}

/** White pill on dark (black on light) when not subscribed; grey once subscribed, like YouTube. */
@Composable
fun SubscribeButton(subscribed: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        colors = if (subscribed) ButtonDefaults.buttonColors(containerColor = c.surfaceVariant, contentColor = c.onBackground)
        else ButtonDefaults.buttonColors(containerColor = c.onBackground, contentColor = c.background),
    ) { Text(if (subscribed) "Subscribed" else "Subscribe", style = MaterialTheme.typography.labelLarge) }
}

// ---------------- You (library) ----------------

@Composable
fun LibraryScreen(nav: Nav, contentPadding: PaddingValues) {
    val history by LibraryStore.history.collectAsState()
    val subs by LibraryStore.subscriptions.collectAsState()
    val playlists by LibraryStore.playlists.collectAsState()
    val downloads by Downloads.entries.collectAsState()
    val settings by LibraryStore.settings.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) {
        nav.openPlaylist(LibraryStore.createPlaylist(it)); creating = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item { TopBar(nav) { Text("You", style = MaterialTheme.typography.titleLarge) } }
        item { SectionHeader("History", if (history.isNotEmpty()) "View all" else null, nav.openHistory) }
        item {
            when {
                history.isNotEmpty() -> LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(history.take(20), key = { "h" + it.url }) { v -> HistoryTile(v) { nav.openVideo(v) } }
                }
                settings.historyPaused -> HintRow(Icons.Default.PauseCircleOutline, "Watch history is paused. Turn it on in History.", nav.openHistory)
                else -> HintRow(Icons.Default.History, "Videos you watch will appear here.", null)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("Playlists") }
                TextButton(onClick = { creating = true }) { Text("New", color = MaterialTheme.colorScheme.onBackground) }
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
        item { Spacer(Modifier.height(8.dp)); HorizontalDivider() }
        item {
            val active = downloads.count { it.status == DownloadEntry.Status.RUNNING || it.status == DownloadEntry.Status.QUEUED }
            ListRow(
                Icons.Default.DownloadForOffline, "Downloads",
                if (active > 0) "$active downloading…" else "${downloads.count { it.status == DownloadEntry.Status.DONE }} saved on this phone",
                nav.openDownloads,
            )
        }
        item { ListRow(Icons.Default.History, "History", "${history.size} videos" + if (settings.historyPaused) " · paused" else "", nav.openHistory) }
        item {
            val liked by LibraryStore.liked.collectAsState()
            ListRow(Icons.Default.ThumbUp, "Liked videos", if (liked.isEmpty()) "Tap Like on a video to save it here" else "${liked.size} videos", nav.openLiked)
        }
        item { ListRow(Icons.Default.Insights, "Your interests", "What Streamo has learned you like", nav.openInterests) }
        if (subs.isNotEmpty()) {
            item { SectionHeader("Subscriptions (${subs.size})") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(subs, key = { it.url }) { ch -> ChannelBubble(ch) { nav.openChannel(ch.url) } }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun HistoryTile(v: VideoItem, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).clip(RoundedCornerShape(8.dp)).prefetchOnTouch(v.url).clickable(onClick = onClick)) {
        Thumbnail(v, Modifier.fillMaxWidth(), corner = 8.dp)
        Spacer(Modifier.height(6.dp))
        Text(v.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(v.channelName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun ListRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HintRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------- History ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(nav: Nav, contentPadding: PaddingValues) {
    val history by LibraryStore.history.collectAsState()
    val watch by LibraryStore.watch.collectAsState()
    val settings by LibraryStore.settings.collectAsState()
    var filter by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear watch history?") },
            text = { Text("All ${history.size} videos and where you left off in them will be removed. Your recommendations will start fresh.") },
            confirmButton = {
                TextButton(onClick = { LibraryStore.clearHistory(); confirmClear = false }) {
                    Text("Clear", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onBackground) } },
        )
    }

    val now = nowMillis()
    val shown = history.filter { v ->
        filter.isBlank() || v.title.contains(filter, ignoreCase = true) || v.channelName.contains(filter, ignoreCase = true)
    }
    // Group by the day each video was last watched; older saves without a date go under "Earlier".
    val groups = shown.groupBy { v -> watch[v.url]?.watchedAtMs?.let { dayLabel(it, now) } ?: "Earlier" }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("History", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { LibraryStore.updateSettings { it.copy(historyPaused = !it.historyPaused) } }) {
                Icon(
                    if (settings.historyPaused) Icons.Default.PlayCircleOutline else Icons.Default.PauseCircleOutline,
                    if (settings.historyPaused) "Turn on watch history" else "Pause watch history",
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    DropdownMenuItem(
                        text = { Text("Clear all watch history") },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                        enabled = history.isNotEmpty(),
                        onClick = { menu = false; confirmClear = true },
                    )
                    DropdownMenuItem(
                        text = { Text(if (settings.historyPaused) "Turn on watch history" else "Pause watch history") },
                        leadingIcon = { Icon(if (settings.historyPaused) Icons.Default.PlayCircleOutline else Icons.Default.PauseCircleOutline, null) },
                        onClick = { menu = false; LibraryStore.updateSettings { it.copy(historyPaused = !it.historyPaused) } },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear search history") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        onClick = { menu = false; LibraryStore.clearSearches(); Platform.ui.toast("Search history cleared.") },
                    )
                }
            }
        }
        if (settings.historyPaused) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Watch history is paused. New videos won't be added or used for recommendations.",
                    Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { LibraryStore.updateSettings { it.copy(historyPaused = false) } }) {
                    Text("Turn on", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (history.isNotEmpty()) {
            TextField(
                value = filter, onValueChange = { filter = it },
                placeholder = { Text("Search watch history") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) { Icon(Icons.Default.Close, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.onBackground,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 48.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            if (history.isEmpty()) item {
                EmptyMessage("No watch history", if (settings.historyPaused) "Watch history is paused." else "Videos you watch will appear here.")
            } else if (shown.isEmpty()) item {
                EmptyMessage("No matches", "Nothing in your history matches \"$filter\".")
            }
            groups.forEach { (day, videos) ->
                stickyHeader(key = "d$day") {
                    Text(
                        day, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    )
                }
                items(videos, key = { "h" + it.url }) { v ->
                    VideoRow(
                        v,
                        extraActions = listOf(MenuAction(Icons.Default.DeleteOutline, "Remove from watch history") {
                            LibraryStore.removeFromHistory(v.url)
                        }),
                    ) { nav.openVideo(v) }
                }
            }
        }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(nav: Nav, contentPadding: PaddingValues) {
    val settings by LibraryStore.settings.collectAsState()
    val hidden by LibraryStore.hidden.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear watch history?") },
            text = { Text("Videos you watched and where you left off will be removed.") },
            confirmButton = {
                TextButton(onClick = { LibraryStore.clearHistory(); confirmClear = false }) {
                    Text("Clear", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onBackground) } },
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
        }
        item { Platform.ui.AppUpdates() }

        item { SettingsHeader("Appearance") }
        items(
            listOf("system" to "Use device theme", "dark" to "Dark theme", "dim" to "Dark grey theme", "light" to "Light theme"),
        ) { (value, label) ->
            ChoiceRow(label, settings.theme == value) { LibraryStore.updateSettings { it.copy(theme = value) } }
        }

        item {
            SettingsHeader("Playback")
            ToggleRow("Play in background", "Keep the sound playing when you leave the app or turn the screen off.", settings.backgroundPlay) { on ->
                LibraryStore.updateSettings { it.copy(backgroundPlay = on) }
            }
            ToggleRow("Resume where you left off", "Videos you started before continue from the same spot.", settings.resumePlayback) { on ->
                LibraryStore.updateSettings { it.copy(resumePlayback = on) }
            }
        }

        item {
            SettingsHeader("Sound")
            Text("Audio quality", Modifier.padding(start = 16.dp, top = 4.dp), style = MaterialTheme.typography.labelLarge)
        }
        items(listOf("best" to "Best (Opus, up to 160 kbps)", "saver" to "Data saver (about 50 kbps)")) { (value, label) ->
            ChoiceRow(label, settings.audioQuality == value) { LibraryStore.updateSettings { it.copy(audioQuality = value) } }
        }
        item {
            ActionRow("Equalizer", if (settings.eqEnabled) "On · ${settings.eqPreset}" else "Off", nav.openEqualizer)
            ToggleRow("Volume leveling", "Keeps loud and quiet videos at a similar volume, like Spotify's \"Normalize volume\".", settings.volumeLeveling) { on ->
                LibraryStore.updateSettings { it.copy(volumeLeveling = on) }
            }
        }

        item {
            SettingsHeader("SponsorBlock")
            ToggleRow("Use SponsorBlock", "Community-marked parts of videos (sponsors, intros, reminders) can be skipped automatically or with a button.", settings.sponsorBlock) { on ->
                LibraryStore.updateSettings { it.copy(sponsorBlock = on) }
            }
        }
        if (settings.sponsorBlock) {
            items(com.streamo.app.data.SponsorBlock.CATEGORIES) { category ->
                val action = com.streamo.app.data.SponsorBlock.action(settings.sponsorActions, category)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(com.streamo.app.data.SponsorBlock.NAMES[category] ?: category, style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val options = if (category == "poi_highlight") listOf("ask" to "Show button", "off" to "Off")
                            else listOf("skip" to "Skip", "ask" to "Ask", "off" to "Off")
                        options.forEach { (value, label) ->
                            Chip(label, action == value) {
                                LibraryStore.updateSettings { it.copy(sponsorActions = it.sponsorActions + (category to value)) }
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsHeader("Video quality")
            Text(
                "Highest quality to start with. Lower saves mobile data.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(listOf(2160, 1440, 1080, 720, 480, 360)) { h ->
            ChoiceRow("${h}p" + when (h) { 2160 -> " (4K)"; 1080 -> " (Full HD)"; 720 -> " (HD)"; else -> "" }, settings.maxHeight == h) {
                LibraryStore.updateSettings { it.copy(maxHeight = h) }
            }
        }

        item {
            SettingsHeader("History & privacy")
            ToggleRow("Pause watch history", "Videos you watch aren't saved or used for recommendations.", settings.historyPaused) { on ->
                LibraryStore.updateSettings { it.copy(historyPaused = on) }
            }
            ToggleRow("Pause search history", "Searches aren't saved or used for recommendations.", settings.searchHistoryPaused) { on ->
                LibraryStore.updateSettings { it.copy(searchHistoryPaused = on) }
            }
            ActionRow("Clear watch history", "Remove all watched videos.") { confirmClear = true }
            ActionRow("Clear search history", "Remove all past searches.") {
                LibraryStore.clearSearches(); Platform.ui.toast("Search history cleared.")
            }
        }
        item { SettingsHeader("Recommendations") }
        items(listOf(0 to "Only what I like", 1 to "Some new channels and topics", 2 to "Lots of new channels and topics")) { (value, label) ->
            ChoiceRow(label, settings.discovery == value) { LibraryStore.updateSettings { it.copy(discovery = value) } }
        }
        item {
            ActionRow("Your interests", "See and change what Streamo has learned you like.", nav.openInterests)
            val hiddenCount = hidden.videos.size + hidden.channels.size + hidden.topics.size
            ActionRow(
                "Reset recommendations",
                "Forget hidden videos, channels and topics" + (if (hiddenCount > 0) " ($hiddenCount)" else "") + ", and what you scrolled past. History and likes stay.",
            ) {
                LibraryStore.resetRecommendations(); Platform.ui.toast("Recommendations reset.")
            }
        }
        item {
            Spacer(Modifier.size(16.dp))
            Text(
                "Streamo • no ads, no account, nothing tracked.\nRecommendations are worked out on this phone from your history, searches and subscriptions.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected, onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onBackground),
        )
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ActionRow(title: String, body: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.background, checkedTrackColor = c.onBackground,
                uncheckedThumbColor = c.onSurfaceVariant, uncheckedTrackColor = c.surfaceVariant, uncheckedBorderColor = c.outline,
            ),
        )
    }
}
