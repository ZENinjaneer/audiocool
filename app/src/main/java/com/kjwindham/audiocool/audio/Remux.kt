package com.kjwindham.audiocool.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies the AAC audio of an ADTS (.aac) file into an MP4 (.m4a) container without re-encoding.
 * MP4 carries a sample index, so players open and seek it instantly; a raw ADTS file has to be
 * scanned frame by frame first, which takes seconds for a lecture-length recording.
 */
fun remuxAdtsToM4a(src: File, dst: File): Boolean {
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var ok = false
    try {
        extractor.setDataSource(src.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return false
        extractor.selectTrack(track)
        val m = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = m
        val out = m.addTrack(extractor.getTrackFormat(track))
        m.start()
        val buffer = ByteBuffer.allocate(64 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            // Every AAC frame is independently decodable.
            info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            m.writeSampleData(out, buffer, info)
            extractor.advance()
        }
        m.stop()
        ok = true
    } catch (e: Exception) {
        Log.w("Remux", "Couldn't convert ${src.name}", e)
    } finally {
        extractor.release()
        runCatching { muxer?.release() }
        if (!ok) dst.delete()
    }
    return ok
}
