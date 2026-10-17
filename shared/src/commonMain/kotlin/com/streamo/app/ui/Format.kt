package com.streamo.app.ui

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

/** One decimal, rounded half up, without a trailing ".0" (1.25 -> "1.3", 2.0 -> "2"). */
private fun trim(v: Double): String {
    if (v >= 10) return v.toLong().toString()
    val tenths = (v * 10 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10}".removeSuffix(".0")
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

private fun two(n: Long) = n.toString().padStart(2, '0')

fun subtitle(channel: String, views: Long, uploaded: String?, live: Boolean = false): String =
    listOfNotNull(
        channel.takeIf { it.isNotBlank() },
        if (live) "LIVE" else formatCount(views, "views").takeIf { it.isNotBlank() },
        uploaded?.takeIf { it.isNotBlank() && !live },
    ).joinToString(" • ")
