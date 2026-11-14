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

/** One decimal, without a trailing ".0" (1.25 -> "1.3", 2.0 -> "2"). */
private fun trim(v: Double): String =
    if (v >= 10) v.toLong().toString() else oneDecimal(v).removeSuffix(".0")

/** One decimal, rounded half up like "%.1f" (1.25 -> "1.3", 2.0 -> "2.0"). */
fun oneDecimal(v: Double): String {
    val tenths = (v * 10 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10}"
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

private const val DAY_MS = 24L * 60 * 60 * 1000
private val weekdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** The phone-local day number (days since 1 Jan 1970) for [ms]. */
fun localDay(ms: Long): Long = (ms + com.streamo.app.utcOffsetMillis(ms)).floorDiv(DAY_MS)

/** "Today", "Yesterday", "Tuesday" (this week), "Mar 3" (this year) or "Mar 3, 2025". */
fun dayLabel(ms: Long, now: Long): String {
    val day = localDay(ms)
    val today = localDay(now)
    return when (val diff = today - day) {
        0L -> "Today"
        1L -> "Yesterday"
        in 2L..6L -> weekdays[(day + 3).mod(7L).toInt()] // 1 Jan 1970 was a Thursday
        else -> {
            val (y, m, d) = civil(day)
            val (thisYear, _, _) = civil(today)
            if (y == thisYear && diff > 0) "${months[m - 1]} $d" else "${months[m - 1]} $d, $y"
        }
    }
}

/** Year, month (1-12), day for a day number; Howard Hinnant's days-to-civil algorithm. */
private fun civil(days: Long): Triple<Long, Int, Int> {
    val z = days + 719468
    val era = z.floorDiv(146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
}
