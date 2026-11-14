package com.streamo.app

import com.streamo.app.data.youtube.YouTubeApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.*
import platform.Foundation.*
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Streamo does the downloading for Apple's player. AVPlayer is given "streamo-https://" links,
 * which make it ask this loader for every byte range it needs; the loader fetches them from
 * YouTube in chunks with ordinary iPhone networking (NSURLSession) and the iPhone app's
 * User-Agent. AVPlayer's own media networking couldn't fetch YouTube's files reliably.
 */
@OptIn(ExperimentalForeignApi::class)
internal class StreamLoader : NSObject(), AVAssetResourceLoaderDelegateProtocol {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.defaultSessionConfiguration)
    private val jobs = mutableMapOf<AVAssetResourceLoadingRequest, Job>()

    /** Called for errors; IosPlayer sends them to its log. */
    var onError: (String) -> Unit = {}

    /** An asset for a YouTube file whose bytes go through this loader. */
    fun asset(url: String): AVURLAsset {
        val routed = NSURL.URLWithString(url.replaceFirst("https://", "$SCHEME://"))!!
        return AVURLAsset.URLAssetWithURL(routed, null).also {
            it.resourceLoader.setDelegate(this, dispatch_get_main_queue())
        }
    }

    @ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        val request = shouldWaitForLoadingOfRequestedResource
        val url = request.request.URL?.absoluteString ?: return false
        if (!url.startsWith("$SCHEME://")) return false
        jobs[request] = scope.launch { serve(url.replaceFirst("$SCHEME://", "https://"), request) }
        return true
    }

    @ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        didCancelLoadingRequest: AVAssetResourceLoadingRequest,
    ) {
        jobs.remove(didCancelLoadingRequest)?.cancel()
    }

    /** Answers one request from the player: file type and size, and/or a range of bytes. */
    private suspend fun serve(url: String, request: AVAssetResourceLoadingRequest) {
        try {
            val data = request.dataRequest
            val info = request.contentInformationRequest
            var offset = data?.requestedOffset ?: 0L
            val last = if (data == null || data.requestsAllDataToEndOfResource) Long.MAX_VALUE
            else data.requestedOffset + data.requestedLength - 1
            var total = Long.MAX_VALUE
            do {
                val chunkEnd = minOf(last, offset + CHUNK - 1, total - 1)
                val (bytes, response) = fetch(url, offset, chunkEnd)
                total = totalSize(response) ?: total
                if (info != null && info.contentLength == 0L) {
                    info.contentType = typeOf(response)
                    info.contentLength = total
                    info.byteRangeAccessSupported = true
                }
                if (data == null || bytes.length == 0UL) break
                data.respondWithData(bytes)
                offset += bytes.length.toLong()
            } while (offset <= last && offset < total)
            request.finishLoading()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The link is tied to the IP that asked YouTube for it ("ip=" in the link); log it to compare.
            val linkIp = Regex("[?&]ip=([^&]+)").find(url)?.groupValues?.get(1)
            val itag = Regex("""[?&]itag=(\d+)""").find(url)?.groupValues?.get(1)
            onError("loading itag=$itag linkIp=$linkIp: ${e.message}")
            request.finishLoadingWithError(
                NSError.errorWithDomain("Streamo", 1, mapOf<Any?, Any?>(NSLocalizedDescriptionKey to (e.message ?: "Download failed")))
            )
        } finally {
            jobs.remove(request)
        }
    }

    private suspend fun fetch(url: String, from: Long, to: Long): Pair<NSData, NSHTTPURLResponse> =
        suspendCancellableCoroutine { cont ->
            val req = NSMutableURLRequest.requestWithURL(NSURL.URLWithString(url)!!)
            req.setValue("bytes=$from-$to", forHTTPHeaderField = "Range")
            req.setValue(YouTubeApi.IOS_USER_AGENT, forHTTPHeaderField = "User-Agent")
            val task = session.dataTaskWithRequest(req) { body, response, error ->
                val http = response as? NSHTTPURLResponse
                if (body != null && http != null && http.statusCode in 200L..299L) cont.resume(body to http)
                else cont.resumeWithException(
                    IllegalStateException("HTTP ${http?.statusCode ?: "-"} for bytes=$from-$to ${error?.localizedDescription.orEmpty()}")
                )
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }

    /** The whole file's size, from "Content-Range: bytes 0-1023/123456". */
    private fun totalSize(response: NSHTTPURLResponse): Long? =
        response.valueForHTTPHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()

    /** Apple's type id for the file, which AVPlayer needs to read it. */
    private fun typeOf(response: NSHTTPURLResponse): String {
        val mime = response.valueForHTTPHeaderField("Content-Type").orEmpty()
        return when {
            mime.startsWith("audio/mp4") -> "com.apple.m4a-audio"
            mime.startsWith("audio/") -> "public.audio"
            else -> "public.mpeg-4"
        }
    }
}

private const val SCHEME = "streamo-https"
private const val CHUNK = 2L * 1024 * 1024 // YouTube is fastest with moderate ranges, like Android's ChunkedDataSource
