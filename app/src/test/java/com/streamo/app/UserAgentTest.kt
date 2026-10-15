package com.streamo.app

import com.streamo.app.data.OkHttpDownloader
import com.streamo.app.data.SearchResult
import com.streamo.app.data.YouTubeRepo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper as H

/** Manual check: which User-Agent avoids 403s on stream URLs. */
class UserAgentTest {
    private val client = OkHttpClient()

    private fun code(url: String, ua: String?): Int {
        val b = Request.Builder().url(url).header("Range", "bytes=0-2097151")
        if (ua != null) b.header("User-Agent", ua)
        return client.newCall(b.build()).execute().use { it.code }
    }

    private fun matchingUa(url: String): String? = when {
        H.isAndroidStreamingUrl(url) -> H.getAndroidUserAgent(null)
        H.isIosStreamingUrl(url) -> H.getIosUserAgent(null)
        H.isVisionOsStreamingUrl(url) -> H.getVisionOsUserAgent(null)
        else -> null
    }

    @Test
    fun userAgents() = runBlocking {
        NewPipe.init(OkHttpDownloader(client))
        val urls = listOf("music mix 2000s", "how money works", "cooking pasta").flatMap { q ->
            YouTubeRepo.search(q).items.filterIsInstance<SearchResult.Video>().filter { !it.video.isLive }.take(4).map { it.video.url }
        }
        var plain403 = 0; var matched403 = 0; var total = 0
        for (u in urls) {
            val s = runCatching { YouTubeRepo.streams(u, 1080).second }.getOrNull() ?: continue
            for (stream in listOfNotNull(s.videoUrl, s.audioUrl)) {
                total++
                val client = Regex("[?&]c=([A-Z_]+)").find(stream)?.groupValues?.get(1)
                val a = code(stream, null); val b = code(stream, matchingUa(stream))
                if (a == 403) plain403++; if (b == 403) matched403++
                println("c=$client plain=$a matched=$b")
            }
        }
        println("RESULT total=$total plain403=$plain403 matched403=$matched403")
    }
}
