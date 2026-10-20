package com.streamo.shared

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.streamo.app.IosDownloadRunner
import com.streamo.app.IosPlatformUi
import com.streamo.app.IosPlayer
import com.streamo.app.Platform
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.UserDefaultsStorage
import com.streamo.app.data.VideoItem
import com.streamo.app.data.youtube.InnerTubeSource
import com.streamo.app.download.Downloads
import com.streamo.app.regionCode
import com.streamo.app.ui.AppState
import com.streamo.app.ui.StreamoRoot
import com.streamo.app.ui.StreamoTheme
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

private var started = false

/** Plugs in the iPhone parts once, like StreamoApp.onCreate on Android. */
private fun startOnce() {
    if (started) return
    started = true
    // Finds videos by talking to YouTube directly (Android uses NewPipe instead).
    Platform.videos = InnerTubeSource(HttpClient(), ::regionCode)
    Platform.player = IosPlayer
    Platform.ui = IosPlatformUi()
    LibraryStore.init(UserDefaultsStorage())
    Downloads.init(UserDefaultsStorage("downloads"), IosDownloadRunner)
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .crossfade(180)
            .build()
    }
}

/** Called from Swift (iosApp/iosApp/ContentView.swift) to show the shared Streamo app. */
fun MainViewController(): UIViewController {
    startOnce()
    val state = AppState()
    autoplayForTests(state)
    return ComposeUIViewController {
        StreamoTheme {
            StreamoRoot(
                state,
                landscape = false,
                // AVPlayerViewController has its own Picture-in-Picture button on the video.
                onPip = { Platform.ui.toast("Use the picture-in-picture button on the video.") },
            )
        }
    }
}

/**
 * For the Simulator test run on Codemagic: launched with `-StreamoAutoplay <video link>` the app
 * opens that video by itself, jumps to `-StreamoSeek <seconds>` once it plays, and logs what the
 * player does (lines starting "StreamoPlayer:").
 */
private fun autoplayForTests(state: AppState) {
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val url = arg("-StreamoAutoplay") ?: return
    IosPlayer.debugLog = true
    // The public addresses this app reaches the internet from (YouTube ties stream links to one).
    MainScope().launch {
        val http = HttpClient()
        for (service in listOf("https://api.ipify.org", "https://api64.ipify.org")) {
            val ip = runCatching { http.get(service).bodyAsText() }.getOrElse { "failed: ${it.message}" }
            println("StreamoPlayer: app IP via $service = $ip")
        }
    }
    state.play(VideoItem(url, "Loading…", "", null, null, 0, -1, null))
    val seek = arg("-StreamoSeek")?.toDoubleOrNull() ?: return
    MainScope().launch {
        IosPlayer.nowPlaying.first { it?.started == true }
        delay(3_000)
        println("StreamoPlayer: test seek to ${seek.toInt()}s")
        IosPlayer.seekTo(seek)
    }
}
