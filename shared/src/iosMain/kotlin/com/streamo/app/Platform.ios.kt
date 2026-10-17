package com.streamo.app

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun logError(message: String, error: Throwable) {
    println("Streamo: $message: $error")
}

// Ktor on iOS reports network problems as Darwin errors; recognised properly once iOS loads videos.
actual fun isNetworkError(error: Throwable): Boolean =
    error::class.simpleName.orEmpty().contains("Timeout") || error.message.orEmpty().contains("offline")

actual fun htmlToText(html: String): String =
    html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
