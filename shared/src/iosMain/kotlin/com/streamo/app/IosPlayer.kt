package com.streamo.app

import com.streamo.app.data.LibraryStore
import com.streamo.app.data.SkipSegment
import com.streamo.app.data.SponsorBlock
import com.streamo.app.data.StreamChoice
import com.streamo.app.data.VideoItem
import com.streamo.app.data.YouTubeRepo
import com.streamo.app.data.youtube.InnerTubeSource
import com.streamo.app.data.youtube.YouTubeApi
import com.streamo.app.player.NowPlaying
import com.streamo.app.player.PlayQueue
import com.streamo.app.player.PlayerController
import com.streamo.app.ui.friendlyError
import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import platform.AVFAudio.*
import platform.AVFoundation.*
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.*
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.MediaPlayer.*
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * The iPhone's [PlayerController], built on Apple's AVPlayer. Like Android's PlaybackService it
 * looks up the streams, records history, skips sponsors, plays the next video when one ends,
 * and shows lock-screen controls. Sound keeps playing in the background (Info.plist allows it).
 *
 * YouTube offers HLS only for some videos; for the rest the separate picture and sound files are
 * combined into one AVComposition, since AVPlayer can't take two files directly.
 */
@OptIn(ExperimentalForeignApi::class)
object IosPlayer : PlayerController {
    private val scope = MainScope()
    private val player = AVPlayer()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** The player the video views show. Null while the app is in the background, so only the sound plays. */
    private val _attached = MutableStateFlow<AVPlayer?>(player)
    val attached: StateFlow<AVPlayer?> = _attached.asStateFlow()

    /** Writes playback events to the log; the Simulator test run turns this on. */
    var debugLog = false

    private class Loaded(val video: VideoItem, val streams: StreamChoice, val related: List<VideoItem>)

    private var current: Loaded? = null
    private var loadJob: Job? = null
    private var speed = 1f
    private var segments: List<SkipSegment> = emptyList()
    private var endObserver: Any? = null
    private val previous = ArrayDeque<VideoItem>()
    private val sponsorBlock by lazy { SponsorBlock(HttpClient()) }
    private val loader = StreamLoader().also { it.onError = { message -> log(message) } }

    init {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        AVAudioSession.sharedInstance().setActive(true, error = null)
        watchTime()
        lockScreenControls()
        followAppLifecycle()
    }

    // ---------- PlayerController ----------

    override fun play(video: VideoItem, fromQueue: Boolean) = start(video, fromQueue, rememberCurrent = true)

    /** Plays a downloaded file (works offline). */
    override fun playLocal(video: VideoItem, fileUri: String) {
        PlayQueue.clear()
        loadJob?.cancel()
        _nowPlaying.value = NowPlaying(video)
        val url = NSURL.URLWithString(fileUri) ?: NSURL.fileURLWithPath(fileUri)
        val item = AVPlayerItem.playerItemWithURL(url)
        current = Loaded(video, StreamChoice(null, null, fileUri, false, 0, emptyList()), emptyList())
        show(item, startAt = 0.0)
    }

    override fun playQueue(title: String, videos: List<VideoItem>, index: Int) {
        if (videos.isEmpty()) return
        PlayQueue.start(title, videos, index)
        play(videos[index.coerceIn(0, videos.lastIndex)], fromQueue = true)
        videos.getOrNull(index + 1)?.let { YouTubeRepo.prefetch(it.url) }
    }

    override fun setQuality(height: Int) {
        val loaded = current ?: return
        if (loaded.streams.isHls) {
            // HLS picks the quality itself; just cap it.
            player.currentItem?.preferredMaximumResolution = CGSizeMake(height * 16.0 / 9, height.toDouble())
            _nowPlaying.value = _nowPlaying.value?.copy(height = height)
        } else {
            load(loaded.video, height, startAt = positionSeconds())
        }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        if (player.timeControlStatus == AVPlayerTimeControlStatusPlaying) player.rate = speed
        _nowPlaying.value = _nowPlaying.value?.copy(speed = speed)
    }

    override fun togglePlay() {
        if (player.timeControlStatus == AVPlayerTimeControlStatusPlaying) pause() else resume()
    }

    override fun close() {
        loadJob?.cancel()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        current = null
        PlayQueue.clear()
        _nowPlaying.value = null
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
    }

    override fun setVideoEnabled(enabled: Boolean) {
        _attached.value = if (enabled) player else null
    }

    override fun pause() {
        player.pause()
        updateLockScreen()
    }

    override fun retry() {
        val loaded = current ?: _nowPlaying.value?.let { Loaded(it.video, StreamChoice(null, null, null, false, 0, emptyList()), emptyList()) } ?: return
        (Platform.videos as? InnerTubeSource)?.invalidate(loaded.video.url)
        load(loaded.video, LibraryStore.settings.value.maxHeight, startAt = positionSeconds())
    }

    override fun progress(): Float? {
        val total = durationSeconds()
        return if (total > 0) (positionSeconds() / total).toFloat() else null
    }

    /**
     * For the Simulator test: plays any network file through the same path as YouTube videos
     * (Streamo's downloader, picture and sound combined), without YouTube's checks in the way.
     */
    fun playTestFile(video: VideoItem, fileUrl: String) {
        PlayQueue.clear()
        loadJob?.cancel()
        _nowPlaying.value = NowPlaying(video)
        loadJob = scope.launch {
            try {
                val item = AVPlayerItem.playerItemWithAsset(combine(fileUrl, fileUrl))
                current = Loaded(video, StreamChoice(fileUrl, fileUrl, null, false, 0, emptyList()), emptyList())
                log("loaded test file ${video.title}")
                show(item, startAt = 0.0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("test file failed: ${e.message}")
                _nowPlaying.value = _nowPlaying.value?.copy(error = "${e.message} Tap to retry.", isBuffering = false)
            }
        }
    }

    /** Jumps to [seconds] into the current video. */
    fun seekTo(seconds: Double) {
        player.seekToTime(CMTimeMakeWithSeconds(seconds, 600))
    }

    // ---------- Loading ----------

    private fun start(video: VideoItem, fromQueue: Boolean, rememberCurrent: Boolean) {
        if (!fromQueue) PlayQueue.clear() else PlayQueue.moveTo(video.url)
        YouTubeRepo.prefetch(video.url)
        val old = _nowPlaying.value?.video
        if (rememberCurrent && old != null && old.url != video.url) {
            previous.addLast(old)
            while (previous.size > 20) previous.removeFirst()
        }
        _nowPlaying.value = NowPlaying(video, speed = speed)
        load(video, LibraryStore.settings.value.maxHeight, startAt = 0.0)
    }

    private fun load(video: VideoItem, maxHeight: Int, startAt: Double) {
        loadJob?.cancel()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null) // silence the old video right away
        segments = emptyList()
        loadJob = scope.launch {
            try {
                val details = YouTubeRepo.details(video.url, maxHeight)
                val item = itemFor(details.streams, details.item.durationSec, maxHeight)
                current = Loaded(details.item, details.streams, details.related)
                _nowPlaying.value = (_nowPlaying.value ?: NowPlaying(details.item)).copy(
                    video = details.item,
                    height = details.streams.height,
                    heights = details.streams.availableHeights,
                    error = null,
                )
                LibraryStore.addToHistory(details.item)
                log("loaded ${details.item.title} hls=${details.streams.isHls} height=${details.streams.height}")
                show(item, startAt)
                if (LibraryStore.settings.value.sponsorBlock) {
                    launch {
                        val found = runCatching { sponsorBlock.segments(video.url) }.getOrDefault(emptyList())
                        if (current?.video?.url == details.item.url) segments = found
                        log("sponsor segments: ${found.size}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("Couldn't play ${video.url}", e)
                _nowPlaying.value = _nowPlaying.value?.copy(error = "${friendlyError(e)} Tap to retry.", isBuffering = false)
            }
        }
    }

    /** Puts [item] in the player and starts it; [startAt] seconds in, for quality changes and retries. */
    private fun show(item: AVPlayerItem, startAt: Double) {
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue,
        ) { _ ->
            // AVPlayer also "reaches the end" when the rest of the file can't be downloaded;
            // only move on when the video really played to its end.
            val duration = durationSeconds()
            if (duration > 0 && positionSeconds() >= duration - 3) playNext()
            else {
                log("stopped early at ${positionSeconds().toInt()}s of ${duration.toInt()}s")
                _nowPlaying.value = _nowPlaying.value?.copy(error = "The video stopped loading. Tap to retry.", isBuffering = false)
            }
        }
        player.replaceCurrentItemWithPlayerItem(item)
        if (startAt > 0) seekTo(startAt)
        resume()
        updateLockScreen()
    }

    private suspend fun itemFor(streams: StreamChoice, durationSec: Long, maxHeight: Int): AVPlayerItem {
        val hls = streams.combinedUrl
        val video = streams.videoUrl
        val audio = streams.audioUrl
        return when {
            // HLS whenever YouTube offers it (always for live streams): AVPlayer fetches it itself,
            // and its pieces kept loading in the Simulator test where the separate files got 403s.
            hls != null -> AVPlayerItem.playerItemWithAsset(asset(hls)).apply {
                if (maxHeight > 0) preferredMaximumResolution = CGSizeMake(maxHeight * 16.0 / 9, maxHeight.toDouble())
            }
            video != null && audio != null -> AVPlayerItem.playerItemWithAsset(combine(video, audio))
            else -> throw IllegalStateException("This video can't be played.")
        }
    }

    /** One playable asset from YouTube's separate picture and sound files. */
    private suspend fun combine(videoUrl: String, audioUrl: String): AVAsset {
        // Streamo downloads these files itself (see StreamLoader) and hands AVPlayer the bytes.
        val videoTrack = track(loader.asset(videoUrl), AVMediaTypeVideo, "picture")
        val audioTrack = track(loader.asset(audioUrl), AVMediaTypeAudio, "sound")
        val composition = AVMutableComposition.composition()
        composition.addMutableTrackWithMediaType(AVMediaTypeVideo, kCMPersistentTrackID_Invalid)
            ?.insertTimeRange(videoTrack.timeRange, ofTrack = videoTrack, atTime = CMTimeMake(0, 1), error = null)
        composition.addMutableTrackWithMediaType(AVMediaTypeAudio, kCMPersistentTrackID_Invalid)
            ?.insertTimeRange(audioTrack.timeRange, ofTrack = audioTrack, atTime = CMTimeMake(0, 1), error = null)
        return composition
    }

    /**
     * The first [type] track of a network file. iOS reads the start of the file to find it,
     * so this waits for Apple's asynchronous loading instead of blocking.
     */
    private suspend fun track(asset: AVURLAsset, type: String?, what: String): AVAssetTrack =
        suspendCancellableCoroutine { cont ->
            asset.loadTracksWithMediaType(type!!) { tracks, error ->
                val track = tracks?.firstOrNull() as? AVAssetTrack
                if (track != null) cont.resume(track)
                else {
                    val reason = error?.let { "${it.domain} ${it.code}: ${it.localizedDescription}" } ?: "no $what track"
                    log("couldn't open the $what: $reason")
                    cont.resumeWithException(IllegalStateException("Couldn't open the $what of this video ($reason)."))
                }
            }
        }

    /** A network file opened the way the YouTube iPhone app does (its User-Agent). */
    private fun asset(url: String): AVURLAsset =
        AVURLAsset.URLAssetWithURL(
            NSURL.URLWithString(url)!!,
            mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to mapOf("User-Agent" to YouTubeApi.IOS_USER_AGENT)),
        )

    // ---------- Up next / previous ----------

    /** The playlist's next video, or YouTube's first suggestion that wasn't watched recently. */
    private fun playNext() {
        val loaded = current ?: return
        val next = PlayQueue.nextAfter(loaded.video.url) ?: run {
            val recent = LibraryStore.history.value.take(50).map { it.url }.toSet()
            loaded.related.firstOrNull { it.url !in recent && !it.isLive }
                ?: loaded.related.firstOrNull { it.url != loaded.video.url }
        } ?: return
        log("up next: ${next.title}")
        start(next, fromQueue = PlayQueue.contains(next.url), rememberCurrent = true)
    }

    private fun playPrevious() {
        val back = previous.removeLastOrNull() ?: return seekTo(0.0)
        start(back, fromQueue = PlayQueue.contains(back.url), rememberCurrent = false)
    }

    // ---------- State, SponsorBlock ----------

    private fun resume() {
        player.play()
        if (speed != 1f) player.rate = speed
    }

    private fun positionSeconds(): Double = CMTimeGetSeconds(player.currentTime()).takeIf { !it.isNaN() } ?: 0.0

    private fun durationSeconds(): Double =
        player.currentItem?.duration?.let { CMTimeGetSeconds(it) }?.takeIf { !it.isNaN() && it > 0 } ?: 0.0

    /**
     * Every half second: keeps [nowPlaying] current and skips sponsor segments. A plain loop
     * rather than AVPlayer's time observer, so it also notices a video that never starts.
     */
    private fun watchTime() {
        scope.launch {
            var ticks = 0
            while (true) {
                tick(logState = debugLog && ticks++ % 4 == 0)
                delay(500)
            }
        }
    }

    private fun tick(logState: Boolean) {
        val now = _nowPlaying.value ?: return
        val item = player.currentItem
        val playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying
        val waiting = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        val failed = item?.status == AVPlayerItemStatusFailed
        if (failed && now.error == null) log("failed: ${itemProblem(item)}")
        _nowPlaying.value = now.copy(
            isPlaying = playing,
            isBuffering = (waiting || item == null) && !failed,
            started = now.started || playing,
            error = if (failed) now.error ?: "Couldn't play this video. Tap to retry." else now.error,
            speed = speed,
        )
        val pos = positionSeconds()
        if (playing) {
            val ms = (pos * 1000).toLong()
            segments.firstOrNull { ms >= it.startMs && ms < it.endMs - 500 }?.let { hit ->
                log("skipped ${hit.category} at ${pos.toInt()}s")
                seekTo(hit.endMs / 1000.0)
                Platform.ui.toast("Skipped ${hit.category}")
            }
        }
        if (logState && item != null) {
            log(
                "state playing=$playing waiting=${player.reasonForWaitingToPlay} itemStatus=${item.status} " +
                    "pos=${pos.toInt()}s/${durationSeconds().toInt()}s problem=${itemProblem(item)}"
            )
        }
    }

    /** What AVPlayer says went wrong with [item], for the log. */
    private fun itemProblem(item: AVPlayerItem?): String? {
        val error = item?.error?.let { "${it.domain} ${it.code}: ${it.localizedDescription} ${it.localizedFailureReason.orEmpty()}" }
        val lastEvent = item?.errorLog()?.events?.lastOrNull() as? AVPlayerItemErrorLogEvent
        val logged = lastEvent?.let { "${it.errorStatusCode} ${it.errorDomain} ${it.errorComment.orEmpty()} ${it.URI.orEmpty().take(80)}" }
        return listOfNotNull(error, logged).joinToString(" | ").ifEmpty { null }
    }

    private fun updateLockScreen() {
        val video = _nowPlaying.value?.video ?: return
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = mapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to video.title,
            MPMediaItemPropertyArtist to video.channelName,
            MPMediaItemPropertyPlaybackDuration to (durationSeconds().takeIf { it > 0 } ?: video.durationSec.toDouble()),
            MPNowPlayingInfoPropertyElapsedPlaybackTime to positionSeconds(),
            MPNowPlayingInfoPropertyPlaybackRate to (if (player.timeControlStatus == AVPlayerTimeControlStatusPlaying) speed.toDouble() else 0.0),
        )
    }

    /** Play/pause, next/previous and the seek bar on the lock screen, Control Center and headphones. */
    private fun lockScreenControls() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        center.playCommand.addTargetWithHandler { _ -> resume(); updateLockScreen(); MPRemoteCommandHandlerStatusSuccess }
        center.pauseCommand.addTargetWithHandler { _ -> pause(); MPRemoteCommandHandlerStatusSuccess }
        center.togglePlayPauseCommand.addTargetWithHandler { _ -> togglePlay(); updateLockScreen(); MPRemoteCommandHandlerStatusSuccess }
        center.nextTrackCommand.addTargetWithHandler { _ -> playNext(); MPRemoteCommandHandlerStatusSuccess }
        center.previousTrackCommand.addTargetWithHandler { _ -> playPrevious(); MPRemoteCommandHandlerStatusSuccess }
        center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val target = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
                ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
            seekTo(target)
            updateLockScreen()
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    /** In the background only the sound plays (if the setting allows); the picture comes back on return. */
    private fun followAppLifecycle() {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            if (LibraryStore.settings.value.backgroundPlay) setVideoEnabled(false) else pause()
        }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            setVideoEnabled(true)
        }
    }

    private fun log(message: String) {
        if (debugLog) println("StreamoPlayer: $message")
    }
}
