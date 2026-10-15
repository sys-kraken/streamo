package com.streamo.app

import com.streamo.app.data.OkHttpDownloader
import com.streamo.app.data.SearchResult
import com.streamo.app.data.YouTubeRepo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

/** Manual check: which lists come with channel pictures. */
class AvatarTest {
    @Test
    fun avatars() = runBlocking {
        NewPipe.init(OkHttpDownloader(OkHttpClient()))
        fun report(name: String, list: List<com.streamo.app.data.VideoItem>) =
            println("$name: ${list.count { it.channelAvatar != null }}/${list.size} have avatars")
        report("search", YouTubeRepo.search("lofi").items.filterIsInstance<SearchResult.Video>().map { it.video })
        for (k in YouTubeRepo.exploreLists()) report(k, runCatching { YouTubeRepo.explore(k) }.getOrDefault(emptyList()))
        val d = YouTubeRepo.details("https://www.youtube.com/watch?v=aqz-KE-bpKQ", 720)
        report("related", d.related)
        println("details avatar: ${d.item.channelAvatar != null}")
    }
}
