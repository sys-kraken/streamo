package com.streamo.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Channel pictures by channel link. Most lists already include them; for the ones that
 * don't, the channel is looked up once (a few at a time) and remembered.
 */
object ChannelAvatars {
    private val known = MutableStateFlow<Map<String, String>>(emptyMap())
    private val lookups = MutableStateFlow<Map<String, Deferred<String?>>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limit = Semaphore(3)

    fun remember(channelUrl: String?, avatar: String?) {
        if (channelUrl != null && avatar != null) known.update { it + (channelUrl to avatar) }
    }

    fun peek(channelUrl: String?): String? = channelUrl?.let { known.value[it] }

    suspend fun get(channelUrl: String): String? {
        known.value[channelUrl]?.let { return it }
        val lookup = lookups.value[channelUrl] ?: run {
            val fresh = scope.async(start = CoroutineStart.LAZY) {
                limit.withPermit {
                    runCatching { YouTubeRepo.channelAvatar(channelUrl) }.getOrNull()
                        ?.also { remember(channelUrl, it) }
                }
            }
            // If another screen asked at the same moment, both wait on the first lookup.
            lookups.update { if (channelUrl in it) it else it + (channelUrl to fresh) }
            lookups.value.getValue(channelUrl)
        }
        return lookup.await()
    }
}
