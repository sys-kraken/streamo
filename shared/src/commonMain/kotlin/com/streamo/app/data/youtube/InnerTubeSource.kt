package com.streamo.app.data.youtube

import com.streamo.app.data.ChannelPage
import com.streamo.app.data.ChannelRef
import com.streamo.app.data.DownloadOption
import com.streamo.app.data.PlaylistPage
import com.streamo.app.data.SearchPage
import com.streamo.app.data.StreamChoice
import com.streamo.app.data.VideoDetails
import com.streamo.app.data.VideoItem
import com.streamo.app.data.VideoSource
import com.streamo.app.data.youtube.YouTubeParse.channelId
import com.streamo.app.data.youtube.YouTubeParse.channelUrl
import com.streamo.app.data.youtube.YouTubeParse.count
import com.streamo.app.data.youtube.YouTubeParse.nextToken
import com.streamo.app.data.youtube.YouTubeParse.videoId
import com.streamo.app.data.youtube.YouTubeParse.videoUrl
import com.streamo.app.data.youtube.YouTubeParse.videosIn
import com.streamo.app.nowMillis
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * A [VideoSource] that talks to YouTube directly, with no NewPipe and no in-between server.
 * Used on iOS; Android keeps NewPipe. Video lookups are cached and shared like NewPipeRepo's,
 * so a prefetched video opens instantly.
 *
 * Streams: [StreamChoice.videoUrl]/[StreamChoice.audioUrl] are separate picture and sound files
 * (H.264/AAC preferred), and [StreamChoice.combinedUrl] is YouTube's HLS playlist when it offers
 * one ([StreamChoice.isHls]), which Apple's player can play directly.
 */
class InnerTubeSource(http: HttpClient, private val country: () -> String) : VideoSource {
    private val api = YouTubeApi(http, country)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The two answers that make up a video: playback info (iPhone client) and its page (website). */
    private class VideoInfo(val player: JsonElement, val page: JsonElement)

    private class Cached(val info: Deferred<VideoInfo>, val createdAt: Long)

    private val cache = MutableStateFlow<Map<String, Cached>>(emptyMap())

    // ---------- Videos ----------

    override fun prefetch(url: String) {
        infoFor(url)
    }

    override suspend fun details(url: String, maxHeight: Int): VideoDetails {
        val info = infoFor(url).await()
        val primary = info.page.findFirst("videoPrimaryInfoRenderer")
        val owner = info.page.findFirst("videoOwnerRenderer")
        val related = info.page["contents"]["twoColumnWatchNextResults"]["secondaryResults"]["secondaryResults"]["results"].list
        return VideoDetails(
            item = item(info),
            description = asHtml(info.page.findFirst("attributedDescription")["content"].str.orEmpty()),
            likes = count(primary.findFirst("likeButtonViewModel").findFirst("buttonViewModel")["title"].str),
            channelAvatar = owner["thumbnail"].thumbs().pick(88),
            channelSubscribers = count(owner["subscriberCountText"].text),
            related = videosIn(related),
            streams = streams(info.player, maxHeight),
        )
    }

    /** Drop a cached lookup, e.g. after the stream links stopped working. */
    fun invalidate(url: String) {
        cache.update { it - url }
    }

    private fun infoFor(url: String): Deferred<VideoInfo> {
        val now = nowMillis()
        cache.value[url]?.let { cached ->
            val failed = cached.info.isCompleted && cached.info.getCompletionExceptionOrNull() != null
            if (!failed && now - cached.createdAt < INFO_TTL_MS) return cached.info
        }
        val id = videoId(url) ?: throw VideoUnavailable("Not a YouTube video link.")
        val fresh = Cached(
            scope.async {
                coroutineScope {
                    val player = async { api.iosPlayer(id) }
                    val page = async { api.web("next", mapOf("videoId" to id)) }
                    VideoInfo(player.await().also(::checkPlayable), page.await())
                }
            },
            now,
        )
        cache.update { it + (url to fresh) }
        return fresh.info
    }

    /** Turns YouTube's "can't play this" answers into errors the app explains properly. */
    private fun checkPlayable(player: JsonElement) {
        val status = player["playabilityStatus"]
        val state = status["status"].str ?: return
        if (state == "OK" || state == "LIVE_STREAM_OFFLINE") return
        val reason = status["reason"].str ?: status.findFirst("subreason").text ?: "This video isn't available."
        throw when {
            reason.contains("bot", ignoreCase = true) -> ReCaptchaRequired(reason)
            reason.contains("age", ignoreCase = true) || reason.contains("inappropriate", ignoreCase = true) -> AgeRestrictedVideo(reason)
            reason.contains("private", ignoreCase = true) -> PrivateVideo(reason)
            reason.contains("country", ignoreCase = true) -> GeographicRestriction(reason)
            else -> VideoUnavailable(reason)
        }
    }

    private fun item(info: VideoInfo): VideoItem {
        val d = info.player["videoDetails"]
        val primary = info.page.findFirst("videoPrimaryInfoRenderer")
        val owner = info.page.findFirst("videoOwnerRenderer")
        val id = d["videoId"].str.orEmpty()
        val live = d["isLive"].bool
        return VideoItem(
            url = videoUrl(id),
            title = d["title"].str ?: primary["title"].text.orEmpty(),
            channelName = d["author"].str ?: owner["title"].text.orEmpty(),
            channelUrl = d["channelId"].str?.let(::channelUrl),
            thumbnail = d["thumbnail"].thumbs().pick(720),
            durationSec = d["lengthSeconds"].long ?: 0,
            views = if (live) count(primary.findFirst("viewCount").text) else d["viewCount"].str?.toLongOrNull() ?: -1,
            uploaded = primary["relativeDateText"].text ?: primary["dateText"].text,
            isLive = live,
            channelAvatar = owner["thumbnail"].thumbs().pick(68),
        )
    }

    // ---------- Streams ----------

    private class Format(val url: String, val mime: String, val height: Int, val fps: Int, val bitrate: Long, val size: Long, val original: Boolean) {
        val isVideo get() = mime.startsWith("video/")
        val isAudio get() = mime.startsWith("audio/")
        val isH264 get() = mime.contains("avc1")
        val isM4a get() = mime.startsWith("audio/mp4")
    }

    private fun formats(player: JsonElement): List<Format> =
        player["streamingData"]["adaptiveFormats"].list.mapNotNull { f ->
            Format(
                url = f["url"].str ?: return@mapNotNull null, // scrambled links are skipped
                mime = f["mimeType"].str.orEmpty(),
                height = f["height"].long?.toInt() ?: 0,
                fps = f["fps"].long?.toInt() ?: 0,
                bitrate = f["averageBitrate"].long ?: f["bitrate"].long ?: 0,
                size = f["contentLength"].str?.toLongOrNull() ?: 0,
                // Prefer the original language over auto-dubbed sound tracks.
                original = f["audioTrack"].let { it == null || it["audioIsDefault"].bool },
            )
        }

    private fun streams(player: JsonElement, maxHeight: Int): StreamChoice {
        val hls = player["streamingData"]["hlsManifestUrl"].str
        if (player["videoDetails"]["isLive"].bool) {
            return StreamChoice(null, null, hls ?: throw VideoUnavailable("This live stream can't be played."), true, 0, emptyList())
        }
        val all = formats(player)
        val videos = all.filter { it.isVideo }
        val audio = rankAudio(all.filter { it.isAudio }).firstOrNull()
        val heights = videos.map { it.height }.filter { it > 0 }.distinct().sortedDescending()
        if (videos.isEmpty() || audio == null) {
            return StreamChoice(null, null, hls ?: throw VideoUnavailable("This video can't be played."), true, 0, heights)
        }
        val video = rankVideo(videos, maxHeight).first()
        return StreamChoice(video.url, audio.url, hls, hls != null, video.height, heights)
    }

    /** Best first: the highest quality up to [maxHeight]; H.264 (plays everywhere), then AV1, then VP9. */
    private fun rankVideo(videos: List<Format>, maxHeight: Int): List<Format> {
        val fitting = videos.filter { it.height <= maxHeight }.ifEmpty { listOf(videos.minBy { it.height }) }
        return fitting.sortedWith(
            compareByDescending<Format> { it.height }
                .thenBy { if (it.isH264) 0 else if (it.mime.contains("av01")) 1 else 2 }
                .thenByDescending { it.fps }
        )
    }

    private fun rankAudio(audio: List<Format>): List<Format> =
        audio.filter { it.original }.ifEmpty { audio }
            .sortedWith(compareBy<Format> { if (it.isM4a) 0 else 1 }.thenByDescending { it.bitrate })

    // ---------- Downloads ----------

    override suspend fun downloadOptions(url: String): List<DownloadOption> {
        val all = formats(infoFor(url).await().player)
        val audio = rankAudio(all.filter { it.isAudio && it.isM4a }).firstOrNull() ?: return emptyList()
        val videos = all.filter { it.isVideo && it.isH264 && it.mime.startsWith("video/mp4") }
            .groupBy { it.height }
            .map { (h, list) -> list.maxBy { it.fps } to h }
            .sortedByDescending { it.second }
            .map { (v, h) -> DownloadOption(false, h, if (v.size > 0) v.size + audio.size else 0) }
        return videos + DownloadOption(true, 0, audio.size)
    }

    // ---------- Search ----------

    override suspend fun search(query: String): SearchPage {
        val r = api.web("search", mapOf("query" to query))
        val sections = r["contents"]["twoColumnSearchResultsRenderer"]["primaryContents"]["sectionListRenderer"]["contents"].list
        return searchPage(sections)
    }

    override suspend fun searchMore(query: String, next: Any): SearchPage {
        val r = api.web("search", mapOf("continuation" to next.toString()))
        return searchPage(r.findAll("continuationItems").flatMap { it.list })
    }

    private fun searchPage(sections: List<JsonElement>): SearchPage {
        val items = sections.flatMap { it["itemSectionRenderer"]["contents"].list }
        return SearchPage(items.mapNotNull(YouTubeParse::searchResult), nextToken(sections))
    }

    override suspend fun suggestions(query: String): List<String> =
        runCatching { api.suggestions(query) }.getOrDefault(emptyList())

    // ---------- Home ----------

    /** The same Home tabs as Android, by NewPipe's ids. */
    override fun exploreLists(): List<String> = listOf("trending_music", "trending_gaming", "trending_movies_and_shows", "live")

    /** "For you": videos related to the last few things watched, mixed together. */
    override suspend fun forYou(history: List<VideoItem>): List<VideoItem> = coroutineScope {
        val watched = history.map { it.url }.toSet()
        val lists = history.take(4).map { v ->
            async { runCatching { details(v.url, 0).related.filter { !it.isLive } }.getOrDefault(emptyList()) }
        }.awaitAll()
        // Interleave so the feed isn't all about one video.
        val mixed = mutableListOf<VideoItem>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { it.getOrNull(i)?.let(mixed::add) }
        mixed.filter { it.url !in watched }.distinctBy { it.url }
    }

    override suspend fun explore(kioskId: String): List<VideoItem> = when (kioskId) {
        "trending_music" -> chart("TRENDING_VIDEOS")
        "trending_movies_and_shows" -> chart("TRENDING_MOVIES")
        "trending_gaming" -> api.web("browse", mapOf("browseId" to GAMING_CHANNEL, "params" to GAMING_TRENDING))
            .findAll("gridVideoRenderer").mapNotNull(YouTubeParse::video)
        "live" -> api.web("browse", mapOf("browseId" to LIVE_CHANNEL, "params" to LIVE_NOW))
            .findAll("videoRenderer").mapNotNull(YouTubeParse::video)
        else -> emptyList()
    }

    /** A YouTube Charts list for the phone's country, or the US one where that country has none. */
    private suspend fun chart(type: String): List<VideoItem> {
        val local = country().takeIf { it.length == 2 }?.let { cc -> runCatching { chartItems(type, cc) }.getOrNull() }
        return local?.takeIf { it.isNotEmpty() } ?: chartItems(type, "US")
    }

    private suspend fun chartItems(type: String, countryCode: String): List<VideoItem> =
        api.chart(type, countryCode).findFirst("videoViews").list.mapNotNull { v ->
            val id = v["id"].str ?: return@mapNotNull null
            VideoItem(
                url = videoUrl(id),
                title = v["title"].str.orEmpty(),
                channelName = v["channelName"].str ?: v["artists"].list.mapNotNull { it["name"].str }.joinToString(", "),
                channelUrl = v["externalChannelId"].str?.let(::channelUrl),
                thumbnail = v["thumbnail"].thumbs().pick(360),
                durationSec = v["videoDuration"].long ?: 0,
                views = -1,
                uploaded = null,
            )
        }

    /** Latest uploads from all subscribed channels, newest first, from YouTube's RSS feeds. */
    override suspend fun subscriptionFeed(channels: List<ChannelRef>): List<VideoItem> = coroutineScope {
        channels.map { ch ->
            async {
                runCatching {
                    val id = channelId(ch.url) ?: return@runCatching emptyList()
                    feedItems(api.channelFeed(id)).map { it.copy(channelName = ch.name, channelUrl = ch.url, channelAvatar = ch.avatar) }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
            .distinctBy { it.url }
            .sortedByDescending { it.uploadedEpochMs ?: 0L }
    }

    private fun feedItems(xml: String): List<VideoItem> =
        Regex("""<entry>([\s\S]*?)</entry>""").findAll(xml).mapNotNull { m ->
            val e = m.groupValues[1]
            fun tag(name: String) = Regex("""<$name>([\s\S]*?)</$name>""").find(e)?.groupValues?.get(1)
            val id = tag("yt:videoId") ?: return@mapNotNull null
            // Shorts show up in feeds too; their links say so.
            if (e.contains("/shorts/")) return@mapNotNull null
            val epoch = tag("published")?.let(YouTubeParse::isoTime)
            VideoItem(
                url = videoUrl(id),
                title = xmlText(tag("title").orEmpty()),
                channelName = "",
                channelUrl = null,
                thumbnail = Regex("""<media:thumbnail url="([^"]+)"""").find(e)?.groupValues?.get(1),
                durationSec = 0,
                views = Regex("""<media:statistics views="(\d+)"""").find(e)?.groupValues?.get(1)?.toLongOrNull() ?: -1,
                uploaded = epoch?.let(YouTubeParse::timeAgo),
                uploadedEpochMs = epoch,
            )
        }.toList()

    // ---------- Channels ----------

    override suspend fun channel(url: String): ChannelPage {
        val id = browseIdFor(url)
        val r = api.web("browse", mapOf("browseId" to id, "params" to CHANNEL_VIDEOS))
        val header = r.findFirst("pageHeaderViewModel")
        val meta = r.findFirst("channelMetadataRenderer")
        val name = header["title"].findFirst("content").str ?: meta["title"].str.orEmpty()
        val avatar = header["image"].findFirst("avatarViewModel")["image"].thumbs().ifEmpty { meta["avatar"].thumbs() }
        val channel = ChannelRef(channelUrl(id), name, avatar.pick(176))
        val videos = videosIn(r.findAll("richItemRenderer")).map {
            it.copy(
                channelName = it.channelName.ifEmpty { name },
                channelUrl = it.channelUrl ?: channel.url,
                channelAvatar = it.channelAvatar ?: avatar.pick(68),
            )
        }
        return ChannelPage(
            channel = channel,
            banner = header["banner"].findFirst("image").thumbs().pick(400),
            subscribers = count(header.findAll("content").mapNotNull { it.str }.firstOrNull { it.contains("subscriber") }),
            description = meta["description"].str.orEmpty(),
            videos = videos,
        )
    }

    override suspend fun channelAvatar(url: String): String? {
        val r = api.web("browse", mapOf("browseId" to browseIdFor(url)))
        val avatar = r.findFirst("pageHeaderViewModel")["image"].findFirst("avatarViewModel")["image"].thumbs()
            .ifEmpty { r.findFirst("channelMetadataRenderer")["avatar"].thumbs() }
        return avatar.pick(68)
    }

    /** The channel id ("UC...") for a channel link, asking YouTube for @handle links. */
    private suspend fun browseIdFor(url: String): String =
        channelId(url) ?: api.web("navigation/resolve_url", mapOf("url" to url))
            .findFirst("browseEndpoint")["browseId"].str
        ?: throw VideoUnavailable("This channel isn't available.")

    // ---------- Playlists ----------

    override suspend fun playlist(url: String): PlaylistPage {
        val id = YouTubeParse.playlistId(url) ?: throw VideoUnavailable("Not a YouTube playlist link.")
        val r = api.web("browse", mapOf("browseId" to "VL$id"))
        val header = r.findFirst("pageHeaderViewModel")
        // Each part is a text, or the owner's picture with "by <name>" next to it.
        val parts = header.findAll("metadataParts").flatMap { it.list }.mapNotNull { it.findFirst("content").str }
        val items = playlistItems(r)
        val videos = videosIn(items)
        return PlaylistPage(
            name = header["title"].findFirst("content").str ?: r.findFirst("playlistHeaderRenderer")["title"].text.orEmpty(),
            uploader = parts.firstOrNull { it.startsWith("by ") }?.removePrefix("by ").orEmpty(),
            thumbnail = header["heroImage"].findFirst("image").thumbs().pick(360) ?: videos.firstOrNull()?.thumbnail,
            count = count(parts.firstOrNull { it.endsWith(" videos") || it.endsWith(" video") }).takeIf { it >= 0 } ?: videos.size.toLong(),
            videos = videos,
            next = nextToken(items),
        )
    }

    override suspend fun playlistMore(url: String, next: Any): Pair<List<VideoItem>, Any?> {
        val items = api.web("browse", mapOf("continuation" to next.toString())).findAll("continuationItems").flatMap { it.list }
        return videosIn(items) to nextToken(items)
    }

    /** The playlist's own list of videos (not the "you may also like" shelves). */
    private fun playlistItems(r: JsonElement): List<JsonElement> =
        r.findAll("playlistVideoListRenderer").firstOrNull()?.get("contents")?.list
            ?: r.findAll("itemSectionRenderer").flatMap { it["contents"].list }
                .flatMap { it["playlistVideoListRenderer"]["contents"].list.ifEmpty { listOf(it) } }

    // ---------- Helpers ----------

    /** The app shows descriptions through htmlToText, so plain text is escaped as simple HTML. */
    private fun asHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    private fun xmlText(text: String): String =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    private companion object {
        const val INFO_TTL_MS = 60 * 60 * 1000L // stream links stay valid for a few hours
        const val CHANNEL_VIDEOS = "EgZ2aWRlb3PyBgQKAjoA"
        const val GAMING_CHANNEL = "UCOpNcN46UbXVtpKMrmU4Abg"
        const val GAMING_TRENDING = "Egh0cmVuZGluZw=="
        const val LIVE_CHANNEL = "UC4R8DWoMoI7CAwX8_LjQHig"
        const val LIVE_NOW = "EgdsaXZldGFikgEDCKEK"
    }
}
