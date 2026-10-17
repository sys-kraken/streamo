package com.streamo.app.data

import kotlinx.serialization.Serializable

@Serializable
data class VideoItem(
    val url: String,
    val title: String,
    val channelName: String,
    val channelUrl: String?,
    val thumbnail: String?,
    val durationSec: Long,
    val views: Long,
    val uploaded: String?,
    val uploadedEpochMs: Long? = null,
    val isLive: Boolean = false,
    val channelAvatar: String? = null,
)

@Serializable
data class ChannelRef(
    val url: String,
    val name: String,
    val avatar: String?,
)

data class VideoDetails(
    val item: VideoItem,
    val description: String,
    val likes: Long,
    val channelAvatar: String?,
    val channelSubscribers: Long,
    val related: List<VideoItem>,
    val streams: StreamChoice,
)

/** Everything the player needs to start a video, picked for the requested quality. */
data class StreamChoice(
    val videoUrl: String?,
    val audioUrl: String?,
    /** Set when the video and audio come as one file (older formats) or a live HLS playlist. */
    val combinedUrl: String?,
    val isHls: Boolean,
    val height: Int,
    val availableHeights: List<Int>,
)

data class ChannelPage(
    val channel: ChannelRef,
    val banner: String?,
    val subscribers: Long,
    val description: String,
    val videos: List<VideoItem>,
)

data class SearchPage(
    val items: List<SearchResult>,
    val next: Any?,
)

sealed interface SearchResult {
    data class Video(val video: VideoItem) : SearchResult
    data class Channel(val channel: ChannelRef, val subscribers: Long) : SearchResult
    data class Playlist(val playlist: PlaylistRef) : SearchResult
}

/** A playlist made in Streamo, saved on the phone. */
@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val videos: List<VideoItem> = emptyList(),
)

/** A YouTube playlist as shown in search results. */
data class PlaylistRef(
    val url: String,
    val name: String,
    val uploader: String,
    val count: Long,
    val thumbnail: String?,
)

data class PlaylistPage(
    val name: String,
    val uploader: String,
    val thumbnail: String?,
    val count: Long,
    val videos: List<VideoItem>,
    val next: Any?,
)
