package com.streamo.app.data.youtube

import com.streamo.app.data.ChannelAvatars
import com.streamo.app.data.ChannelRef
import com.streamo.app.data.PlaylistRef
import com.streamo.app.data.SearchResult
import com.streamo.app.data.VideoItem
import com.streamo.app.nowMillis
import kotlinx.serialization.json.JsonElement

/** Turns YouTube's item formats (old "renderers" and newer "lockups") into Streamo's models. */
internal object YouTubeParse {

    fun videoUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    fun channelUrl(id: String) = "https://www.youtube.com/channel/$id"
    fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"

    fun videoId(url: String): String? =
        Regex("""(?:v=|youtu\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)

    fun channelId(url: String): String? = Regex("""/channel/(UC[A-Za-z0-9_-]{22})""").find(url)?.groupValues?.get(1)

    fun playlistId(url: String): String? = Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)

    // ---------- Numbers in text ----------

    /**
     * A count from text like "15,941,893 views", "1.6M", "148 thousand views", "No views" or
     * "7,862 watching". -1 when there's no number.
     */
    fun count(text: String?): Long {
        if (text.isNullOrBlank()) return -1
        if (text.startsWith("No ", ignoreCase = true)) return 0
        val m = Regex("""([\d][\d,.]*)\s*([KMB]|thousand|million|billion)?""", RegexOption.IGNORE_CASE).find(text) ?: return -1
        val number = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return -1
        val factor = when (m.groupValues[2].lowercase()) {
            "k", "thousand" -> 1e3
            "m", "million" -> 1e6
            "b", "billion" -> 1e9
            else -> 1.0
        }
        return (number * factor).toLong()
    }

    /** Seconds from "1:04:42" or "3:14"; 0 when it isn't a time. */
    fun duration(text: String?): Long {
        val parts = text?.trim()?.split(":")?.map { it.toLongOrNull() ?: return 0 } ?: return 0
        if (parts.size !in 2..3) return 0
        return parts.fold(0L) { acc, p -> acc * 60 + p }
    }

    // ---------- Videos ----------

    /** `videoRenderer`, `gridVideoRenderer` and `compactVideoRenderer` all look alike. */
    fun video(v: JsonElement): VideoItem? {
        val id = v["videoId"].str ?: return null
        val byline = (v["longBylineText"] ?: v["shortBylineText"] ?: v["ownerText"])["runs"][0]
        val channelId = byline["navigationEndpoint"]["browseEndpoint"]["browseId"].str
        val overlay = v["thumbnailOverlays"].findFirst("thumbnailOverlayTimeStatusRenderer")
        val live = overlay["style"].str == "LIVE" ||
            v["badges"].findAll("style").any { it.str == "BADGE_STYLE_TYPE_LIVE_NOW" }
        val avatar = (v["channelThumbnailSupportedRenderers"].findFirst("thumbnail") ?: v["channelThumbnail"]).thumbs()
            .ifEmpty { v["avatar"].findFirst("avatarViewModel")["image"].thumbs() }
        return VideoItem(
            url = videoUrl(id),
            title = v["title"].text.orEmpty(),
            channelName = byline["text"].str.orEmpty(),
            channelUrl = channelId?.let(::channelUrl),
            thumbnail = v["thumbnail"].thumbs().pick(360),
            durationSec = duration(v["lengthText"].text ?: overlay["text"].text),
            views = count(v["viewCountText"].text ?: v["shortViewCountText"].text),
            uploaded = v["publishedTimeText"].text,
            isLive = live,
            channelAvatar = avatar.pick(68),
        ).remembered()
    }

    /** A video in YouTube's newer "lockup" format (related videos, channel pages, playlists). */
    fun lockupVideo(l: JsonElement): VideoItem? {
        if (l["contentType"].str != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = l["contentId"].str ?: return null
        val meta = l["metadata"]["lockupMetadataViewModel"]
        val avatar = meta["image"]["decoratedAvatarViewModel"]
        val badges = l["contentImage"].findAll("thumbnailBadgeViewModel")
        var channel: String? = null
        var views = -1L
        var uploaded: String? = null
        meta["metadata"]["contentMetadataViewModel"]["metadataRows"].list.forEach { row ->
            row["metadataParts"].list.forEach { part ->
                val shown = part["text"]["content"].str?.trim().orEmpty()
                val full = part["accessibilityLabel"].str ?: shown
                when {
                    full.contains("view", ignoreCase = true) || full.contains("watching", ignoreCase = true) -> views = count(full)
                    full.endsWith(" ago") || full.startsWith("Streamed") || full.startsWith("Premiere") -> uploaded = full
                    channel == null && shown.isNotEmpty() -> channel = shown
                }
            }
        }
        return VideoItem(
            url = videoUrl(id),
            title = meta["title"]["content"].str.orEmpty(),
            channelName = channel.orEmpty(),
            channelUrl = avatar.findFirst("browseEndpoint")["browseId"].str?.let(::channelUrl),
            thumbnail = l["contentImage"].findFirst("image").thumbs().pick(360),
            durationSec = badges.firstNotNullOfOrNull { duration(it["text"].str).takeIf { s -> s > 0 } } ?: 0,
            views = views,
            uploaded = uploaded,
            isLive = badges.any { it["text"].str == "LIVE" || it["badgeStyle"].str.orEmpty().contains("LIVE") },
            channelAvatar = avatar.findFirst("avatarViewModel")["image"].thumbs().pick(68),
        ).remembered()
    }

    /** Any video in [items], whichever format each one comes in. Shorts are left out. */
    fun videosIn(items: List<JsonElement>): List<VideoItem> = items.mapNotNull { item ->
        // Grid items come wrapped: {"richItemRenderer": {"content": {...}}}, or already unwrapped to {"content": ...}.
        val inner = item["richItemRenderer"]["content"] ?: item["content"] ?: item
        inner["videoRenderer"]?.takeUnless(::isShort)?.let(::video)
            ?: inner["gridVideoRenderer"]?.let(::video)
            ?: inner["compactVideoRenderer"]?.let(::video)
            ?: inner["playlistVideoRenderer"]?.let(::video)
            ?: inner["lockupViewModel"]?.let(::lockupVideo)
    }

    private fun isShort(v: JsonElement) = v["navigationEndpoint"]["reelWatchEndpoint"] != null

    // ---------- Search results ----------

    fun searchResult(item: JsonElement): SearchResult? {
        item["videoRenderer"]?.let { v -> return if (isShort(v)) null else video(v)?.let { SearchResult.Video(it) } }
        item["channelRenderer"]?.let { return channelResult(it) }
        item["officialCardViewModel"]?.let { return officialCard(it) }
        item["playlistRenderer"]?.let { return playlistResult(it) }
        item["lockupViewModel"]?.let { l ->
            lockupVideo(l)?.let { return SearchResult.Video(it) }
            lockupPlaylist(l)?.let { return SearchResult.Playlist(it) }
        }
        return null
    }

    private fun channelResult(c: JsonElement): SearchResult? {
        val id = c["channelId"].str ?: return null
        // Newer layouts put the @handle where the subscriber count used to be.
        val subs = listOf(c["subscriberCountText"].text, c["videoCountText"].text)
            .firstOrNull { it?.contains("subscriber") == true }
        return SearchResult.Channel(ChannelRef(channelUrl(id), c["title"].text.orEmpty(), c["thumbnail"].thumbs().pick(88)), count(subs))
    }

    /** The big channel card at the top of a search for that channel. */
    private fun officialCard(card: JsonElement): SearchResult? {
        val header = card.findFirst("pageHeaderViewModel") ?: card["header"]
        val id = header.findFirst("browseEndpoint")["browseId"].str?.takeIf { it.startsWith("UC") } ?: return null
        val name = header["title"].findFirst("content").str ?: return null
        val subs = header.findAll("content").mapNotNull { it.str }.firstOrNull { it.contains("subscriber") }
        val avatar = header["image"].findFirst("image").thumbs().ifEmpty { card.findFirst("contentPreviewImageViewModel")["image"].thumbs() }
        return SearchResult.Channel(ChannelRef(channelUrl(id), name, avatar.pick(88)), count(subs))
    }

    private fun playlistResult(p: JsonElement): SearchResult? {
        val id = p["playlistId"].str ?: return null
        return SearchResult.Playlist(
            PlaylistRef(
                url = playlistUrl(id),
                name = p["title"].text.orEmpty(),
                uploader = (p["longBylineText"] ?: p["shortBylineText"]).text.orEmpty(),
                count = p["videoCount"].str?.toLongOrNull() ?: count(p["videoCountText"].text),
                thumbnail = (p["thumbnails"][0] ?: p["thumbnail"]).thumbs().pick(180),
            )
        )
    }

    /** A playlist in the lockup format. Mixes ("RD...") are endless and left out. */
    fun lockupPlaylist(l: JsonElement): PlaylistRef? {
        if (l["contentType"].str != "LOCKUP_CONTENT_TYPE_PLAYLIST") return null
        val id = l["contentId"].str?.takeUnless { it.startsWith("RD") } ?: return null
        val meta = l["metadata"]["lockupMetadataViewModel"]
        val parts = meta.findAll("metadataParts").flatMap { it.list }.mapNotNull { it["text"]["content"].str }
        val countText = l["contentImage"].findAll("thumbnailBadgeViewModel").mapNotNull { it["text"].str }
            .firstOrNull { it.contains("video") || it.contains("episode") }
        return PlaylistRef(
            url = playlistUrl(id),
            name = meta["title"]["content"].str.orEmpty(),
            uploader = parts.firstOrNull { it != "Playlist" && !it.contains("video") && !it.contains("View full") }.orEmpty(),
            count = count(countText),
            thumbnail = l["contentImage"].findFirst("image").thumbs().pick(180),
        )
    }

    /** The token that loads the next page of a list, if there is one. */
    fun nextToken(items: List<JsonElement>): String? {
        val more = items.lastOrNull { it["continuationItemRenderer"] != null || it["continuationItemViewModel"] != null }
            ?.let { it["continuationItemRenderer"] ?: it["continuationItemViewModel"] } ?: return null
        return (more.findFirst("continuationCommand")["token"] ?: more.findFirst("token")).str
    }

    // ---------- Dates ----------

    /** Milliseconds since 1970 from an ISO time like "2026-10-15T15:59:55+00:00". */
    fun isoTime(text: String): Long? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})""").find(text) ?: return null
        val (y, mo, d, h, mi, s, zone) = m.destructured
        val offsetMin = if (zone == "Z") 0 else zone.substring(1, 3).toInt() * 60 + zone.substring(4, 6).toInt()
        val sign = if (zone.startsWith("-")) -1 else 1
        val days = daysFromCivil(y.toInt(), mo.toInt(), d.toInt())
        val seconds = days * 86_400L + h.toInt() * 3600 + mi.toInt() * 60 + s.toInt() - sign * offsetMin * 60L
        return seconds * 1000
    }

    /** Days since 1970-01-01 for a calendar date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468
    }

    /** "4 years ago", the way lists show dates. */
    fun timeAgo(epochMs: Long): String {
        val minutes = (nowMillis() - epochMs) / 60_000
        fun ago(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
        return when {
            minutes < 60 -> ago(minutes.coerceAtLeast(1), "minute")
            minutes < 60 * 24 -> ago(minutes / 60, "hour")
            minutes < 60 * 24 * 7 -> ago(minutes / (60 * 24), "day")
            minutes < 60 * 24 * 30 -> ago(minutes / (60 * 24 * 7), "week")
            minutes < 60 * 24 * 365 -> ago(minutes / (60 * 24 * 30), "month")
            else -> ago(minutes / (60 * 24 * 365), "year")
        }
    }

    private fun VideoItem.remembered() = also { ChannelAvatars.remember(channelUrl, channelAvatar) }
}
