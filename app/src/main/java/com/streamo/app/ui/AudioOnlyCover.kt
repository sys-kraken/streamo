package com.streamo.app.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.streamo.app.R
import com.streamo.app.player.PlayerConnection
import kotlin.math.roundToInt

/**
 * What the video area shows in audio-only mode: the video's picture (dimmed), a headphones label and
 * a "Show video" button. It sits in the player's overlay layer, so the player's controls stay on top.
 */
class AudioOnlyCover(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private val picture = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setColorFilter(0x66000000) // dim it so the label and controls stay readable
    }

    private val badge = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x99000000.toInt()) }
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_audio_only) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(TextView(context).apply {
            text = "Audio only"
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(6), 0, 0, 0)
        })
    }

    private val showVideo = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(14), dp(8))
        minimumHeight = dp(40)
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(android.graphics.Color.WHITE) }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_show_video)
            setColorFilter(android.graphics.Color.BLACK)
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(TextView(context).apply {
            text = "Show video"
            setTextColor(android.graphics.Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(6), 0, 0, 0)
        })
        isClickable = true
        contentDescription = "Show video"
        setOnClickListener { PlayerConnection.setAudioOnly(false) }
    }

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        visibility = View.GONE
        addView(picture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12); marginEnd = dp(12)
        })
        addView(showVideo, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(52); marginEnd = dp(12)
        })
    }

    /** [full]: the big player (label and button); false in the mini player (just the picture). */
    fun update(on: Boolean, image: Drawable?, full: Boolean) {
        visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        picture.setImageDrawable(image)
        badge.visibility = if (full) View.VISIBLE else View.GONE
        showVideo.visibility = if (full) View.VISIBLE else View.GONE
    }
}
