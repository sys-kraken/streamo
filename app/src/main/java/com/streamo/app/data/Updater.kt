package com.streamo.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.streamo.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Updates come from the newest GitHub release of [REPO]: its tag is the version
 * (e.g. "v1.1.0") and it must have the APK attached, signed with the same key as this app.
 */
class Updater(private val client: OkHttpClient) {

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long)

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        val name: String,
        val size: Long = 0,
        @SerialName("browser_download_url") val url: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The newest release with an APK, or null if there is none yet. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null // no releases published yet
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val release = json.decodeFromString<GhRelease>(response.body.string())
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return@withContext null
            Release(release.tag.removePrefix("v").removePrefix("V"), release.body.orEmpty().trim(), apk.url, apk.size)
        }
    }

    fun isNewer(release: Release) = compareVersions(release.version, BuildConfig.VERSION_NAME) > 0

    /** Downloads the APK into the app's cache, reporting progress from 0 to 1. */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "streamo-${release.version}.apk")
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (${response.code})")
                val total = response.body.contentLength().takeIf { it > 0 } ?: release.apkSize
                response.body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            file
        }

    companion object {
        const val REPO = "syskraken/streamo"

        /**
         * Opens Android's installer for the downloaded APK. Returns false (after opening the
         * settings page) if Streamo first needs permission to install apps.
         */
        fun install(context: Context, apk: File): Boolean {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return false
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        }

        /** "1.10.0" > "1.9.2"; missing parts count as 0. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
                if (d != 0) return d
            }
            return 0
        }
    }
}
