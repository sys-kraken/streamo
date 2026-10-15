package com.streamo.app

import com.streamo.app.data.OkHttpDownloader
import com.streamo.app.data.SearchResult
import com.streamo.app.data.YouTubeRepo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

/** Hits real YouTube. Run manually: gradlew :app:testDebugUnitTest --tests *ExtractionSmokeTest* */
class ExtractionSmokeTest {
    private val client = OkHttpClient()

    @Test
    fun searchAndResolve() = runBlocking {
        NewPipe.init(OkHttpDownloader(client))

        val t0 = System.currentTimeMillis()
        val results = YouTubeRepo.search("lofi hip hop")
        println("search: ${System.currentTimeMillis() - t0} ms, results=${results.items.size}")
        for (url in listOf("https://www.youtube.com/watch?v=jNQXAC9IVRw", "https://www.youtube.com/watch?v=aqz-KE-bpKQ")) {
            check(url)
        }
        println("explore lists: ${YouTubeRepo.exploreLists()}")
        for (k in YouTubeRepo.exploreLists()) {
            val r = runCatching { YouTubeRepo.explore(k) }
            println("explore $k: " + r.fold({ "${it.size} items, first=${it.firstOrNull()?.title}" }, { "FAILED ${it.message}" }))
        }
    }

    private suspend fun check(url: String) {
        val video = YouTubeRepo.streams(url, 1080).first

        val t1 = System.currentTimeMillis()
        val details = YouTubeRepo.details(video.url, 1080)
        println("details: ${System.currentTimeMillis() - t1} ms, ${details.streams.height}p, heights=${details.streams.availableHeights}, related=${details.related.size}")

        for (u in listOfNotNull(details.streams.videoUrl, details.streams.audioUrl, details.streams.combinedUrl)) {
            val req = Request.Builder().url(u).header("Range", "bytes=0-1023").build()
            client.newCall(req).execute().use { println("stream check: HTTP ${it.code} ${it.header("Content-Type")} ${u.take(60)}") ; assertTrue(it.code in 200..299) }
        }
    }
}
