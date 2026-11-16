package com.streamo.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Where the library is saved: SharedPreferences on Android, NSUserDefaults on iOS. */
interface KeyValueStorage {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

@Serializable
data class Settings(
    /** Highest video quality to pick automatically. */
    val maxHeight: Int = 1080,
    /** Skip sponsor messages and "please subscribe" bits using SponsorBlock. */
    val sponsorBlock: Boolean = true,
    /** Keep playing sound when the app is closed or the screen turns off. */
    val backgroundPlay: Boolean = true,
    /** "system", "dark" or "light". */
    val theme: String = "system",
    /** While on, watched videos aren't added to History (like YouTube's "Pause watch history"). */
    val historyPaused: Boolean = false,
    /** While on, searches aren't remembered. */
    val searchHistoryPaused: Boolean = false,
    /** Start videos where you left off. */
    val resumePlayback: Boolean = true,
    /** How much of Home is new channels and topics: 0 none, 1 some, 2 more. */
    val discovery: Int = 1,
    /** Dark theme flavour: "black" (pure black, best on AMOLED screens) or "dim" (YouTube's dark grey). */
    val darkStyle: String = "black",
    /** "best": highest-quality sound YouTube offers (Opus ~160 kbps); "saver": smallest, to save data. */
    val audioQuality: String = "best",
    val eqEnabled: Boolean = false,
    /** A preset name from [com.streamo.app.player.EqPresets], or "Custom" to use [eqCustom]. */
    val eqPreset: String = "Flat",
    /** Custom equalizer gains in decibels, one per band of [com.streamo.app.player.EqPresets.FREQUENCIES]. */
    val eqCustom: List<Float> = emptyList(),
    /** Keeps loud and quiet videos at a similar volume. */
    val volumeLeveling: Boolean = false,
    /** SponsorBlock: what to do per category, "skip", "ask" or "off". Missing = the category's default. */
    val sponsorActions: Map<String, String> = emptyMap(),
    /** Double-tap to skip, hold for 2x, swipe for brightness and volume (in fullscreen). */
    val gestures: Boolean = true,
    /** Show captions automatically when there are some in the phone's language. */
    val captions: Boolean = false,
    /** "small", "normal" or "large". */
    val captionSize: String = "normal",
    /** Offer YouTube's automatic translation of captions into the phone's language. */
    val captionTranslate: Boolean = true,
)

/** How far into a video you got, and when you last watched it. */
@Serializable
data class WatchState(
    val watchedAtMs: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** How many times you've opened it; watching again is a strong sign you like it. */
    val plays: Int = 1,
) {
    /** 0..1, or null when the length isn't known. */
    val fraction: Float?
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
}

/** Everything saved on the phone: subscriptions, watch history and settings. No account needed. */
object LibraryStore {
    private const val MAX_HISTORY = 300
    private const val MAX_SEARCHES = 50
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var storage: KeyValueStorage

    private val _subscriptions = MutableStateFlow<List<ChannelRef>>(emptyList())
    val subscriptions: StateFlow<List<ChannelRef>> = _subscriptions.asStateFlow()

    private val _history = MutableStateFlow<List<VideoItem>>(emptyList())
    val history: StateFlow<List<VideoItem>> = _history.asStateFlow()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    /** Watch time and progress per video link. Saved apart from "history" so older saves stay readable. */
    private val _watch = MutableStateFlow<Map<String, WatchState>>(emptyMap())
    val watch: StateFlow<Map<String, WatchState>> = _watch.asStateFlow()

    private val _searches = MutableStateFlow<List<String>>(emptyList())
    val searches: StateFlow<List<String>> = _searches.asStateFlow()

    /** Videos and channels marked "Not interested" / "Don't recommend channel". */
    private val _hidden = MutableStateFlow(Hidden())
    val hidden: StateFlow<Hidden> = _hidden.asStateFlow()

    @Serializable
    data class Hidden(
        val videos: Set<String> = emptySet(),
        val channels: Set<String> = emptySet(),
        /** Words removed under "Your interests"; videos about them aren't recommended. */
        val topics: Set<String> = emptySet(),
    )

    /** Videos you liked in Streamo (kept on the phone, never sent to YouTube). */
    private val _liked = MutableStateFlow<List<VideoItem>>(emptyList())
    val liked: StateFlow<List<VideoItem>> = _liked.asStateFlow()

    /** How often each video was shown on Home. Shown a lot but never opened = probably not for you. */
    private val _impressions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val impressions: StateFlow<Map<String, Int>> = _impressions.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun init(storage: KeyValueStorage) {
        this.storage = storage
        _subscriptions.value = read("subscriptions") ?: emptyList()
        _history.value = read("history") ?: emptyList()
        _settings.value = read("settings") ?: Settings()
        _playlists.value = read("playlists") ?: emptyList()
        _watch.value = read("watch") ?: emptyMap()
        _searches.value = read("searches") ?: emptyList()
        _hidden.value = read("hidden") ?: Hidden()
        _liked.value = read("liked") ?: emptyList()
        _impressions.value = read("impressions") ?: emptyMap()
    }

    fun isSubscribed(url: String) = _subscriptions.value.any { it.url == url }

    fun toggleSubscription(channel: ChannelRef) {
        _subscriptions.update { list ->
            if (list.any { it.url == channel.url }) list.filterNot { it.url == channel.url }
            else (list + channel).sortedBy { it.name.lowercase() }
        }
        write("subscriptions", _subscriptions.value)
    }

    fun addToHistory(video: VideoItem) {
        if (_settings.value.historyPaused) return
        _history.update { list -> (listOf(video) + list.filterNot { it.url == video.url }).take(MAX_HISTORY) }
        write("history", _history.value)
        val now = com.streamo.app.nowMillis()
        _watch.update { map ->
            val kept = map.filterKeys { k -> k == video.url || _history.value.any { it.url == k } }
            val old = kept[video.url]
            kept + (video.url to (old?.copy(watchedAtMs = now, plays = old.plays + 1) ?: WatchState(now, 0, video.durationSec * 1000)))
        }
        write("watch", _watch.value)
    }

    fun removeFromHistory(url: String) {
        _history.update { list -> list.filterNot { it.url == url } }
        _watch.update { it - url }
        write("history", _history.value)
        write("watch", _watch.value)
    }

    fun clearHistory() {
        _history.value = emptyList()
        _watch.value = emptyMap()
        write("history", _history.value)
        write("watch", _watch.value)
    }

    private var lastPositionSave = 0L

    /**
     * Remembers how far into [url] playback got. Called often by the player; saved to the phone
     * at most every 10 s unless [force] (pause, video change).
     */
    fun savePosition(url: String, positionMs: Long, durationMs: Long, force: Boolean = false) {
        if (_settings.value.historyPaused || positionMs < 0) return
        val old = _watch.value[url] ?: return // only videos in History
        _watch.update { it + (url to old.copy(positionMs = positionMs, durationMs = durationMs.takeIf { d -> d > 0 } ?: old.durationMs)) }
        val now = com.streamo.app.nowMillis()
        if (force || now - lastPositionSave > 10_000) {
            lastPositionSave = now
            write("watch", _watch.value)
        }
    }

    /** Where to start [url]: where you left off, unless near the start, near the end, or turned off. */
    fun resumePositionMs(url: String): Long {
        if (!_settings.value.resumePlayback) return 0
        val w = _watch.value[url] ?: return 0
        if (w.positionMs < 10_000) return 0
        if (w.durationMs > 0 && w.positionMs > w.durationMs - 15_000) return 0
        return w.positionMs
    }

    // ---------- Search history ----------

    fun addSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty() || _settings.value.searchHistoryPaused) return
        _searches.update { list -> (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_SEARCHES) }
        write("searches", _searches.value)
    }

    fun removeSearch(query: String) {
        _searches.update { list -> list.filterNot { it == query } }
        write("searches", _searches.value)
    }

    fun clearSearches() {
        _searches.value = emptyList()
        write("searches", _searches.value)
    }

    // ---------- Not interested ----------

    fun hideVideo(url: String) {
        _hidden.update { it.copy(videos = (it.videos + url).toList().takeLast(500).toSet()) }
        write("hidden", _hidden.value)
    }

    fun hideChannel(url: String) {
        _hidden.update { it.copy(channels = it.channels + url) }
        write("hidden", _hidden.value)
    }

    fun hideTopic(word: String) {
        _hidden.update { it.copy(topics = it.topics + word.lowercase()) }
        write("hidden", _hidden.value)
    }

    fun unhideTopic(word: String) {
        _hidden.update { it.copy(topics = it.topics - word) }
        write("hidden", _hidden.value)
    }

    fun unhideChannel(url: String) {
        _hidden.update { it.copy(channels = it.channels - url) }
        write("hidden", _hidden.value)
    }

    fun clearHidden() {
        _hidden.value = Hidden()
        write("hidden", _hidden.value)
    }

    // ---------- Likes ----------

    fun isLiked(url: String) = _liked.value.any { it.url == url }

    fun toggleLike(video: VideoItem) {
        _liked.update { list ->
            if (list.any { it.url == video.url }) list.filterNot { it.url == video.url } else listOf(video) + list
        }
        write("liked", _liked.value)
    }

    // ---------- Impressions ----------

    private var lastImpressionSave = 0L

    /** Notes that these videos were shown on Home (once per app session each, decided by the caller). */
    fun recordImpressions(urls: Collection<String>) {
        if (urls.isEmpty() || _settings.value.historyPaused) return
        _impressions.update { map ->
            val next = LinkedHashMap(map)
            urls.forEach { u -> next[u] = (next.remove(u) ?: 0) + 1 } // re-insert: most recent last
            // Keep the 800 most recently shown.
            if (next.size > 800) next.keys.take(next.size - 800).toList().forEach(next::remove)
            next
        }
        val now = com.streamo.app.nowMillis()
        if (now - lastImpressionSave > 15_000) {
            lastImpressionSave = now
            write("impressions", _impressions.value)
        }
    }

    /** Forget what Streamo learned from what you were shown and what you hid. History and likes stay. */
    fun resetRecommendations() {
        _impressions.value = emptyMap()
        _hidden.value = Hidden()
        write("impressions", _impressions.value)
        write("hidden", _hidden.value)
    }

    // ---------- Playlists ----------

    @OptIn(ExperimentalUuidApi::class)
    fun createPlaylist(name: String, videos: List<VideoItem> = emptyList()): String {
        val id = Uuid.random().toString()
        editPlaylists { it + LocalPlaylist(id, name.trim().ifEmpty { "My playlist" }, videos.distinctBy { v -> v.url }) }
        return id
    }

    fun renamePlaylist(id: String, name: String) =
        editPlaylists { list -> list.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it } }

    fun deletePlaylist(id: String) = editPlaylists { list -> list.filterNot { it.id == id } }

    fun toggleInPlaylist(id: String, video: VideoItem) = editPlaylists { list ->
        list.map { p ->
            if (p.id != id) p
            else if (p.videos.any { it.url == video.url }) p.copy(videos = p.videos.filterNot { it.url == video.url })
            else p.copy(videos = p.videos + video)
        }
    }

    fun removeFromPlaylist(id: String, url: String) = editPlaylists { list ->
        list.map { p -> if (p.id == id) p.copy(videos = p.videos.filterNot { it.url == url }) else p }
    }

    private fun editPlaylists(change: (List<LocalPlaylist>) -> List<LocalPlaylist>) {
        _playlists.update(change)
        write("playlists", _playlists.value)
    }

    fun updateSettings(change: (Settings) -> Settings) {
        _settings.update(change)
        write("settings", _settings.value)
    }

    private inline fun <reified T> read(key: String): T? =
        storage.get(key)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    /** All saving runs in the background one at a time, in order, so the screen never waits on it. */
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private inline fun <reified T> write(key: String, value: T) {
        saver.launch { storage.put(key, json.encodeToString(value)) }
    }
}
