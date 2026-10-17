package com.streamo.app

import com.streamo.app.data.VideoSource
import com.streamo.app.player.PlayerController
import com.streamo.app.ui.PlatformUi

/**
 * The parts that differ between Android and iOS, plugged in once when the app starts.
 * The shared screens reach them through [com.streamo.app.data.YouTubeRepo],
 * [com.streamo.app.player.PlayerConnection] and [ui].
 */
object Platform {
    lateinit var videos: VideoSource
    lateinit var player: PlayerController
    lateinit var ui: PlatformUi
}

/** A feature this platform doesn't have yet; its message is shown to the user as is. */
class ComingSoon(message: String) : Exception(message)

/** Milliseconds since 1970, like System.currentTimeMillis(). */
expect fun nowMillis(): Long

/** Writes an error to the system log (Logcat on Android). */
expect fun logError(message: String, error: Throwable)

/** True when [error] means there's no internet (no connection, or it timed out). */
expect fun isNetworkError(error: Throwable): Boolean

/** Plain text from a YouTube description, which comes as simple HTML. */
expect fun htmlToText(html: String): String
