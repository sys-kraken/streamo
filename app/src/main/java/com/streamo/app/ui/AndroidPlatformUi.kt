package com.streamo.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamo.app.player.AndroidPlayer

/** Android's [PlatformUi]: Media3's PlayerView, the share sheet, toasts and the APK updater. */
class AndroidPlatformUi(context: Context) : PlatformUi {
    private val context = context.applicationContext

    @OptIn(UnstableApi::class)
    @Composable
    override fun VideoView(
        modifier: Modifier,
        controls: Boolean,
        fullscreen: Boolean,
        onFullscreen: (() -> Unit)?,
        onSettings: (() -> Unit)?,
    ) {
        val player by AndroidPlayer.player.collectAsState()
        val now by AndroidPlayer.nowPlaying.collectAsState()
        val settingsClick by rememberUpdatedState(onSettings)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    setShowShuffleButton(false)
                    setShowSubtitleButton(true)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // The gear opens Streamo's own menu (quality + speed) instead of the default one.
                    findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
                        ?.setOnClickListener { settingsClick?.invoke() }
                    // Seek bar in the logo's ember orange.
                    findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                        setPlayedColor(Ember.toArgb())
                        setScrubberColor(Ember.toArgb())
                        setBufferedColor(Parchment.copy(alpha = 0.45f).toArgb())
                    }
                }
            },
            update = { view ->
                view.player = player
                view.useController = controls
                view.keepScreenOn = now?.isPlaying == true // screen stays on while watching
                view.setFullscreenButtonState(fullscreen)
                if (onFullscreen != null) view.setFullscreenButtonClickListener { onFullscreen() }
            },
            onRelease = { it.player = null },
            modifier = modifier,
        )
    }

    @Composable
    override fun AppUpdates() = UpdateSection()

    override fun share(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun toast(message: String, long: Boolean) {
        Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    @Composable
    override fun rememberDownloadPermission(): (() -> Unit) -> Unit {
        // Android 9 and older ask for storage permission before saving to Movies / Music.
        val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
        val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pending.value
            if (granted && action != null) action()
            else toast("Streamo needs storage access to save downloads.", long = true)
        }
        return { action ->
            if (Build.VERSION.SDK_INT < 29) {
                pending.value = action
                askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else action()
        }
    }
}
