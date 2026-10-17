package com.streamo.app.data.youtube

import com.streamo.app.data.ChannelRef
import com.streamo.app.data.SearchResult
import com.streamo.app.data.VideoItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the iPhone YouTube client against live YouTube (needs internet). It prints what it
 * found so a person can sanity-check titles, counts and dates, and fails on empty results.
 */
class InnerTubeSourceTest {
    private val http = HttpClient(OkHttp)
    private val yt = InnerTubeSource(http) { "PH" }

    private fun show(label: String, v: VideoItem) =
        println("  $label ${v.title.take(50)} | ${v.channelName} | views=${v.views} | ${v.uploaded} | ${v.durationSec}s | live=${v.isLive} | ch=${v.channelUrl != null} avatar=${v.channelAvatar != null} thumb=${v.thumbnail != null}")

    @Test
    fun search() = runBlocking {
        val page = yt.search("lofi girl")
        println("SEARCH: ${page.items.size} results, next=${page.next != null}")
        page.items.take(8).forEach {
            when (it) {
                is SearchResult.Video -> show("video", it.video)
                is SearchResult.Channel -> println("  channel ${it.channel.name} subs=${it.subscribers} avatar=${it.channel.avatar != null}")
                is SearchResult.Playlist -> println("  playlist ${it.playlist.name} by ${it.playlist.uploader} (${it.playlist.count})")
            }
        }
        assertTrue(page.items.size >= 10)
        val more = yt.searchMore("lofi girl", page.next!!)
        println("SEARCH page 2: ${more.items.size} results")
        assertTrue(more.items.isNotEmpty())
        val sugg = yt.suggestions("lofi")
        println("SUGGESTIONS: $sugg")
        assertTrue(sugg.isNotEmpty())
    }

    @Test
    fun videoAndStreams() = runBlocking {
        val d = yt.details("https://www.youtube.com/watch?v=dQw4w9WgXcQ", 1080)
        show("details", d.item)
        println("  likes=${d.likes} subs=${d.channelSubscribers} avatar=${d.channelAvatar != null} related=${d.related.size} desc=${d.description.take(60)}")
        d.related.take(3).forEach { show("related", it) }
        val s = d.streams
        println("  streams: height=${s.height} heights=${s.availableHeights} hls=${s.isHls} video=${s.videoUrl != null} audio=${s.audioUrl != null}")
        for (url in listOfNotNull(s.videoUrl, s.audioUrl, s.combinedUrl)) {
            val status = http.get(url) { header("Range", "bytes=0-1000"); header("User-Agent", YouTubeApi.IOS_USER_AGENT) }.status.value
            println("  fetch -> $status")
            assertTrue(status in 200..299, "stream link refused: $status")
        }
        assertTrue(d.related.isNotEmpty() && s.height in 1..1080)
        println("  downloads: " + yt.downloadOptions("https://www.youtube.com/watch?v=dQw4w9WgXcQ").joinToString { "${if (it.audioOnly) "audio" else "${it.height}p"}=${it.sizeBytes / 1_000_000}MB" })
    }

    @Test
    fun home() = runBlocking {
        for (id in yt.exploreLists()) {
            val list = yt.explore(id)
            println("EXPLORE $id: ${list.size}")
            list.take(2).forEach { show("  ", it) }
            assertTrue(list.isNotEmpty(), "$id is empty")
        }
        val history = listOf(VideoItem("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "", "", null, null, 0, 0, null))
        val forYou = yt.forYou(history)
        println("FOR YOU: ${forYou.size}")
        assertTrue(forYou.isNotEmpty())
    }

    @Test
    fun channelPlaylistFeed() = runBlocking {
        val lofi = "https://www.youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow"
        val c = yt.channel(lofi)
        println("CHANNEL ${c.channel.name} subs=${c.subscribers} avatar=${c.channel.avatar != null} banner=${c.banner != null} videos=${c.videos.size} desc=${c.description.take(40)}")
        c.videos.take(2).forEach { show("  ", it) }
        assertTrue(c.videos.isNotEmpty())
        println("AVATAR ${yt.channelAvatar(lofi)}")
        println("HANDLE " + yt.channel("https://www.youtube.com/@LofiGirl").channel.url)

        val p = yt.playlist("https://www.youtube.com/playlist?list=PLOHoVaTp8R7dfrJW5pumS0iD_dhlXKv17")
        println("PLAYLIST ${p.name} by ${p.uploader} count=${p.count} videos=${p.videos.size} next=${p.next != null}")
        p.videos.take(2).forEach { show("  ", it) }
        assertTrue(p.videos.isNotEmpty())
        val more = yt.playlistMore("", p.next ?: error("no next page"))
        println("PLAYLIST page 2: ${more.first.size} next=${more.second != null}")
        assertTrue(more.first.isNotEmpty())

        val feed = yt.subscriptionFeed(listOf(ChannelRef(lofi, "Lofi Girl", null)))
        println("FEED ${feed.size}")
        feed.take(2).forEach { show("  ", it) }
        assertTrue(feed.isNotEmpty())
    }
}
