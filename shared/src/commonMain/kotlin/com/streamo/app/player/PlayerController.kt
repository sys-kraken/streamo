package com.streamo.app.player

import com.streamo.app.Platform
import com.streamo.app.data.VideoItem
import kotlinx.coroutines.flow.StateFlow

/** What the screens need to know about playback. */
data class NowPlaying(
    val video: VideoItem,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val height: Int = 0,
    val heights: List<Int> = emptyList(),
    val error: String? = null,
    /** False until this video's first moment actually plays; the thumbnail is shown until then. */
    val started: Boolean = false,
    val speed: Float = 1f,
)

/** Controls playback. Android: Media3 in a background service (AndroidPlayer); iOS: later. */
interface PlayerController {
    val nowPlaying: StateFlow<NowPlaying?>

    /** Plays one video. Unless [fromQueue], any playlist in progress ends. */
    fun play(video: VideoItem, fromQueue: Boolean = false)

    /** Plays a downloaded file (works offline). */
    fun playLocal(video: VideoItem, fileUri: String)

    /** Plays a playlist from [index]; Next and autoplay then follow the playlist. */
    fun playQueue(title: String, videos: List<VideoItem>, index: Int = 0)

    /** Switch quality, continuing from the same spot. */
    fun setQuality(height: Int)
    fun setSpeed(speed: Float)
    fun togglePlay()
    fun close()

    /** With the screen off or the app hidden, stop downloading the picture and keep the sound. */
    fun setVideoEnabled(enabled: Boolean)
    fun pause()
    fun retry()

    /** How far into the current video, from 0 to 1, or null when not known yet. */
    fun progress(): Float?
}

/** The screens' handle on the player. */
val PlayerConnection: PlayerController get() = Platform.player
