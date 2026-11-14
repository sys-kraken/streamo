package com.streamo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Screen pieces and actions that only the platform can provide. */
interface PlatformUi {
    /** The video picture with the platform player's own controls (seek bar, play/pause, fullscreen). */
    @Composable
    fun VideoView(
        modifier: Modifier,
        controls: Boolean,
        fullscreen: Boolean,
        onFullscreen: (() -> Unit)?,
        onSettings: (() -> Unit)?,
    )

    /** "Check for updates" in Settings. Android only: iPhones can't install app files directly. */
    @Composable
    fun AppUpdates()

    /** Opens the system share sheet. */
    fun share(subject: String, text: String)

    /** A short message at the bottom of the screen. */
    fun toast(message: String, long: Boolean = false)

    /** Runs the action given to it once saving downloads is allowed (old Android versions ask first). */
    @Composable
    fun rememberDownloadPermission(): (() -> Unit) -> Unit
}
