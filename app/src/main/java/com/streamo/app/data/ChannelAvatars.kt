package com.streamo.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Channel pictures by channel link. Most lists already include them; for the ones that
 * don't, the channel is looked up once (a few at a time) and remembered.
 */
object ChannelAvatars {
    private val known = ConcurrentHashMap<String, String>()
    private val lookups = ConcurrentHashMap<String, Deferred<String?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limit = Semaphore(3)

    fun remember(channelUrl: String?, avatar: String?) {
        if (channelUrl != null && avatar != null) known[channelUrl] = avatar
    }

    fun peek(channelUrl: String?): String? = channelUrl?.let { known[it] }

    suspend fun get(channelUrl: String): String? {
        known[channelUrl]?.let { return it }
        return lookups.getOrPut(channelUrl) {
            scope.async {
                limit.withPermit {
                    runCatching {
                        ChannelInfo.getInfo(ServiceList.YouTube, channelUrl).avatars
                            .filter { it.height > 0 }.sortedBy { it.height }
                            .let { list -> (list.firstOrNull { it.height >= 68 } ?: list.lastOrNull())?.url }
                    }.getOrNull()?.also { known[channelUrl] = it }
                }
            }
        }.await()
    }
}
