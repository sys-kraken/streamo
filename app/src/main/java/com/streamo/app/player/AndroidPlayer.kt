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
import kotlinx.coroutines.launch

/** Android's [PlayerController]: the app's handle on [PlaybackService]'s player. */
object AndroidPlayer : PlayerController {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var controller: MediaController? = null

    /** Set by [close]: the player's last update about the old video must not reopen the mini player. */
    private var closed = false
    private var pending: (MediaController.() -> Unit)? = null
    private val scope = kotlinx.coroutines.MainScope()

    /** Audio-only mode (kept from video to video) and whether the app is on screen. */
    private var audioOnlyOn = false
    private var screenVisible = true

    private var sleepDeadline: Long? = null
    private var sleepAtVideoEnd = false
    private var sleepJob: kotlinx.coroutines.Job? = null

    /** Carries the player-wide choices (audio only, sleep timer) into a fresh [NowPlaying]. */
    // The player's own fields have different names from NowPlaying's: inside this extension a bare
    // `audioOnly` would mean the NowPlaying's (old) value and the copy would change nothing.
    private fun NowPlaying.withModes() = copy(audioOnly = audioOnlyOn, sleepAtMs = sleepDeadline, sleepAtEnd = sleepAtVideoEnd)

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
        _nowPlaying.value = NowPlaying(video).withModes()
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
        _nowPlaying.value = NowPlaying(video).withModes()
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
        _captionText.value = ""
        setSleepTimer(null)
        PlayQueue.clear()
        _nowPlaying.value = null
        withController {
            stop()
            clearMediaItems()
        }
    }

    override fun setVideoEnabled(enabled: Boolean) {
        screenVisible = enabled
        applyVideoTrack()
    }

    override fun setAudioOnly(on: Boolean) {
        if (audioOnlyOn == on) return
        audioOnlyOn = on
        applyVideoTrack()
        // The picture track restarts from the next keyframe; a seek to the same spot makes it
        // show straight away instead of staying black until then.
        if (!on) withController { seekTo(currentPosition) }
        _nowPlaying.value = _nowPlaying.value?.withModes()
    }

    /** The picture is downloaded only while the app is on screen and audio-only is off. */
    private fun applyVideoTrack() = withController {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !screenVisible || audioOnlyOn)
            .build()
    }

    override fun seekTo(positionMs: Long) = withController { seekTo(positionMs) }

    override fun setSleepTimer(minutes: Int?, endOfVideo: Boolean) {
        sleepJob?.cancel()
        sleepDeadline = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        sleepAtVideoEnd = endOfVideo && minutes == null
        _nowPlaying.value = _nowPlaying.value?.withModes()
        if (sleepDeadline == null && !sleepAtVideoEnd) return
        sleepJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val c = controller ?: continue
                val due = sleepDeadline?.let { System.currentTimeMillis() >= it - 8_000 } ?: false
                val atEnd = sleepAtVideoEnd && c.duration > 0 && c.duration - c.currentPosition < 1_500
                if (due || atEnd) break
            }
            // Fade out gently over the last few seconds instead of cutting off.
            controller?.let { c ->
                val start = c.volume
                if (sleepDeadline != null) for (step in 1..16) {
                    c.volume = start * (1f - step / 16f)
                    kotlinx.coroutines.delay(500)
                }
                c.pause()
                c.volume = start
            }
            sleepDeadline = null
            sleepAtVideoEnd = false
            _nowPlaying.value = _nowPlaying.value?.withModes()
        }
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

        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            _captionText.value = cleanCaptionLines(cueGroup.cues.mapNotNull { it.text?.toString() })
        }
    }

    private val _captionText = MutableStateFlow("")
    override val captionText: StateFlow<String> = _captionText.asStateFlow()

    override fun positionMs(): Long = controller?.currentPosition ?: 0
    override fun durationMs(): Long = controller?.duration?.takeIf { it > 0 } ?: 0
    override fun bufferedMs(): Long = controller?.bufferedPosition ?: 0
    override fun next() = withController { seekToNext() }
    override fun previous() = withController { seekToPrevious() }

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
                _nowPlaying.value = (current ?: NowPlaying(resolved).withModes()).copy(video = resolved)
            // The player moved on by itself (Next button, or the video ended and up next started).
            resolved != null && p.currentMediaItem?.mediaId == resolved.url ->
                _nowPlaying.value = NowPlaying(resolved).withModes()
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
            captions = textGroups(p).map { g -> g.getTrackFormat(0).let { it.label ?: it.language ?: "Captions" } },
            captionIndex = textGroups(p).indexOfFirst { it.isSelected }.takeIf { it >= 0 },
        )
    }

    private fun textGroups(p: Player) = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }

    override fun setCaption(index: Int?) = withController {
        val groups = textGroups(this)
        val builder = trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val group = index?.let { groups.getOrNull(it) }
        trackSelectionParameters = if (group == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
        }
    }

    /**
     * The CC button: captions off if they're on; otherwise on, in the phone's language when there
     * are some (the uploader's before YouTube's automatic ones), else the first track.
     */
    override fun toggleCaptions() {
        val now = _nowPlaying.value ?: return
        if (now.captionIndex != null) return setCaption(null)
        if (now.captions.isEmpty()) return
        val c = controller ?: return
        val lang = java.util.Locale.getDefault().language
        val formats = textGroups(c).map { it.getTrackFormat(0) }
        fun matches(f: androidx.media3.common.Format) = f.language?.startsWith(lang) == true
        val auto = { f: androidx.media3.common.Format -> f.label.orEmpty().contains("auto-", ignoreCase = true) }
        val pick = formats.indexOfFirst { matches(it) && !auto(it) }.takeIf { it >= 0 }
            ?: formats.indexOfFirst { matches(it) }.takeIf { it >= 0 }
            ?: 0
        setCaption(pick)
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
