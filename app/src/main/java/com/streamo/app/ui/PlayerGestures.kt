package com.streamo.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.streamo.app.R
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.streamo.app.data.LibraryStore
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * YouTube-style touch controls on the video:
 *  - double-tap the left or right side: back or forward 10 seconds (taps keep adding up),
 *  - press and hold: 2x speed until you let go,
 *  - in fullscreen, swipe up/down on the left for brightness and on the right for volume.
 * A single tap still shows and hides the player's own controls. Turned off in Settings (Player gestures).
 */
@OptIn(UnstableApi::class)
class PlayerGestures(private val view: PlayerView) {
    /** Only in fullscreen, so swiping down on the small player still shrinks it to the mini player. */
    var fullscreen = false

    private val handler = Handler(Looper.getMainLooper())
    private val audio = view.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val density = view.resources.displayMetrics.density
    private val icon = ImageView(view.context)
    private val text = TextView(view.context).apply {
        setTextColor(android.graphics.Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }
    /** The little pill in the middle of the video: an icon and a value ("10s", "2x", "60%"). */
    private val label = LinearLayout(view.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = (12 * density).roundToInt()
        setPadding(pad * 2, pad, pad * 2, pad)
        background = GradientDrawable().apply { cornerRadius = 24 * density; setColor(0x99000000.toInt()) }
        addView(icon, LinearLayout.LayoutParams((22 * density).roundToInt(), (22 * density).roundToInt()).apply { marginEnd = (8 * density).roundToInt() })
        addView(text)
        visibility = View.GONE
    }
    private val hideLabel = Runnable { label.visibility = View.GONE }

    private var holding = false
    private var speedBefore = 1f
    private var swiping: String? = null // "brightness" or "volume"
    private var swipeStart = 0f
    private var startValue = 0f
    private var seekTotal = 0

    init {
        view.overlayFrameLayout?.addView(
            label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    private val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val p = view.player ?: return false
            val forward = e.x > view.width / 2f
            seekTotal = if ((seekTotal > 0) == forward && seekTotal != 0) seekTotal + 10 else if (forward) 10 else -10
            p.seekTo((p.currentPosition + if (forward) 10_000 else -10_000).coerceAtLeast(0))
            show(if (forward) R.drawable.ic_gesture_forward else R.drawable.ic_gesture_rewind, "${abs(seekTotal)}s")
            handler.postDelayed({ seekTotal = 0 }, 900)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (view.useController) {
                if (view.isControllerFullyVisible) view.hideController() else view.showController()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val p = view.player ?: return
            if (!p.isPlaying) return
            holding = true
            speedBefore = p.playbackParameters.speed
            p.setPlaybackSpeed(2f)
            show(R.drawable.ic_gesture_forward, "2x", stay = true)
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    fun onTouch(e: MotionEvent): Boolean {
        if (!LibraryStore.settings.value.gestures) return false
        if (fullscreen && handleSwipe(e)) return true
        detector.onTouchEvent(e)
        if ((e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) && holding) {
            holding = false
            view.player?.setPlaybackSpeed(speedBefore)
            hideSoon()
        }
        // Consume taps (handled above); let moves through so the screen can still scroll/swipe.
        return e.action != MotionEvent.ACTION_MOVE
    }

    private fun handleSwipe(e: MotionEvent): Boolean {
        val slop = 24 * view.resources.displayMetrics.density
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { swiping = null; swipeStart = e.y }
            MotionEvent.ACTION_MOVE -> {
                val dy = swipeStart - e.y
                if (swiping == null && abs(dy) > slop && !holding) {
                    swiping = if (e.x < view.width / 2f) "brightness" else "volume"
                    swipeStart = e.y
                    startValue = if (swiping == "brightness") currentBrightness() else
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    return true
                }
                if (swiping != null) {
                    val value = (startValue + (swipeStart - e.y) / (view.height * 0.8f)).coerceIn(0f, 1f)
                    if (swiping == "brightness") {
                        activity()?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = value.coerceAtLeast(0.01f) } }
                        show(R.drawable.ic_gesture_brightness, "${(value * 100).roundToInt()}%", stay = true)
                    } else {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt(), 0)
                        show(R.drawable.ic_gesture_volume, "${(value * 100).roundToInt()}%", stay = true)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping != null) {
                swiping = null
                hideSoon()
                return true
            }
        }
        return false
    }

    private fun currentBrightness(): Float {
        val b = activity()?.window?.attributes?.screenBrightness ?: -1f
        if (b >= 0) return b
        return runCatching {
            android.provider.Settings.System.getInt(view.context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    private fun activity(): Activity? {
        var c = view.context
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    private fun show(iconRes: Int, value: String, stay: Boolean = false) {
        icon.setImageResource(iconRes)
        text.text = value
        label.visibility = View.VISIBLE
        handler.removeCallbacks(hideLabel)
        if (!stay) hideSoon()
    }

    private fun hideSoon() {
        handler.removeCallbacks(hideLabel)
        handler.postDelayed(hideLabel, 700)
    }
}
