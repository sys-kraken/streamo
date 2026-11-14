package com.streamo.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.streamo.app.MainActivity
import com.streamo.app.R
import com.streamo.app.StreamoApp
import com.streamo.app.data.NewPipeRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Works through the download queue one at a time, in the foreground so it keeps going
 * when the app is closed: fetch the picture and sound files, join them, save to the phone.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private lateinit var notifications: NotificationManager
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification("Preparing download…", null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (worker?.isActive != true) worker = scope.launch { runQueue() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runQueue() {
        while (true) {
            val entry = Downloads.nextQueued() ?: break
            Downloads.update(entry.id) { it.copy(status = DownloadEntry.Status.RUNNING, error = null) }
            Downloads.setProgress(entry.id, 0f)
            try {
                val (uri, size) = download(entry)
                if (Downloads.isCancelled(entry.id)) {
                    Downloads.delete(entry.id)
                } else {
                    Downloads.update(entry.id) { it.copy(status = DownloadEntry.Status.DONE, uri = uri.toString(), sizeBytes = size) }
                    notifications.notify(entry.id.hashCode(), doneNotification(entry))
                }
            } catch (e: CancellationException) {
                if (!Downloads.isCancelled(entry.id)) throw e
            } catch (e: Exception) {
                android.util.Log.e("Streamo", "Download failed", e)
                Downloads.update(entry.id) {
                    it.copy(status = DownloadEntry.Status.FAILED, error = "Download failed. Tap Retry to try again.")
                }
            } finally {
                Downloads.setProgress(entry.id, null)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Returns the saved file's address and size. */
    private suspend fun download(entry: DownloadEntry): Pair<Uri, Long> {
        val work = File(cacheDir, "downloads/${entry.id}").apply { mkdirs() }
        try {
            val (video, audio) = NewPipeRepo.downloadSources(entry.video.url, entry.audioOnly, entry.height)
            val videoFile = video?.let { File(work, "video.mp4") }
            val audioFile = File(work, "audio.m4a")

            // Progress is shared between the two files by their size.
            val videoSize = video?.itagItem?.contentLength?.takeIf { it > 0 } ?: 0L
            val audioSize = audio.itagItem?.contentLength?.takeIf { it > 0 } ?: 0L
            val total = (videoSize + audioSize).toFloat()
            var doneBytes = 0L
            val report = { bytes: Long ->
                doneBytes += bytes
                if (total > 0) progress(entry, (doneBytes / total).coerceAtMost(0.99f))
            }

            if (video != null && videoFile != null) fetch(entry.id, video.content, videoFile, report)
            fetch(entry.id, audio.content, audioFile, report)

            val out = File(work, if (entry.audioOnly) "out.m4a" else "out.mp4")
            Muxer.mux(videoFile, audioFile, out)
            return save(entry, out) to out.length()
        } finally {
            work.deleteRecursively()
        }
    }

    /** Downloads in 4 MB pieces: YouTube slows down one big open-ended download. */
    private fun fetch(id: String, url: String, target: File, onBytes: (Long) -> Unit) {
        val chunk = 4L * 1024 * 1024
        var position = 0L
        var total = -1L
        target.outputStream().use { out ->
            while (total < 0 || position < total) {
                if (Downloads.isCancelled(id)) throw CancellationException("cancelled")
                val end = position + chunk - 1
                val request = Request.Builder().url(url).header("Range", "bytes=$position-$end").build()
                StreamoApp.http.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) throw IOException("HTTP ${response.code}")
                    if (total < 0) {
                        total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: response.body.contentLength()
                    }
                    val input = response.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var gotAny = false
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        position += n
                        onBytes(n.toLong())
                        gotAny = true
                    }
                    if (!gotAny) return // server had nothing more
                    if (response.code == 200) return // server sent the whole file at once
                }
            }
        }
    }

    /** Copies the finished file into Movies/Streamo (video) or Music/Streamo (audio). */
    private fun save(entry: DownloadEntry, file: File): Uri {
        val name = entry.video.title.replace(Regex("""[\\/:*?"<>|]"""), "").trim().take(120).ifEmpty { "video" } +
            if (entry.audioOnly) ".m4a" else ".mp4"
        val mime = if (entry.audioOnly) "audio/mp4" else "video/mp4"
        val folder = if (entry.audioOnly) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES

        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (entry.audioOnly) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Streamo")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (entry.audioOnly) put(MediaStore.Audio.Media.ARTIST, entry.video.channelName)
            }
            val uri = contentResolver.insert(collection, values) ?: throw IOException("Couldn't create the file")
            try {
                contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
            return uri
        }

        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(folder), "Streamo").apply { mkdirs() }
        var target = File(dir, name)
        var n = 1
        while (target.exists()) target = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.')).also { n++ }
        file.copyTo(target)
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(target)
    }

    // ---------- Notifications ----------

    private fun progress(entry: DownloadEntry, value: Float) {
        Downloads.setProgress(entry.id, value)
        val now = System.currentTimeMillis()
        if (now - lastNotified > 800) {
            lastNotified = now
            notifications.notify(NOTIFICATION_ID, notification(entry.video.title, value))
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "downloads"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(text: String, progress: Float?): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .build()

    private fun doneNotification(entry: DownloadEntry): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloaded")
            .setContentText(entry.video.title)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
