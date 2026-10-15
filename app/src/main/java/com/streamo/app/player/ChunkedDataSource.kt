package com.streamo.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * YouTube heavily slows down (and sometimes refuses) open-ended downloads like "bytes=0-",
 * but serves fixed-size pieces at full speed. This asks for the video in pieces of
 * [CHUNK_BYTES] while looking like one continuous file to the player.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(private val upstream: HttpDataSource) : DataSource {

    private var spec: DataSpec? = null
    private var position = 0L      // next byte to fetch, in the whole file
    private var end = C.LENGTH_UNSET.toLong() // exclusive; unknown until the first reply
    private var chunkOpen = false
    private var chunked = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = dataSpec.uri.host?.endsWith("googlevideo.com") == true
        if (!chunked) return upstream.open(dataSpec)

        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else C.LENGTH_UNSET.toLong()
        openChunk()
        // Learn the full file size from "Content-Range: bytes a-b/TOTAL".
        if (end == C.LENGTH_UNSET.toLong()) {
            val total = upstream.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()?.substringAfter('/')?.toLongOrNull()
            if (total != null) end = total
        }
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - position
    }

    private fun openChunk() {
        val base = spec!!
        var length = CHUNK_BYTES
        if (end != C.LENGTH_UNSET.toLong()) length = minOf(length, end - position)
        upstream.open(base.buildUpon().setPosition(position).setLength(length).build())
        chunkOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
            if (!chunkOpen) openChunk()
            val read = upstream.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                return read
            }
            // This piece is done; move on to the next one (or stop if the server had nothing more).
            upstream.close()
            chunkOpen = false
            if (end == C.LENGTH_UNSET.toLong()) return C.RESULT_END_OF_INPUT
        }
    }

    override fun getUri(): Uri? = spec?.uri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        chunkOpen = false
        spec = null
        upstream.close()
    }

    class Factory(private val upstream: HttpDataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource())
    }

    companion object {
        /** Big enough to keep requests few, well under the size YouTube starts slowing down. */
        const val CHUNK_BYTES = 2L * 1024 * 1024
    }
}
