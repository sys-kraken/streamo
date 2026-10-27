package com.streamo.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFoundation.*
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * For the Simulator test (`-StreamoPlainPlayer`): plays a link with a bare AVPlayer, without
 * Streamo's downloader, picture-and-sound combining or player state, and logs what AVPlayer does.
 * If this plays and IosPlayer doesn't, the problem is in Streamo's player code; if this doesn't
 * play either, it's the Simulator.
 */
@OptIn(ExperimentalForeignApi::class)
object PlainPlayerTest {
    private var player: AVPlayer? = null

    fun run(url: String) {
        val p = AVPlayer.playerWithURL(NSURL.URLWithString(url)!!)
        player = p
        p.play()
        iosLog("StreamoPlayer: plain AVPlayer started $url")
        MainScope().launch {
            // Show the picture too, so the screenshots tell whether frames arrive.
            delay(1_000)
            UIApplication.sharedApplication.keyWindow?.layer?.addSublayer(
                AVPlayerLayer.playerLayerWithPlayer(p).apply { setFrame(CGRectMake(0.0, 120.0, 393.0, 221.0)) }
            )
            var lastPos = -1.0
            repeat(70) { tick ->
                val item = p.currentItem
                val pos = CMTimeGetSeconds(p.currentTime()).takeIf { !it.isNaN() } ?: 0.0
                if (tick % 4 == 0) {
                    val duration = item?.duration?.let { CMTimeGetSeconds(it) }?.takeIf { !it.isNaN() } ?: 0.0
                    val error = item?.error?.let { "${it.domain} ${it.code}: ${it.localizedDescription}" }
                    val errorEvent = (item?.errorLog()?.events?.lastOrNull() as? AVPlayerItemErrorLogEvent)
                        ?.let { "${it.errorStatusCode} ${it.errorDomain} ${it.errorComment.orEmpty()}" }
                    val access = (item?.accessLog()?.events?.lastOrNull() as? AVPlayerItemAccessLogEvent)
                        ?.let { "${it.numberOfMediaRequests} requests ${it.numberOfBytesTransferred / 1024}KB" }
                    iosLog(
                        "StreamoPlayer: plain state timeControl=${p.timeControlStatus} waiting=${p.reasonForWaitingToPlay} " +
                            "itemStatus=${item?.status} pos=${pos.toInt()}s/${duration.toInt()}s moving=${pos > lastPos} " +
                            "loaded=${item?.loadedTimeRanges?.size ?: 0} fetched=$access error=$error errorLog=$errorEvent"
                    )
                    lastPos = pos
                }
                delay(500)
            }
        }
    }
}
