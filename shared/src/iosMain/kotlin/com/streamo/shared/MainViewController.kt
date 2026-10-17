package com.streamo.shared

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.streamo.app.IosDownloadRunner
import com.streamo.app.IosPlatformUi
import com.streamo.app.IosPlayer
import com.streamo.app.IosVideoSource
import com.streamo.app.Platform
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.UserDefaultsStorage
import com.streamo.app.download.Downloads
import com.streamo.app.ui.AppState
import com.streamo.app.ui.StreamoRoot
import com.streamo.app.ui.StreamoTheme
import platform.UIKit.UIViewController

private var started = false

/** Plugs in the iPhone parts once, like StreamoApp.onCreate on Android. */
private fun startOnce() {
    if (started) return
    started = true
    Platform.videos = IosVideoSource
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
    return ComposeUIViewController {
        StreamoTheme {
            StreamoRoot(state, landscape = false, onPip = {})
        }
    }
}
