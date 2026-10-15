package com.streamo.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

/** A part of a video to jump over, in milliseconds. */
data class SkipSegment(val startMs: Long, val endMs: Long, val category: String)

/**
 * Community-made list of sponsor messages inside videos (https://sponsor.ajay.app).
 * Only the first 4 characters of a hash of the video id are sent, so the server
 * never learns which video is being watched.
 */
class SponsorBlock(private val client: OkHttpClient) {
    @Serializable
    private data class VideoSegments(val videoID: String, val segments: List<Segment>)

    @Serializable
    private data class Segment(val category: String, val actionType: String = "skip", val segment: List<Double>)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun segments(videoUrl: String): List<SkipSegment> = withContext(Dispatchers.IO) {
        val id = videoId(videoUrl) ?: return@withContext emptyList()
        val prefix = sha256(id).take(4)
        val categories = CATEGORIES.joinToString(",", "[", "]") { "\"$it\"" }
        val request = Request.Builder()
            .url("https://sponsor.ajay.app/api/skipSegments/$prefix?categories=$categories")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                json.decodeFromString<List<VideoSegments>>(response.body.string())
                    .firstOrNull { it.videoID == id }
                    ?.segments.orEmpty()
                    .filter { it.actionType == "skip" && it.segment.size == 2 }
                    .map { SkipSegment((it.segment[0] * 1000).toLong(), (it.segment[1] * 1000).toLong(), it.category) }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** Paid promotions, self-promotion and "like and subscribe" reminders. */
        val CATEGORIES = listOf("sponsor", "selfpromo", "interaction")

        fun videoId(url: String): String? =
            Regex("""(?:v=|youtu\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
