package com.streamo.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's [VideoSource], built on NewPipeExtractor. All YouTube access goes through here. Video lookups are cached and de-duplicated so
 * a video that was prefetched (e.g. on touch-down) starts instantly when opened.
 */
object NewPipeRepo : VideoSource {
    private val yt get() = ServiceList.YouTube
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class CachedInfo(val deferred: Deferred<StreamInfo>, val createdAt: Long)

    private val infoCache = ConcurrentHashMap<String, CachedInfo>()
    private const val INFO_TTL_MS = 60 * 60 * 1000L // stream URLs stay valid for a few hours

    // ---------- Videos ----------

    /** Start looking up a video in the background; cheap to call repeatedly. */
    override fun prefetch(url: String) {
        infoFor(url)
    }

    override suspend fun details(url: String, maxHeight: Int): VideoDetails {
        val info = infoFor(url).await()
        return VideoDetails(
            item = info.toVideoItem(),
            description = info.description?.content.orEmpty(),
            likes = info.likeCount,
            channelAvatar = info.uploaderAvatars.pick(88),
            channelSubscribers = info.uploaderSubscriberCount,
            related = info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toVideoItem() },
            streams = chooseStreams(info, maxHeight),
        )
    }

    /**
     * The streams to actually play. YouTube refuses a few percent of stream links outright (403),
     * so the picked picture and sound links are tested first (in parallel, a tiny request each)
     * and an equally good alternative is used if one is refused.
     */
    suspend fun streams(url: String, maxHeight: Int): Pair<VideoItem, StreamChoice> {
        val info = infoFor(url).await()
        val choice = chooseStreams(info, maxHeight)
        if (choice.videoUrl == null || choice.audioUrl == null) return info.toVideoItem() to choice

        val (video, audio) = coroutineScope {
            val v = async(Dispatchers.IO) { firstWorking(rankVideo(playableVideo(info), maxHeight).map { it.content to it.height }) }
            val a = async(Dispatchers.IO) { firstWorking(rankAudio(playableAudio(info)).map { it.content to 0 }) }
            v.await() to a.await()
        }
        return info.toVideoItem() to choice.copy(
            videoUrl = video?.first ?: choice.videoUrl,
            audioUrl = audio?.first ?: choice.audioUrl,
            height = video?.second ?: choice.height,
        )
    }

    /** First link (of the best few) that YouTube doesn't refuse. */
    private fun <T> firstWorking(candidates: List<Pair<String, T>>): Pair<String, T>? =
        candidates.take(4).firstOrNull { (link, _) ->
            runCatching {
                NewPipe.getDownloader().get(link, mapOf("Range" to listOf("bytes=0-1"))).responseCode() != 403
            }.getOrDefault(true) // network hiccup: let the player try it anyway
        }

    /** Drop a cached lookup, e.g. after the stream URLs stopped working. */
    fun invalidate(url: String) {
        infoCache.remove(url)
    }

    private fun infoFor(url: String): Deferred<StreamInfo> {
        val now = System.currentTimeMillis()
        infoCache[url]?.let { cached ->
            val failed = cached.deferred.isCompleted && cached.deferred.getCompletionExceptionOrNull() != null
            if (!failed && now - cached.createdAt < INFO_TTL_MS) return cached.deferred
        }
        val fresh = CachedInfo(scope.async { StreamInfo.getInfo(yt, url) }, now)
        infoCache[url] = fresh
        return fresh.deferred
    }

    private fun chooseStreams(info: StreamInfo, maxHeight: Int): StreamChoice {
        val isLive = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        if (isLive && !info.hlsUrl.isNullOrEmpty()) {
            return StreamChoice(null, null, info.hlsUrl, isHls = true, height = 0, availableHeights = emptyList())
        }

        val videoOnly = playableVideo(info)
        val audio = rankAudio(playableAudio(info)).firstOrNull()
        val heights = videoOnly.map { it.height }.filter { it > 0 }.distinct().sortedDescending()

        if (videoOnly.isNotEmpty() && audio != null) {
            val video = rankVideo(videoOnly, maxHeight).first()
            return StreamChoice(video.content, audio.content, null, false, video.height, heights)
        }

        // Fallback: a single file with both picture and sound (usually 360p).
        val muxed = info.videoStreams.filter { it.isPlayableUrl() }.maxByOrNull { it.height }
        if (muxed != null) {
            return StreamChoice(null, null, muxed.content, false, muxed.height, listOf(muxed.height))
        }
        if (!info.hlsUrl.isNullOrEmpty()) {
            return StreamChoice(null, null, info.hlsUrl, true, 0, emptyList())
        }
        throw IllegalStateException("This video can't be played")
    }

    private fun playableVideo(info: StreamInfo) = info.videoOnlyStreams.filter { it.isPlayableUrl() }
    private fun playableAudio(info: StreamInfo) = info.audioStreams.filter { it.isPlayableUrl() }

    /** Best first: the highest quality up to [maxHeight], then the next lower ones as backups. */
    private fun rankVideo(streams: List<VideoStream>, maxHeight: Int): List<VideoStream> {
        val fitting = streams.filter { it.height <= maxHeight }.ifEmpty { listOf(streams.minBy { it.height }) }
        // H.264 (MP4) is hardware-decoded everywhere and easiest on the battery; VP9 (WebM) next.
        return fitting.sortedWith(
            compareByDescending<VideoStream> { it.height }
                .thenBy { formatRank(it.format) }
                .thenByDescending { it.fps }
        )
    }

    private fun rankAudio(streams: List<AudioStream>): List<AudioStream> {
        // Prefer the original language track over auto-dubbed ones.
        val original = streams.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { streams }
        return original.sortedWith(
            compareBy<AudioStream> { if (it.format == MediaFormat.M4A) 0 else 1 }
                .thenByDescending { it.averageBitrate }
        )
    }

    private fun formatRank(format: MediaFormat?) = when (format) {
        MediaFormat.MPEG_4 -> 0
        MediaFormat.WEBM -> 1
        else -> 2
    }

    private fun org.schabi.newpipe.extractor.stream.Stream.isPlayableUrl() =
        isUrl && deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP

    // ---------- Search ----------

    override suspend fun search(query: String): SearchPage = io {
        val handler = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.ALL), "")
        val info = SearchInfo.getInfo(yt, handler)
        SearchPage(info.relatedItems.mapNotNull { it.toSearchResult() }, info.nextPage)
    }

    override suspend fun searchMore(query: String, next: Any): SearchPage = io {
        val handler = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.ALL), "")
        val page = SearchInfo.getMoreItems(yt, handler, next as Page)
        SearchPage(page.items.mapNotNull { it.toSearchResult() }, page.nextPage)
    }

    override suspend fun suggestions(query: String): List<String> = io {
        runCatching { yt.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    private fun InfoItem.toSearchResult(): SearchResult? = when (this) {
        is StreamInfoItem -> if (isShortFormContent) null else SearchResult.Video(toVideoItem())
        is ChannelInfoItem -> SearchResult.Channel(ChannelRef(url, name, thumbnails.pick(88)), subscriberCount)
        is PlaylistInfoItem -> SearchResult.Playlist(PlaylistRef(url, name, uploaderName.orEmpty(), streamCount, thumbnails.pick(180)))
        else -> null
    }

    // ---------- Home / explore ----------

    /**
     * YouTube's explore lists that still work, in display order. YouTube shut down its old
     * Trending page, and the podcasts list currently fails to load, so those are left out.
     */
    override fun exploreLists(): List<String> {
        val available = yt.kioskList.availableKiosks
        return listOf("trending_music", "trending_gaming", "trending_movies_and_shows", "live").filter { it in available }
    }

    /** "For you": videos related to the last few things watched, mixed together. */
    override suspend fun forYou(history: List<VideoItem>): List<VideoItem> = coroutineScope {
        val watched = history.map { it.url }.toSet()
        val lists = history.take(4).map { v ->
            async {
                runCatching {
                    infoFor(v.url).await().relatedItems.filterIsInstance<StreamInfoItem>()
                        .filter { !it.isShortFormContent }
                        .map { it.toVideoItem() }
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
        // Interleave so the feed isn't all about one video.
        val mixed = mutableListOf<VideoItem>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { it.getOrNull(i)?.let(mixed::add) }
        mixed.filter { it.url !in watched }.distinctBy { it.url }
    }

    override suspend fun explore(kioskId: String): List<VideoItem> = io {
        // Some charts don't exist everywhere (e.g. YouTube has no Music chart for the
        // Philippines). Then show the US chart rather than an error.
        runCatching { kiosk(kioskId, null) }.getOrElse { kiosk(kioskId, ContentCountry("US")) }
    }

    private fun kiosk(kioskId: String, country: ContentCountry?): List<VideoItem> {
        val extractor = yt.kioskList.getExtractorById(kioskId, null)
        country?.let(extractor::forceContentCountry)
        extractor.fetchPage()
        return KioskInfo.getInfo(extractor).relatedItems.map { it.toVideoItem() }
    }

    /** Latest uploads from all subscribed channels, newest first. Uses YouTube's lightweight feeds. */
    override suspend fun subscriptionFeed(channels: List<ChannelRef>): List<VideoItem> = coroutineScope {
        channels.map { ch ->
            async(Dispatchers.IO) {
                runCatching {
                    FeedInfo.getInfo(yt, ch.url).relatedItems
                        .filterIsInstance<StreamInfoItem>()
                        .map { it.toVideoItem().copy(channelName = ch.name, channelUrl = ch.url, channelAvatar = ch.avatar) }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
            .distinctBy { it.url }
            .sortedByDescending { it.uploadedEpochMs ?: 0L }
    }

    // ---------- Downloads ----------

    /** H.264 MP4 picture files (the kind phones and the MP4 joiner handle everywhere). */
    private fun downloadableVideo(info: StreamInfo) = playableVideo(info).filter {
        it.format == MediaFormat.MPEG_4 && (it.codec ?: it.itagItem?.codec ?: "").startsWith("avc")
    }

    private fun downloadableAudio(info: StreamInfo) =
        rankAudio(playableAudio(info).filter { it.format == MediaFormat.M4A })

    private fun sizeOf(stream: org.schabi.newpipe.extractor.stream.Stream?): Long = when (stream) {
        is VideoStream -> stream.itagItem?.contentLength ?: 0
        is AudioStream -> stream.itagItem?.contentLength ?: 0
        else -> 0
    }.coerceAtLeast(0)

    override suspend fun downloadOptions(url: String): List<DownloadOption> {
        val info = infoFor(url).await()
        val audio = downloadableAudio(info).firstOrNull() ?: return emptyList()
        val audioSize = sizeOf(audio)
        val videos = downloadableVideo(info)
            .groupBy { it.height }
            .map { (h, list) -> list.maxBy { it.fps } to h }
            .sortedByDescending { it.second }
            .map { (v, h) -> DownloadOption(false, h, sizeOf(v).let { if (it > 0) it + audioSize else 0 }) }
        return videos + DownloadOption(true, 0, audioSize)
    }

    /** Picture (null for audio-only) and sound links to download, picked fresh. */
    suspend fun downloadSources(url: String, audioOnly: Boolean, height: Int): Pair<VideoStream?, AudioStream> {
        invalidate(url) // links expire; a download may start much later than the video was opened
        val info = infoFor(url).await()
        val audio = downloadableAudio(info).firstOrNull() ?: throw IllegalStateException("No downloadable sound")
        if (audioOnly) return null to audio
        val video = downloadableVideo(info).filter { it.height <= height }.maxWithOrNull(compareBy({ it.height }, { it.fps }))
            ?: downloadableVideo(info).minByOrNull { it.height }
            ?: throw IllegalStateException("No downloadable video")
        return video to audio
    }

    // ---------- Channel pictures ----------

    override suspend fun channelAvatar(url: String): String? = io {
        ChannelInfo.getInfo(yt, url).avatars
            .filter { it.height > 0 }.sortedBy { it.height }
            .let { list -> (list.firstOrNull { it.height >= 68 } ?: list.lastOrNull())?.url }
    }

    // ---------- YouTube playlists ----------

    override suspend fun playlist(url: String): PlaylistPage = io {
        val info = PlaylistInfo.getInfo(yt, url)
        val videos = info.relatedItems.map { it.toVideoItem() }
        PlaylistPage(
            name = info.name,
            uploader = info.uploaderName.orEmpty(),
            thumbnail = info.thumbnails.pick(360) ?: videos.firstOrNull()?.thumbnail,
            count = info.streamCount,
            videos = videos,
            next = info.nextPage,
        )
    }

    override suspend fun playlistMore(url: String, next: Any): Pair<List<VideoItem>, Any?> = io {
        val page = PlaylistInfo.getMoreItems(yt, url, next as Page)
        page.items.map { it.toVideoItem() } to page.nextPage
    }

    // ---------- Channels ----------

    override suspend fun channel(url: String): ChannelPage = io {
        val info = ChannelInfo.getInfo(yt, url)
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        val videos = videosTab?.let { tab ->
            runCatching {
                ChannelTabInfo.getInfo(yt, tab).relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .map { it.toVideoItem() }
            }.getOrDefault(emptyList())
        }.orEmpty()
        ChannelPage(
            channel = ChannelRef(info.url, info.name, info.avatars.pick(176)),
            banner = info.banners.pick(400),
            subscribers = info.subscriberCount,
            description = info.description.orEmpty(),
            videos = videos,
        )
    }

    // ---------- Mapping ----------

    private fun StreamInfoItem.toVideoItem() = VideoItem(
        url = url,
        title = name,
        channelName = uploaderName.orEmpty(),
        channelUrl = uploaderUrl,
        thumbnail = thumbnails.pick(360),
        durationSec = duration,
        views = viewCount,
        uploaded = textualUploadDate,
        uploadedEpochMs = runCatching { uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() }.getOrNull(),
        isLive = streamType == StreamType.LIVE_STREAM,
        channelAvatar = uploaderAvatars.pick(68),
    ).also { ChannelAvatars.remember(it.channelUrl, it.channelAvatar) }

    private fun StreamInfo.toVideoItem(): VideoItem {
        val epoch = runCatching { uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() }.getOrNull()
        return VideoItem(
            url = url,
            title = name,
            channelName = uploaderName.orEmpty(),
            channelUrl = uploaderUrl,
            thumbnail = thumbnails.pick(720),
            durationSec = duration,
            views = viewCount,
            // The video page gives an exact timestamp; show it the way lists do ("4 years ago").
            uploaded = epoch?.let(::timeAgo) ?: textualUploadDate,
            uploadedEpochMs = epoch,
            isLive = streamType == StreamType.LIVE_STREAM,
            channelAvatar = uploaderAvatars.pick(68),
        ).also { ChannelAvatars.remember(it.channelUrl, it.channelAvatar) }
    }

    private fun timeAgo(epochMs: Long): String {
        val minutes = (System.currentTimeMillis() - epochMs) / 60_000
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

    /** Smallest image that is at least [targetHeight] tall, else the biggest one. */
    private fun List<Image>.pick(targetHeight: Int): String? {
        if (isEmpty()) return null
        val known = filter { it.height > 0 }.sortedBy { it.height }
        return (known.firstOrNull { it.height >= targetHeight } ?: known.lastOrNull() ?: first()).url
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
