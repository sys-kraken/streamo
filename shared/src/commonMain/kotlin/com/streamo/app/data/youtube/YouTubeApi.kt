package com.streamo.app.data.youtube

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Talks to YouTube's own internal API ("InnerTube"), the one its website and apps use.
 * Client names and versions follow NewPipeExtractor, which keeps them current.
 * Texts are always requested in English: the parsers read words like "views" and "ago".
 */
internal class YouTubeApi(private val http: HttpClient, private val country: () -> String) {

    private val json = Json { ignoreUnknownKeys = true }

    /** YouTube's website: search, video pages, channels, playlists. */
    suspend fun web(endpoint: String, body: Map<String, Any>): JsonElement =
        request(
            url = "https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false",
            client = mapOf("clientName" to "WEB", "clientVersion" to WEB_VERSION),
            body = body,
            headers = mapOf(
                "User-Agent" to WEB_USER_AGENT,
                "X-YouTube-Client-Name" to "1",
                "X-YouTube-Client-Version" to WEB_VERSION,
                "Origin" to "https://www.youtube.com",
            ),
        )

    /**
     * Playback info as the YouTube iPhone app gets it: direct stream links (no scrambled
     * signatures) and an HLS playlist that Apple's player plays natively.
     */
    suspend fun iosPlayer(videoId: String): JsonElement =
        request(
            url = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false",
            client = mapOf(
                "clientName" to "IOS",
                "clientVersion" to IOS_VERSION,
                "deviceMake" to "Apple",
                "deviceModel" to IOS_DEVICE,
                "osName" to "iPhone",
                "osVersion" to IOS_OS_VERSION,
            ),
            body = mapOf("videoId" to videoId, "contentCheckOk" to true, "racyCheckOk" to true),
            headers = mapOf(
                "User-Agent" to IOS_USER_AGENT,
                "X-Goog-Api-Format-Version" to "2",
                "X-YouTube-Client-Name" to "5",
                "X-YouTube-Client-Version" to IOS_VERSION,
            ),
        )

    /** A chart from charts.youtube.com, e.g. type "TRENDING_VIDEOS" for country "US". */
    suspend fun chart(type: String, countryCode: String): JsonElement =
        request(
            url = "https://charts.youtube.com/youtubei/v1/browse?alt=json&prettyPrint=false",
            client = mapOf("clientName" to "WEB_MUSIC_ANALYTICS", "clientVersion" to "2.0"),
            body = mapOf(
                "browseId" to "FEmusic_analytics_charts_home",
                "query" to "perspective=CHART_DETAILS&chart_params_country_code=$countryCode&chart_params_chart_type=$type",
            ),
            headers = mapOf("User-Agent" to WEB_USER_AGENT, "Origin" to "https://charts.youtube.com"),
        )

    /** Search-box suggestions, as on youtube.com. */
    suspend fun suggestions(query: String): List<String> {
        val response = http.get("https://suggestqueries-clients6.youtube.com/complete/search") {
            parameter("client", "youtube")
            parameter("ds", "yt")
            parameter("gl", country().ifEmpty { "US" })
            parameter("q", query)
            parameter("xhr", "t")
            header("User-Agent", WEB_USER_AGENT)
        }
        // ["query", [["suggestion", 0, [...]], ...]]
        return json.parseToJsonElement(response.checked().bodyAsText())[1].list.mapNotNull { it[0].str }
    }

    /** A channel's latest uploads as YouTube's public RSS feed (XML). */
    suspend fun channelFeed(channelId: String): String =
        http.get("https://www.youtube.com/feeds/videos.xml") {
            parameter("channel_id", channelId)
            header("User-Agent", WEB_USER_AGENT)
        }.checked().bodyAsText()

    private suspend fun request(
        url: String,
        client: Map<String, String>,
        body: Map<String, Any>,
        headers: Map<String, String>,
    ): JsonElement {
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    client.forEach { (k, v) -> put(k, v) }
                    put("hl", "en")
                    put("gl", country().ifEmpty { "US" })
                }
            }
            body.forEach { (k, v) -> put(k, if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString())) }
        }
        val response = http.post(url) {
            headers.forEach { (k, v) -> header(k, v) }
            setBody(TextContent(payload.toString(), ContentType.Application.Json))
        }
        return json.parseToJsonElement(response.checked().bodyAsText()) as? JsonObject
            ?: throw IllegalStateException("YouTube sent something unexpected.")
    }

    private fun HttpResponse.checked(): HttpResponse {
        if (status.value == 429) throw ReCaptchaRequired("YouTube is asking to slow down.")
        if (!status.isSuccess()) throw IllegalStateException("YouTube answered ${status.value}.")
        return this
    }

    companion object {
        const val WEB_VERSION = "2.20260120.01.00"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        const val IOS_VERSION = "21.03.2"
        const val IOS_DEVICE = "iPhone16,2"
        const val IOS_OS_VERSION = "18.7.2.22H124"
        const val IOS_USER_AGENT = "com.google.ios.youtube/$IOS_VERSION($IOS_DEVICE; U; CPU iOS 18_7_2 like Mac OS X; US)"
    }
}

// Error types. The app turns them into messages by their names (see friendlyError).
class ReCaptchaRequired(message: String) : Exception(message)
class AgeRestrictedVideo(message: String) : Exception(message)
class PrivateVideo(message: String) : Exception(message)
class GeographicRestriction(message: String) : Exception(message)
class VideoUnavailable(message: String) : Exception(message)
