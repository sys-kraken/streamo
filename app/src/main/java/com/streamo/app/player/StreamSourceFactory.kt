package com.streamo.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.streamo.app.data.StreamChoice

/**
 * YouTube serves picture and sound as two separate files for anything above 360p.
 * This plays them together, so the player can drop the picture file entirely while
 * the screen is off and only download sound.
 */
@OptIn(UnstableApi::class)
class StreamSourceFactory(context: Context, private val dataSource: DataSource.Factory) : MediaSource.Factory {
    private val progressive = ProgressiveMediaSource.Factory(dataSource)
    private val hls = HlsMediaSource.Factory(dataSource)
    // Anything else (downloaded files: content:// and file:// addresses) needs a reader that
    // understands the phone's own storage, not just the internet.
    private val fallback = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, dataSource))

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val streams = mediaItem.localConfiguration?.tag as? StreamChoice
            ?: return fallback.createMediaSource(mediaItem)

        if (streams.isHls) return hls.createMediaSource(mediaItem)
        if (streams.videoUrl == null || streams.audioUrl == null) return progressive.createMediaSource(mediaItem)

        // The picture source keeps the original item so titles/artwork stay attached to playback.
        val video = progressive.createMediaSource(mediaItem.buildUpon().setUri(streams.videoUrl).build())
        val audio = progressive.createMediaSource(MediaItem.fromUri(streams.audioUrl))
        return MergingMediaSource(/* adjustPeriodTimeOffsets = */ true, /* clipDurations = */ true, video, audio)
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        progressive.setDrmSessionManagerProvider(provider)
        hls.setDrmSessionManagerProvider(provider)
        fallback.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        progressive.setLoadErrorHandlingPolicy(policy)
        hls.setLoadErrorHandlingPolicy(policy)
        fallback.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)
}
