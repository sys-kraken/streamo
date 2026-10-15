package com.streamo.app.ui

import java.util.Locale

fun formatCount(n: Long, unit: String): String {
    if (n < 0) return ""
    val text = when {
        n >= 1_000_000_000 -> trim(n / 1e9) + "B"
        n >= 1_000_000 -> trim(n / 1e6) + "M"
        n >= 1_000 -> trim(n / 1e3) + "K"
        else -> n.toString()
    }
    return "$text $unit"
}

private fun trim(v: Double): String =
    if (v >= 10) v.toLong().toString() else String.format(Locale.US, "%.1f", v).removeSuffix(".0")

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun subtitle(channel: String, views: Long, uploaded: String?, live: Boolean = false): String =
    listOfNotNull(
        channel.takeIf { it.isNotBlank() },
        if (live) "LIVE" else formatCount(views, "views").takeIf { it.isNotBlank() },
        uploaded?.takeIf { it.isNotBlank() && !live },
    ).joinToString(" • ")
