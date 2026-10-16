package com.streamo.app.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.streamo.app.MainActivity
import com.streamo.app.R
import com.streamo.app.StreamoApp
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.SkipSegment
import com.streamo.app.data.VideoItem
import com.streamo.app.data.YouTubeRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Owns the player. Because it runs as a foreground media service, Android keeps it alive
 * when the app is closed or the screen is off, and shows lock-screen / notification controls.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession

    private var segments: List<SkipSegment> = emptyList()
    private var sponsorJob: Job? = null
    private var retriedUrl: String? = null

    override fun onCreate() {
        super.onCreate()

        val dataSource = ChunkedDataSource.Factory(OkHttpDataSource.Factory(StreamoApp.http))
        val loadControl = DefaultLoadControl.Builder()
            // Start playing after 1s of video is buffered instead of the default 2.5s.
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(StreamSourceFactory(this, dataSource))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)  // keep CPU + Wi-Fi awake while playing, screen off
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(sessionCallback)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )
        startSponsorWatcher()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recents: keep going only if something is playing.
        if (!player.playWhenReady || player.mediaItemCount == 0 || !LibraryStore.settings.value.backgroundPlay) {
            player.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    // ---------- Turning a YouTube link into something playable ----------

    private val sessionCallback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = scope.future {
            mediaItems.map { item ->
                try {
                    resolve(item)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed(item, e)
                }
            }
        }
    }

    /**
     * The app only sends the video's page link (as mediaId). Here we look up the actual stream
     * files; the lookup is usually already cached because the app prefetches on touch.
     */
    private suspend fun resolve(item: MediaItem): MediaItem {
        item.requestMetadata.mediaUri?.let { local -> return resolveLocal(item, local) }
        val url = item.mediaId
        val maxHeight = item.requestMetadata.extras?.getInt(EXTRA_MAX_HEIGHT)?.takeIf { it > 0 }
            ?: LibraryStore.settings.value.maxHeight
        val (video, streams) = YouTubeRepo.streams(url, maxHeight)

        val extras = Bundle().apply {
            putString(EXTRA_VIDEO, json.encodeToString(video))
            putInt(EXTRA_HEIGHT, streams.height)
            putIntArray(EXTRA_HEIGHTS, streams.availableHeights.toIntArray())
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(video.title)
            .setArtist(video.channelName)
            .setArtworkUri(video.thumbnail?.let(Uri::parse))
            .setExtras(extras)
            .build()

        return item.buildUpon()
            .setUri(streams.videoUrl ?: streams.combinedUrl)
            .setMimeType(if (streams.isHls) "application/x-mpegURL" else null)
            .setMediaMetadata(metadata)
            .setTag(streams)
            .build()
    }

    /**
     * The video couldn't be looked up (removed, private, age-restricted, no internet...). Hand the
     * player an address it can't open, so it reports an error the app shows with the reason,
     * instead of waiting forever.
     */
    private fun failed(item: MediaItem, e: Exception): MediaItem {
        android.util.Log.e("Streamo", "Couldn't look up ${item.mediaId}", e)
        val metadata = item.mediaMetadata.buildUpon()
            .setExtras(Bundle().apply { putString(EXTRA_ERROR, com.streamo.app.ui.friendlyError(e)) })
            .build()
        return item.buildUpon().setUri("streamo://unavailable").setMediaMetadata(metadata).setTag(null).build()
    }

    /** A downloaded file: no lookup needed, just play it. */
    private fun resolveLocal(item: MediaItem, local: Uri): MediaItem {
        val videoJson = item.requestMetadata.extras?.getString(EXTRA_VIDEO)
        val metadata = item.mediaMetadata.buildUpon()
            .setExtras(Bundle().apply { putString(EXTRA_VIDEO, videoJson) })
            .build()
        return item.buildUpon().setUri(local).setMediaMetadata(metadata).setTag(null).build()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            segments = emptyList()
            val item = mediaItem ?: return
            PlayQueue.moveTo(item.mediaId)
            item.mediaMetadata.extras?.getString(EXTRA_VIDEO)?.let {
                runCatching { json.decodeFromString<VideoItem>(it) }.getOrNull()?.let(LibraryStore::addToHistory)
            }
            scope.launch { queueUpNext(item.mediaId) }
            if (LibraryStore.settings.value.sponsorBlock) {
                val url = item.mediaId
                scope.launch {
                    val found = StreamoApp.sponsorBlock.segments(url)
                    if (player.currentMediaItem?.mediaId == url) segments = found
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Stream links expire after a few hours; fetch fresh ones once and carry on.
            val item = player.currentMediaItem ?: return
            val http = error.cause as? HttpDataSource.InvalidResponseCodeException
            if (http?.responseCode != 403 || retriedUrl == item.mediaId) return
            retriedUrl = item.mediaId
            YouTubeRepo.invalidate(item.mediaId)
            val position = player.currentPosition
            scope.launch {
                runCatching { resolve(item) }.onSuccess { fresh ->
                    player.replaceMediaItem(player.currentMediaItemIndex, fresh)
                    player.seekTo(position)
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    // ---------- Up next ----------

    /**
     * Puts the first "up next" video after the current one, so the Next button works in the
     * notification, lock screen and pop-up window, and playback continues when a video ends.
     */
    private suspend fun queueUpNext(url: String) {
        // In a playlist, the next video is the playlist's; otherwise YouTube's first suggestion.
        val next = PlayQueue.nextAfter(url) ?: run {
            val details = runCatching { YouTubeRepo.details(url, LibraryStore.settings.value.maxHeight) }.getOrNull()
                ?: return
            val recent = LibraryStore.history.value.take(50).map { it.url }.toSet()
            details.related.firstOrNull { it.url !in recent && !it.isLive }
                ?: details.related.firstOrNull { it.url != url } ?: return
        }
        val resolved = runCatching { resolve(MediaItem.Builder().setMediaId(next.url).build()) }.getOrNull() ?: return
        if (player.currentMediaItem?.mediaId != url) return // the user switched videos meanwhile

        val index = player.currentMediaItemIndex
        if (player.mediaItemCount > index + 1) player.removeMediaItems(index + 1, player.mediaItemCount)
        player.addMediaItem(resolved)
        // Keep just one earlier video so Previous can go back to it.
        if (index > 1) player.removeMediaItems(0, index - 1)
    }

    // ---------- SponsorBlock ----------

    private fun startSponsorWatcher() {
        sponsorJob = scope.launch {
            while (isActive) {
                delay(400)
                if (!player.isPlaying || segments.isEmpty()) continue
                val pos = player.currentPosition
                val hit = segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 500 } ?: continue
                player.seekTo(hit.endMs)
                Toast.makeText(this@PlaybackService, "Skipped ${label(hit.category)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun label(category: String) = when (category) {
        "sponsor" -> "sponsor"
        "selfpromo" -> "self-promotion"
        "interaction" -> "subscribe reminder"
        else -> category
    }

    companion object {
        const val EXTRA_MAX_HEIGHT = "max_height"
        const val EXTRA_VIDEO = "video"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_HEIGHTS = "heights"
        const val EXTRA_ERROR = "error"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
