package com.kjwindham.audiocool.transcribe

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Decodes an AAC (ADTS) file while MediaRecorder is still writing it: reads whatever whole frames
 * have been written, decodes them, and waits for more. Once [writerFinished] is called it decodes
 * the rest of the file and then ends.
 */
class AdtsTailSource(private val file: File) : PcmSource {
    @Volatile
    private var writerDone = false

    @Volatile
    private var stopped = false

    private val reader = AdtsFrameReader()
    private val readBuf = ByteArray(32 * 1024)
    private val pending = ArrayDeque<ByteArray>() // frame payloads waiting for a decoder input buffer
    private val info = MediaCodec.BufferInfo()
    private var input: FileInputStream? = null
    private var codec: MediaCodec? = null
    private var sampleRate = 0
    private var channels = 1
    private var floatPcm = false
    private var framesQueued = 0L
    private var inputEnded = false
    private var outputEnded = false

    /** The recording is complete; decode to the end of the file, then finish. */
    fun writerFinished() {
        writerDone = true
    }

    /** Makes [next] return null as soon as possible. Safe to call from any thread. */
    fun stop() {
        stopped = true
    }

    override fun next(): PcmChunk? {
        while (!stopped) {
            codec?.let { c ->
                drain(c)?.let { return it }
                if (outputEnded) return null
            }
            val bytesRead = readMore()
            feed()
            val c = codec
            val everythingRead = writerDone && bytesRead <= 0 && pending.isEmpty()
            if (c == null) {
                if (everythingRead) return null // the file never got a whole frame
            } else if (everythingRead && !inputEnded) {
                val index = c.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEnded = true
                }
            }
            // Nothing new on disk yet: wait for the recorder to write more.
            if (bytesRead <= 0 && pending.isEmpty() && !inputEnded) Thread.sleep(POLL_MS)
        }
        return null
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { input?.close() }
        input = null
    }

    /** Reads newly written bytes into the frame reader; returns how many (0 or less if none yet). */
    private fun readMore(): Int {
        val stream = input ?: run {
            if (!file.exists()) return 0
            FileInputStream(file).also { input = it }
        }
        // At the current end of the file this returns -1; once more is appended, reads continue.
        val n = stream.read(readBuf)
        if (n > 0) reader.append(readBuf, n)
        while (true) {
            val frame = reader.next() ?: break
            if (codec == null) start(frame.header)
            pending.addLast(frame.payload)
        }
        return n
    }

    private fun start(header: AdtsHeader) {
        sampleRate = header.sampleRate
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, header.channelConfig.coerceAtLeast(1))
        format.setByteBuffer("csd-0", ByteBuffer.wrap(header.audioSpecificConfig()))
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(format, null, null, 0)
        c.start()
        codec = c
    }

    private fun feed() {
        val c = codec ?: return
        while (pending.isNotEmpty()) {
            val index = c.dequeueInputBuffer(0)
            if (index < 0) return
            val payload = pending.removeFirst()
            val buf = c.getInputBuffer(index)!!
            buf.clear()
            buf.put(payload)
            // Each AAC frame is 1024 samples.
            c.queueInputBuffer(index, 0, payload.size, framesQueued * 1024 * 1_000_000L / sampleRate, 0)
            framesQueued++
        }
    }

    private fun drain(c: MediaCodec): PcmChunk? {
        val index = c.dequeueOutputBuffer(info, if (inputEnded) TIMEOUT_US else 0)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val out = c.outputFormat
            sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm = isFloatPcm(out)
            return null
        }
        if (index < 0) return null
        val chunk = if (info.size > 0) {
            val buf = c.getOutputBuffer(index)!!
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            PcmChunk(pcmToMono(buf, channels, floatPcm), sampleRate)
        } else {
            null
        }
        c.releaseOutputBuffer(index, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
        return chunk
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val POLL_MS = 150L
    }
}
