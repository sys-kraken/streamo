package com.streamo.app.data

import com.streamo.app.Platform

/**
 * Everything the screens look up on YouTube. Android uses NewPipeExtractor (NewPipeRepo);
 * iOS gets its own implementation later.
 */
interface VideoSource {
    /** Start looking up a video in the background; cheap to call repeatedly. */
    fun prefetch(url: String)
    suspend fun details(url: String, maxHeight: Int): VideoDetails
    suspend fun search(query: String): SearchPage
    suspend fun searchMore(query: String, next: Any): SearchPage
    suspend fun suggestions(query: String): List<String>
    /** The Home tabs besides "For you" (Music, Gaming, ...), by their YouTube ids. */
    fun exploreLists(): List<String>
    suspend fun forYou(history: List<VideoItem>): List<VideoItem>
    suspend fun explore(kioskId: String): List<VideoItem>
    suspend fun subscriptionFeed(channels: List<ChannelRef>): List<VideoItem>
    suspend fun downloadOptions(url: String): List<DownloadOption>
    suspend fun playlist(url: String): PlaylistPage
    suspend fun playlistMore(url: String, next: Any): Pair<List<VideoItem>, Any?>
    suspend fun channel(url: String): ChannelPage
    /** A small picture of the channel, or null if it has none. */
    suspend fun channelAvatar(url: String): String?
}

/** All YouTube lookups made by the screens go through here. */
val YouTubeRepo: VideoSource get() = Platform.videos
