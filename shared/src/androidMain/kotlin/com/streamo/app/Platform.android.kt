package com.streamo.app

import android.text.Html
import android.util.Log

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun regionCode(): String = java.util.Locale.getDefault().country

actual fun logError(message: String, error: Throwable) {
    Log.e("Streamo", message, error)
}

actual fun isNetworkError(error: Throwable): Boolean =
    error is java.net.UnknownHostException || error is java.net.SocketTimeoutException

actual fun htmlToText(html: String): String = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
