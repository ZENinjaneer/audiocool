package com.kjwindham.audiocool.share

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.kjwindham.audiocool.transcribe.AudioDecoder
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * A recording made small to go in a web page: mono AAC at 32 kbps, about 15 MB an hour, which is
 * plenty for speech.
 */
object SmallAudio {
    const val SAMPLE_RATE = 16_000
    const val BITRATE = 32_000

    /** Bytes per second of the result, for estimating a page's size. */
    const val BYTES_PER_SECOND = BITRATE / 8

    /** Writes [input] to [output] small; [onProgress] goes 0..1. Throws if it can't. */
    fun encode(input: File, output: File, isCancelled: () -> Boolean, onProgress: (Float) -> Unit) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        var samplesIn = 0L
        val info = MediaCodec.BufferInfo()

        /** Hands what the encoder has made to the file; with [untilEnd], waits for the last of it. */
        fun drain(untilEnd: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxing) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        /** Gives the encoder [pcm] (16-bit samples), as its input buffers free up. */
        fun feed(pcm: ShortArray) {
            var at = 0
            while (at < pcm.size) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index < 0) {
                    drain(untilEnd = false)
                    continue
                }
                // Samples go in little-endian (a ByteBuffer's own order is big-endian).
                val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                buffer.clear()
                val n = minOf(pcm.size - at, buffer.remaining() / 2)
                for (i in 0 until n) buffer.putShort(pcm[at + i])
                codec.queueInputBuffer(index, 0, n * 2, samplesIn * 1_000_000 / SAMPLE_RATE, 0)
                samplesIn += n
                at += n
                drain(untilEnd = false)
            }
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            AudioDecoder(input, SAMPLE_RATE).decode(isCancelled, onProgress) { chunk ->
                feed(ShortArray(chunk.size) { (chunk[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() })
            }
            if (isCancelled()) throw IOException("Cancelled")
            // The end.
            while (true) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, samplesIn * 1_000_000 / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(untilEnd = false)
            }
            drain(untilEnd = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (muxing) muxer.stop() }
            muxer.release()
        }
        if (!muxing || output.length() == 0L) throw IOException("The audio couldn't be made smaller")
    }
}
