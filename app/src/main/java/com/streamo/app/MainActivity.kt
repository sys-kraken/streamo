package com.streamo.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.SponsorBlock
import com.streamo.app.data.VideoItem
import com.streamo.app.download.Downloads
import com.streamo.app.player.AndroidPlayer
import com.streamo.app.player.PlayerConnection
import com.streamo.app.ui.AppState
import com.streamo.app.ui.PlayerSurface
import com.streamo.app.ui.StreamoRoot
import com.streamo.app.ui.StreamoTheme

class MainActivity : ComponentActivity() {

    /** The shared app's frame (player open, fullscreen, page requests); this activity adds Android's parts. */
    private val state = AppState()
    private var inPip by mutableStateOf(false)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AndroidPlayer.connect(this)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleShare(intent)
        state.openRequest = intent?.getStringExtra(EXTRA_OPEN)
        Downloads.resumeIfNeeded()

        setContent {
            StreamoTheme {
                if (inPip) {
                    PlayerSurface(Modifier.fillMaxSize(), controls = false)
                } else {
                    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    LaunchedEffect(state.fullscreen) { applyFullscreen(state.fullscreen) }
                    BackHandler(enabled = state.fullscreen || state.watchExpanded) { state.back() }
                    StreamoRoot(state, landscape, onPip = ::enterPip)
                }
            }
        }
    }

    // ---------- Picture-in-picture & background ----------

    private fun pipParams(): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()

    private fun enterPip() {
        if (PlayerConnection.nowPlaying.value != null) enterPictureInPictureMode(pipParams())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Pressing Home while watching keeps the video in a small floating window.
        val now = PlayerConnection.nowPlaying.value
        if (state.watchExpanded && now?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        PlayerConnection.setVideoEnabled(true)
    }

    override fun onStop() {
        super.onStop()
        // App hidden or screen off: keep the sound, stop downloading the picture.
        if (LibraryStore.settings.value.backgroundPlay) PlayerConnection.setVideoEnabled(false)
        else PlayerConnection.pause()
    }

    private fun applyFullscreen(on: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    // ---------- "Share to Streamo" from the YouTube app or browser ----------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { state.openRequest = it }
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val id = SponsorBlock.videoId(text) ?: return
        state.play(VideoItem("https://www.youtube.com/watch?v=$id", "Loading…", "", null, null, 0, -1, null))
    }

    companion object {
        /** Which page to open, e.g. "downloads" from the download notification. */
        const val EXTRA_OPEN = "open"
    }
}
