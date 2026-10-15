package com.streamo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.streamo.app.data.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class Loader<T>(val state: Load<T>, val reload: () -> Unit)

/**
 * Runs [block] when [key] changes; `reload()` runs it again with `refresh = true`
 * (pull-to-refresh or "Try again"). [cached] is shown instantly with no loading spinner.
 */
@Composable
fun <T> rememberLoad(key: Any?, cached: T? = null, block: suspend (refresh: Boolean) -> T): Loader<T> {
    var attempt by remember(key) { mutableIntStateOf(0) }
    val state: MutableState<Load<T>> = remember(key) {
        mutableStateOf(if (cached != null) Load.Done(cached) else Load.Loading)
    }
    LaunchedEffect(key, attempt) {
        if (attempt == 0 && state.value is Load.Done) return@LaunchedEffect
        if (state.value !is Load.Done) state.value = Load.Loading
        state.value = try {
            Load.Done(block(attempt > 0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(friendlyError(e))
        }
    }
    return Loader(state.value) { attempt++ }
}

/**
 * Video lists loaded in the background and kept for the whole session, so switching
 * tabs shows them instantly. Loading the same list twice shares one request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Feeds {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lists = ConcurrentHashMap<String, Deferred<List<VideoItem>>>()

    fun load(key: String, refresh: Boolean = false, block: suspend () -> List<VideoItem>): Deferred<List<VideoItem>> {
        val existing = lists[key]
        val failed = existing != null && existing.isCompleted && existing.getCompletionExceptionOrNull() != null
        if (existing != null && !refresh && !failed) return existing
        return scope.async { block() }.also { lists[key] = it }
    }

    /** The list if it has already finished loading. */
    fun peek(key: String): List<VideoItem>? {
        val d = lists[key] ?: return null
        return if (d.isCompleted && d.getCompletionExceptionOrNull() == null) d.getCompleted() else null
    }
}

fun friendlyError(e: Throwable): String {
    android.util.Log.e("Streamo", "Load failed", e)
    val name = e::class.simpleName.orEmpty()
    return when {
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException -> "No internet connection."
        name.contains("ReCaptcha") -> "YouTube is asking to slow down. Wait a minute and try again."
        name.contains("AgeRestricted") -> "This video is age-restricted and can't be played."
        name.contains("Geographic") -> "This video isn't available in your country."
        name.contains("Private") -> "This video is private."
        name.contains("Unavailable") || name.contains("ContentNotAvailable") -> "This video isn't available."
        else -> "Something went wrong."
    }
}
