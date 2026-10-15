package com.streamo.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins YouTube's separate picture file and sound file into one normal MP4, copying the
 * data as-is (no re-encoding, so it's fast and loses no quality). With no picture file it
 * writes a sound-only .m4a, which music players handle better than YouTube's raw file.
 */
object Muxer {

    fun mux(video: File?, audio: File, out: File) {
        val inputs = listOfNotNull(video, audio).map { file ->
            MediaExtractor().apply {
                setDataSource(file.absolutePath)
                selectTrack(0)
            }
        }
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val tracks = inputs.map { muxer.addTrack(it.getTrackFormat(0)) }
            val bufferSize = inputs.maxOf { ex ->
                val f = ex.getTrackFormat(0)
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            }.coerceAtLeast(4 * 1024 * 1024)
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            muxer.start()

            // Write samples in time order across both inputs, so picture and sound are interleaved.
            val done = BooleanArray(inputs.size)
            while (!done.all { it }) {
                val i = inputs.indices.filter { !done[it] }.minBy { inputs[it].sampleTime }
                val ex = inputs[i]
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) {
                    done[i] = true
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(tracks[i], buffer, info)
                if (!ex.advance()) done[i] = true
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            inputs.forEach { runCatching { it.release() } }
        }
    }
}
