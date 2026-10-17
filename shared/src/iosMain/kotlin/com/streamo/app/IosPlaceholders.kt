package com.streamo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.streamo.app.data.VideoItem
import com.streamo.app.download.DownloadEntry
import com.streamo.app.download.DownloadRunner
import com.streamo.app.download.Downloads
import com.streamo.app.player.NowPlaying
import com.streamo.app.player.PlayerController
import com.streamo.app.ui.PlatformUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

// Stand-ins until the iPhone versions exist: playing videos (phase 6) and downloading (phase 7).
// The screens show their messages instead of crashing.

object IosPlayer : PlayerController {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    override fun play(video: VideoItem, fromQueue: Boolean) {
        _nowPlaying.value = NowPlaying(video, isBuffering = false, error = "Playing videos on iPhone is coming soon.")
    }

    override fun playLocal(video: VideoItem, fileUri: String) = play(video)
    override fun playQueue(title: String, videos: List<VideoItem>, index: Int) {
        videos.getOrNull(index)?.let { play(it) }
    }

    override fun setQuality(height: Int) {}
    override fun setSpeed(speed: Float) {}
    override fun togglePlay() {}
    override fun close() {
        _nowPlaying.value = null
    }

    override fun setVideoEnabled(enabled: Boolean) {}
    override fun pause() {}
    override fun retry() {}
    override fun progress(): Float? = null
}

object IosDownloadRunner : DownloadRunner {
    override fun start() {
        while (true) {
            val entry = Downloads.nextQueued() ?: break
            Downloads.update(entry.id) {
                it.copy(status = DownloadEntry.Status.FAILED, error = "Downloads on iPhone are coming soon.")
            }
        }
    }

    override fun deleteFile(uri: String) {}
}

class IosPlatformUi : PlatformUi {
    @Composable
    override fun VideoView(
        modifier: Modifier,
        controls: Boolean,
        fullscreen: Boolean,
        onFullscreen: (() -> Unit)?,
        onSettings: (() -> Unit)?,
    ) {
        Box(modifier.background(Color.Black))
    }

    @Composable
    override fun AppUpdates() {
        // iPhones install updates from where the app came from, not from inside the app.
    }

    override fun share(subject: String, text: String) {
        val sheet = UIActivityViewController(listOf(text), null)
        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(sheet, true, null)
    }

    override fun toast(message: String, long: Boolean) {
        println("Streamo: $message")
    }

    @Composable
    override fun rememberDownloadPermission(): (() -> Unit) -> Unit = { action -> action() }
}
