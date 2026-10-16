package com.streamo.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Settings(
    /** Highest video quality to pick automatically. */
    val maxHeight: Int = 1080,
    /** Skip sponsor messages and "please subscribe" bits using SponsorBlock. */
    val sponsorBlock: Boolean = true,
    /** Keep playing sound when the app is closed or the screen turns off. */
    val backgroundPlay: Boolean = true,
)

/** Everything saved on the phone: subscriptions, watch history and settings. No account needed. */
object LibraryStore {
    private const val MAX_HISTORY = 300
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences

    private val _subscriptions = MutableStateFlow<List<ChannelRef>>(emptyList())
    val subscriptions: StateFlow<List<ChannelRef>> = _subscriptions.asStateFlow()

    private val _history = MutableStateFlow<List<VideoItem>>(emptyList())
    val history: StateFlow<List<VideoItem>> = _history.asStateFlow()

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        _subscriptions.value = read("subscriptions") ?: emptyList()
        _history.value = read("history") ?: emptyList()
        _settings.value = read("settings") ?: Settings()
        _playlists.value = read("playlists") ?: emptyList()
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
        _history.update { list -> (listOf(video) + list.filterNot { it.url == video.url }).take(MAX_HISTORY) }
        write("history", _history.value)
    }

    fun clearHistory() {
        _history.value = emptyList()
        write("history", _history.value)
    }

    // ---------- Playlists ----------

    fun createPlaylist(name: String, videos: List<VideoItem> = emptyList()): String {
        val id = java.util.UUID.randomUUID().toString()
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
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    /** One background thread does all saving, in order, so the screen never waits on it. */
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor()

    private inline fun <reified T> write(key: String, value: T) {
        saver.execute { prefs.edit().putString(key, json.encodeToString(value)).apply() }
    }
}
