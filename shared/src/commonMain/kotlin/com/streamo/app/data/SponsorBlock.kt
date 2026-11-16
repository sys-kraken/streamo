package com.streamo.app.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8

/** A part of a video to jump over, in milliseconds. A "poi_highlight" has start == end: the best part. */
data class SkipSegment(val startMs: Long, val endMs: Long, val category: String) {
    val isHighlight get() = category == "poi_highlight"
}

/**
 * Community-made list of sponsor messages inside videos (https://sponsor.ajay.app).
 * Only the first 4 characters of a hash of the video id are sent, so the server
 * never learns which video is being watched.
 */
class SponsorBlock(private val client: HttpClient) {
    @Serializable
    private data class VideoSegments(val videoID: String, val segments: List<Segment>)

    @Serializable
    private data class Segment(val category: String, val actionType: String = "skip", val segment: List<Double>)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun segments(videoUrl: String): List<SkipSegment> {
        val id = videoId(videoUrl) ?: return emptyList()
        val prefix = id.encodeUtf8().sha256().hex().take(4)
        val categories = CATEGORIES.joinToString(",", "[", "]") { "\"$it\"" }
        return runCatching {
            val response = client.get("https://sponsor.ajay.app/api/skipSegments/$prefix") {
                parameter("categories", categories)
            }
            if (!response.status.isSuccess()) return emptyList()
            json.decodeFromString<List<VideoSegments>>(response.bodyAsText())
                .firstOrNull { it.videoID == id }
                ?.segments.orEmpty()
                .filter { (it.actionType == "skip" || it.actionType == "poi") && it.segment.size == 2 }
                .map { SkipSegment((it.segment[0] * 1000).toLong(), (it.segment[1] * 1000).toLong(), it.category) }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** Every kind of segment SponsorBlock knows, in the order shown in Settings. */
        val CATEGORIES = listOf(
            "sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic", "filler", "poi_highlight",
        )

        /** Names people understand, for Settings and the "Skipped …" message. */
        val NAMES = mapOf(
            "sponsor" to "Sponsor",
            "selfpromo" to "Self-promotion",
            "interaction" to "Subscribe reminder",
            "intro" to "Intro",
            "outro" to "Endcards and credits",
            "preview" to "Preview or recap",
            "music_offtopic" to "Non-music part of a music video",
            "filler" to "Filler and jokes",
            "poi_highlight" to "Highlight",
        )

        /** What happens when nothing was chosen: the original three are skipped, the rest left alone. */
        fun defaultAction(category: String) = when (category) {
            "sponsor", "selfpromo", "interaction" -> "skip"
            "poi_highlight" -> "ask"
            else -> "off"
        }

        /** "skip", "ask" or "off" for [category], from the user's choices. */
        fun action(choices: Map<String, String>, category: String) = choices[category] ?: defaultAction(category)

        fun videoId(url: String): String? =
            Regex("""(?:v=|youtu\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)
    }
}
