package com.streamo.app

import com.streamo.app.data.NewPipeRepo
import com.streamo.app.data.OkHttpDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

/** Hits real YouTube: comments come with real avatars, and replies load. */
class CommentsSmokeTest {
    @Test
    fun commentsAvatarsAndReplies(): Unit = runBlocking {
        NewPipe.init(OkHttpDownloader(OkHttpClient()))
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val page = NewPipeRepo.comments(url)
        println("comments: ${page.comments.size}, total=${page.count}, more=${page.next != null}")
        page.comments.take(5).forEach { println("  ${it.author} avatar=${it.authorAvatar} replies=${it.replyCount} handle=${it.replies != null}") }
        assertTrue(page.comments.isNotEmpty())
        // YouTube's comment data has no pictures via NewPipe; the app looks them up from the channel.
        val first = page.comments.first { it.authorUrl != null }
        val avatar = NewPipeRepo.channelAvatar(first.authorUrl!!)
        println("avatar for ${first.author} via channel: $avatar")
        assertTrue("commenter's channel picture should be found", avatar?.startsWith("http") == true)

        val threaded = page.comments.first { it.replies != null && it.replyCount > 0 }
        val replies = NewPipeRepo.commentReplies(url, threaded.replies!!)
        println("replies to ${threaded.author} (${threaded.replyCount}): got ${replies.comments.size}, more=${replies.next != null}")
        replies.comments.take(3).forEach { println("  ${it.author} avatar=${it.authorAvatar}: ${it.text.take(60)}") }
        assertTrue(replies.comments.isNotEmpty())
        replies.next?.let { more ->
            val second = NewPipeRepo.commentReplies(url, more)
            println("more replies: ${second.comments.size}")
        }
    }
}
