package com.streamo.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.streamo.app.data.NewPipeRepo
import com.streamo.app.data.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android's [PlayerController]: the app's handle on [PlaybackService]'s player. */
object AndroidPlayer : PlayerController {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var controller: MediaController? = null

    /** Set by [close]: the player's last update about the old video must not reopen the mini player. */
    private var closed = false
    private var pending: (MediaController.() -> Unit)? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** The player, once connected; the video surface attaches to this. */
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    fun connect(context: Context) {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            _player.value = c
            syncFromController(c)
            pending?.invoke(c)
            pending = null
        }, MoreExecutors.directExecutor())
    }

    override fun play(video: VideoItem, fromQueue: Boolean) {
        closed = false
        if (!fromQueue) PlayQueue.clear() else PlayQueue.moveTo(video.url)
        NewPipeRepo.prefetch(video.url)
        _nowPlaying.value = NowPlaying(video)
        withController {
            stop() // silence the previous video right away instead of while the new one loads
            setMediaItem(requestItem(video, maxHeight = 0))
            prepare()
            play()
        }
    }

    override fun playLocal(video: VideoItem, fileUri: String) {
        closed = false
        PlayQueue.clear()
        _nowPlaying.value = NowPlaying(video)
        withController {
            stop()
            setMediaItem(
                requestItem(video, maxHeight = 0).buildUpon()
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder()
                            .setMediaUri(Uri.parse(fileUri))
                            .setExtras(Bundle().apply { putString(PlaybackService.EXTRA_VIDEO, json.encodeToString(VideoItem.serializer(), video)) })
                            .build()
                    ).build()
            )
            prepare()
            play()
        }
    }

    override fun playQueue(title: String, videos: List<VideoItem>, index: Int) {
        if (videos.isEmpty()) return
        PlayQueue.start(title, videos, index)
        play(videos[index.coerceIn(0, videos.lastIndex)], fromQueue = true)
        videos.getOrNull(index + 1)?.let { NewPipeRepo.prefetch(it.url) }
    }

    override fun setQuality(height: Int) {
        val now = _nowPlaying.value ?: return
        withController {
            val position = currentPosition
            setMediaItem(requestItem(now.video, height), position)
            prepare()
            play()
        }
    }

    override fun setSpeed(speed: Float) = withController { setPlaybackSpeed(speed) }

    override fun togglePlay() = withController { if (isPlaying) pause() else play() }

    override fun close() {
        closed = true
        PlayQueue.clear()
        _nowPlaying.value = null
        withController {
            stop()
            clearMediaItems()
        }
    }

    override fun setVideoEnabled(enabled: Boolean) = withController {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    override fun pause() = withController { pause() }

    private fun requestItem(video: VideoItem, maxHeight: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(video.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(video.title)
                    .setArtist(video.channelName)
                    .setArtworkUri(video.thumbnail?.let(Uri::parse))
                    .build()
            )
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setExtras(Bundle().apply { putInt(PlaybackService.EXTRA_MAX_HEIGHT, maxHeight) })
                    .build()
            )
            .build()

    private fun withController(block: MediaController.() -> Unit) {
        val c = controller
        if (c != null) c.block() else pending = block
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController(player)
        }
    }

    private fun syncFromController(p: Player) {
        if (closed) return
        val current = _nowPlaying.value
        val extras = p.mediaMetadata.extras
        // The service fills in full video info once it has looked the video up. This also restores
        // state when the app is reopened while the service kept playing in the background.
        val resolved = extras?.getString(PlaybackService.EXTRA_VIDEO)?.let {
            runCatching { json.decodeFromString<VideoItem>(it) }.getOrNull()
        }
        when {
            resolved != null && (current == null || current.video.url == resolved.url) ->
                _nowPlaying.value = (current ?: NowPlaying(resolved)).copy(video = resolved)
            // The player moved on by itself (Next button, or the video ended and up next started).
            resolved != null && p.currentMediaItem?.mediaId == resolved.url ->
                _nowPlaying.value = NowPlaying(resolved)
            current == null -> return
        }
        val now = _nowPlaying.value ?: return
        val sameItem = p.currentMediaItem?.mediaId == now.video.url
        _nowPlaying.value = now.copy(
            started = now.started || (sameItem && p.isPlaying),
            isPlaying = sameItem && p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING ||
                (p.playbackState == Player.STATE_IDLE && p.playerError == null),
            height = if (sameItem) extras?.getInt(PlaybackService.EXTRA_HEIGHT) ?: 0 else 0,
            heights = if (sameItem) extras?.getIntArray(PlaybackService.EXTRA_HEIGHTS)?.toList().orEmpty() else emptyList(),
            error = p.playerError?.let {
                val reason = extras?.getString(PlaybackService.EXTRA_ERROR)
                if (reason != null) "$reason Tap to retry." else "Couldn't play this video. Tap to retry."
            },
            speed = p.playbackParameters.speed,
        )
    }

    override fun progress(): Float? {
        val c = controller ?: return null
        return if (c.duration > 0) c.currentPosition.toFloat() / c.duration else null
    }

    override fun retry() = withController {
        val now = _nowPlaying.value ?: return@withController
        NewPipeRepo.invalidate(now.video.url)
        setMediaItem(requestItem(now.video, 0), currentPosition)
        prepare()
        play()
    }
}
