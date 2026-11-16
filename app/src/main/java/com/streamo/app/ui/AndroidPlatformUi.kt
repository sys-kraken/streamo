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
import coil3.asDrawable
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
        val settingsClick by rememberUpdatedState(onSettings)
        val segments by SponsorState.segments.collectAsState()
        val settings by LibraryStore.settings.collectAsState()
        val audioOnly = now?.audioOnly == true
        val thumbnail = now?.video?.thumbnail
        // Audio only: show the video's picture instead of a black box.
        var cover by remember { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
        LaunchedEffect(audioOnly, thumbnail) {
            cover = if (!audioOnly || thumbnail == null) null else runCatching<android.graphics.drawable.Drawable?> {
                val loader = coil3.SingletonImageLoader.get(context)
                val result = loader.execute(coil3.request.ImageRequest.Builder(context).data(thumbnail).build())
                result.image?.asDrawable(context.resources)
            }.getOrNull()
        }
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
                    // White seek bar, matching the black-and-white look.
                    findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
                        setPlayedColor(android.graphics.Color.WHITE)
                        setScrubberColor(android.graphics.Color.WHITE)
                        setBufferedColor(0x73FFFFFF)
                        setUnplayedColor(0x33FFFFFF)
                        // SponsorBlock segments show as grey marks.
                        setAdMarkerColor(0xFFAAAAAA.toInt())
                        setPlayedAdMarkerColor(0x88FFFFFF.toInt())
                    }
                    val gestures = PlayerGestures(this)
                    tag = gestures
                    setOnTouchListener { _, e -> gestures.onTouch(e) }
                }
            },
            update = { view ->
                view.player = player
                view.useController = controls
                (view.tag as? PlayerGestures)?.fullscreen = fullscreen
                // Where SponsorBlock segments start, marked on the seek bar.
                val marks = segments.map { it.startMs }.toLongArray()
                view.setExtraAdGroupMarkers(marks, BooleanArray(marks.size))
                view.defaultArtwork = cover
                view.artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_FIT
                view.subtitleView?.apply {
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                    setFractionalTextSize(
                        androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * when (settings.captionSize) {
                            "small" -> 0.8f; "large" -> 1.35f; else -> 1f
                        },
                    )
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            android.graphics.Color.WHITE, 0xB3000000.toInt(), android.graphics.Color.TRANSPARENT,
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK, null,
                        ),
                    )
                }
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
