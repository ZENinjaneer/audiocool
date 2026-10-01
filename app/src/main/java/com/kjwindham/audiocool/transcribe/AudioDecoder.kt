package com.kjwindham.audiocool.transcribe

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** Decodes an audio file (.m4a/.aac) to mono float samples at [outRate], delivered in chunks. */
class AudioDecoder(private val file: File, private val outRate: Int) {

    fun decode(isCancelled: () -> Boolean, onProgress: (Float) -> Unit, onChunk: (FloatArray) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio in ${file.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var resampler = Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), outRate)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (isCancelled()) throw CancellationException()
                if (!inputDone) {
                    val index = c.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val size = extractor.readSampleData(c.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The decoder's real output format (e.g. HE-AAC decodes at twice the stated rate).
                    val out = c.outputFormat
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    resampler = Resampler(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), outRate)
                    floatPcm = isFloatPcm(out)
                } else if (index >= 0) {
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(index)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val samples = resampler.process(pcmToMono(buf, channels, floatPcm))
                        if (samples.isNotEmpty()) onChunk(samples)
                        if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            val tail = resampler.flush()
            if (tail.isNotEmpty()) onChunk(tail)
            onProgress(1f)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}

internal fun isFloatPcm(format: MediaFormat) =
    format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
        format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT

/** Decoder output (interleaved 16-bit or float PCM) averaged down to mono floats in -1..1. */
internal fun pcmToMono(buf: ByteBuffer, channels: Int, floatPcm: Boolean): FloatArray {
    val ordered = buf.slice().order(ByteOrder.nativeOrder())
    val ch = channels.coerceAtLeast(1)
    return if (floatPcm) {
        val fb = ordered.asFloatBuffer()
        FloatArray(fb.remaining() / ch) { i ->
            var sum = 0f
            for (k in 0 until ch) sum += fb.get(i * ch + k)
            sum / ch
        }
    } else {
        val sb = ordered.asShortBuffer()
        FloatArray(sb.remaining() / ch) { i ->
            var sum = 0f
            for (k in 0 until ch) sum += sb.get(i * ch + k)
            sum / ch / 32768f
        }
    }
}
