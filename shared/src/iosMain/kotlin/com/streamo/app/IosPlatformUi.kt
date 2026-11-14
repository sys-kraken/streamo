package com.streamo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import com.streamo.app.download.DownloadEntry
import com.streamo.app.download.DownloadRunner
import com.streamo.app.download.Downloads
import com.streamo.app.ui.PlatformUi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVKit.AVPlayerViewController
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

// Downloads are a stand-in until phase 7: they fail with a clear message instead of crashing.

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

@OptIn(ExperimentalForeignApi::class)
class IosPlatformUi : PlatformUi {
    @Composable
    override fun VideoView(
        modifier: Modifier,
        controls: Boolean,
        fullscreen: Boolean,
        onFullscreen: (() -> Unit)?,
        onSettings: (() -> Unit)?,
    ) {
        val player by IosPlayer.attached.collectAsState()
        Box(modifier.background(Color.Black)) {
            // Apple's own player view: its controls, Picture-in-Picture and AirPlay come with it.
            UIKitViewController(
                factory = {
                    AVPlayerViewController().apply {
                        allowsPictureInPicturePlayback = true
                        videoGravity = AVLayerVideoGravityResizeAspect
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.player = player
                    view.showsPlaybackControls = controls
                },
            )
            // Streamo's own quality and speed menu, like the gear on Android's player.
            if (controls && onSettings != null) {
                IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 36.dp)) {
                    Icon(Icons.Default.Settings, "Quality and speed", tint = Color.White)
                }
            }
        }
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
        iosLog("Streamo: $message")
    }

    @Composable
    override fun rememberDownloadPermission(): (() -> Unit) -> Unit = { action -> action() }
}
