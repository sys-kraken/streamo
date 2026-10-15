package com.streamo.app

import com.streamo.app.data.OkHttpDownloader
import com.streamo.app.data.YouTubeRepo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

class SpeedTest {
    private val client = OkHttpClient()

    private fun measure(label: String, url: String, range: String) {
        val t = System.currentTimeMillis()
        val req = Request.Builder().url(url).header("Range", range).build()
        var bytes = 0L
        client.newCall(req).execute().use { r ->
            val src = r.body.source()
            val buf = okio.Buffer()
            while (bytes < 4_000_000) { val n = src.read(buf, 65536); if (n < 0) break; bytes += n; buf.clear() }
            val ms = System.currentTimeMillis() - t
            println("$label HTTP ${r.code}: ${bytes / 1024} KB in $ms ms = ${bytes * 8 / 1000 / ms.coerceAtLeast(1)} kbit/s")
        }
    }

    @Test
    fun speed() = runBlocking {
        NewPipe.init(OkHttpDownloader(client))
        for (u in listOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ", "https://www.youtube.com/watch?v=JGwWNGJdvx8")) {
            val t = System.currentTimeMillis()
            val s = YouTubeRepo.streams(u, 1080).second
            println("lookup ${System.currentTimeMillis() - t} ms")
            measure("video open-ended", s.videoUrl!!, "bytes=0-")
            measure("video closed 0-4MB", s.videoUrl!!, "bytes=0-3999999")
            measure("video open-ended mid", s.videoUrl!!, "bytes=20000000-")
        }
    }
}
