package com.streamo.app.download

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.streamo.app.data.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class DownloadEntry(
    val id: String,
    val video: VideoItem,
    val audioOnly: Boolean,
    /** Highest picture height wanted (ignored for audio). */
    val height: Int,
    val status: Status = Status.QUEUED,
    /** Where the finished file is (a content:// or file:// address). */
    val uri: String? = null,
    val sizeBytes: Long = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    enum class Status { QUEUED, RUNNING, DONE, FAILED }
}

/** The list of downloads (saved on the phone) and their live progress. */
object Downloads {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences

    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    /** 0..1 for the download in progress. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val cancelled = mutableSetOf<String>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        val saved = prefs.getString("entries", null)
            ?.let { runCatching { json.decodeFromString<List<DownloadEntry>>(it) }.getOrNull() }.orEmpty()
        // Anything that was mid-download when the app was closed starts again.
        _entries.value = saved.map {
            if (it.status == DownloadEntry.Status.RUNNING) it.copy(status = DownloadEntry.Status.QUEUED) else it
        }
    }

    /** Called when the app opens: carries on with downloads that were waiting. */
    fun resumeIfNeeded(context: Context) {
        if (_entries.value.any { it.status == DownloadEntry.Status.QUEUED }) DownloadService.start(context)
    }

    fun enqueue(context: Context, video: VideoItem, audioOnly: Boolean, height: Int) {
        val entry = DownloadEntry(UUID.randomUUID().toString(), video, audioOnly, height)
        edit { listOf(entry) + it }
        DownloadService.start(context)
    }

    fun retry(context: Context, id: String) {
        update(id) { it.copy(status = DownloadEntry.Status.QUEUED, error = null) }
        DownloadService.start(context)
    }

    /** Stops a queued or running download and removes it. */
    fun cancel(id: String) {
        synchronized(cancelled) { cancelled += id }
        edit { list -> list.filterNot { it.id == id } }
        setProgress(id, null)
    }

    /** Removes a download from the list and deletes its file. */
    fun delete(context: Context, id: String) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        entry.uri?.let { deleteFile(context, Uri.parse(it)) }
        edit { list -> list.filterNot { it.id == id } }
    }

    // ---------- Used by DownloadService ----------

    internal fun nextQueued(): DownloadEntry? =
        _entries.value.lastOrNull { it.status == DownloadEntry.Status.QUEUED } // oldest first

    internal fun isCancelled(id: String) = synchronized(cancelled) { id in cancelled }

    internal fun update(id: String, change: (DownloadEntry) -> DownloadEntry) =
        edit { list -> list.map { if (it.id == id) change(it) else it } }

    internal fun setProgress(id: String, value: Float?) =
        _progress.update { if (value == null) it - id else it + (id to value) }

    private fun edit(change: (List<DownloadEntry>) -> List<DownloadEntry>) {
        _entries.update(change)
        prefs.edit().putString("entries", json.encodeToString(_entries.value)).apply()
    }

    private fun deleteFile(context: Context, uri: Uri) {
        runCatching {
            when (uri.scheme) {
                "content" -> context.contentResolver.delete(uri, null, null)
                "file" -> uri.path?.let { File(it).delete() }
            }
        }
    }
}
