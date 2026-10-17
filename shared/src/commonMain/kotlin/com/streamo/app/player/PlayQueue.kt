package com.streamo.app.player

import com.streamo.app.data.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The playlist being played, if any. The player only ever holds the current video and the
 * next one; when a playlist is active, "next" comes from here instead of YouTube's suggestions.
 * Shared by the app and the playback service (same process).
 */
object PlayQueue {
    data class Queue(val title: String, val videos: List<VideoItem>, val index: Int) {
        val current: VideoItem? get() = videos.getOrNull(index)
    }

    private val _queue = MutableStateFlow<Queue?>(null)
    val queue: StateFlow<Queue?> = _queue.asStateFlow()

    fun start(title: String, videos: List<VideoItem>, index: Int) {
        _queue.value = if (videos.isEmpty()) null else Queue(title, videos, index.coerceIn(0, videos.lastIndex))
    }

    fun clear() {
        _queue.value = null
    }

    /** Called when the player moves to [url]; keeps the position in sync (Next, Previous, autoplay). */
    fun moveTo(url: String) {
        val q = _queue.value ?: return
        val i = q.videos.indexOfFirst { it.url == url }
        if (i >= 0) _queue.value = q.copy(index = i)
    }

    /** The video after [url] in the playlist, or null when not in a playlist or at its end. */
    fun nextAfter(url: String): VideoItem? {
        val q = _queue.value ?: return null
        val i = q.videos.indexOfFirst { it.url == url }
        return if (i >= 0) q.videos.getOrNull(i + 1) else null
    }

    fun contains(url: String) = _queue.value?.videos?.any { it.url == url } == true
}
