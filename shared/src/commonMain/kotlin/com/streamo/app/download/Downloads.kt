package com.streamo.app.download

import com.streamo.app.data.KeyValueStorage
import com.streamo.app.data.VideoItem
import com.streamo.app.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    val createdAt: Long = nowMillis(),
) {
    enum class Status { QUEUED, RUNNING, DONE, FAILED }
}

/** Does the actual downloading. Android: DownloadService; iOS: later. */
interface DownloadRunner {
    /** Start working through the queued downloads (does nothing if already running). */
    fun start()

    /** Deletes a finished download's file. */
    fun deleteFile(uri: String)
}

/** The list of downloads (saved on the phone) and their live progress. */
object Downloads {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var storage: KeyValueStorage
    private lateinit var runner: DownloadRunner

    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    /** 0..1 for the download in progress. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val cancelled = MutableStateFlow<Set<String>>(emptySet())

    fun init(storage: KeyValueStorage, runner: DownloadRunner) {
        this.storage = storage
        this.runner = runner
        val saved = storage.get("entries")
            ?.let { runCatching { json.decodeFromString<List<DownloadEntry>>(it) }.getOrNull() }.orEmpty()
        // Anything that was mid-download when the app was closed starts again.
        _entries.value = saved.map {
            if (it.status == DownloadEntry.Status.RUNNING) it.copy(status = DownloadEntry.Status.QUEUED) else it
        }
    }

    /** Called when the app opens: carries on with downloads that were waiting. */
    fun resumeIfNeeded() {
        if (_entries.value.any { it.status == DownloadEntry.Status.QUEUED }) runner.start()
    }

    @OptIn(ExperimentalUuidApi::class)
    fun enqueue(video: VideoItem, audioOnly: Boolean, height: Int) {
        val entry = DownloadEntry(Uuid.random().toString(), video, audioOnly, height)
        edit { listOf(entry) + it }
        runner.start()
    }

    fun retry(id: String) {
        update(id) { it.copy(status = DownloadEntry.Status.QUEUED, error = null) }
        runner.start()
    }

    /** Stops a queued or running download and removes it. */
    fun cancel(id: String) {
        cancelled.update { it + id }
        edit { list -> list.filterNot { it.id == id } }
        setProgress(id, null)
    }

    /** Removes a download from the list and deletes its file. */
    fun delete(id: String) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        entry.uri?.let { runCatching { runner.deleteFile(it) } }
        edit { list -> list.filterNot { it.id == id } }
    }

    // ---------- Used by the platform's downloader ----------

    fun nextQueued(): DownloadEntry? =
        _entries.value.lastOrNull { it.status == DownloadEntry.Status.QUEUED } // oldest first

    fun isCancelled(id: String) = id in cancelled.value

    fun update(id: String, change: (DownloadEntry) -> DownloadEntry) =
        edit { list -> list.map { if (it.id == id) change(it) else it } }

    fun setProgress(id: String, value: Float?) =
        _progress.update { if (value == null) it - id else it + (id to value) }

    private fun edit(change: (List<DownloadEntry>) -> List<DownloadEntry>) {
        _entries.update(change)
        storage.put("entries", json.encodeToString(_entries.value))
    }
}
