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
import kotlin.math.roundToInt
import com.streamo.app.data.LibraryStore
import com.streamo.app.player.AndroidPlayer
import com.streamo.app.player.SponsorState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue

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
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // Streamo draws its own controls and captions on top (shared PlayerControls).
                    useController = false
                    subtitleView?.visibility = android.view.View.GONE
                    artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_OFF
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.player = player
                view.keepScreenOn = now?.isPlaying == true // screen stays on while watching
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

    override fun brightness(): Float {
        val window = currentActivity?.get()?.window ?: return -1f
        val b = window.attributes.screenBrightness
        if (b >= 0) return b
        return runCatching {
            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    override fun setBrightness(value: Float) {
        val window = currentActivity?.get()?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
    }

    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    override fun volume(): Float {
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
    }

    override fun setVolume(value: Float) {
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (value * max).roundToInt(), 0)
    }

    companion object {
        /** The activity on screen, for screen brightness. Set by MainActivity. */
        var currentActivity: java.lang.ref.WeakReference<android.app.Activity>? = null
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
