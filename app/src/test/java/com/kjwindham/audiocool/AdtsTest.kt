package com.kjwindham.audiocool

import com.kjwindham.audiocool.transcribe.AdtsFrameReader
import com.kjwindham.audiocool.transcribe.AdtsHeader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdtsTest {
    /** An ADTS frame as MediaRecorder writes it: AAC LC, 44.1 kHz, mono, no CRC. */
    private fun frame(payload: ByteArray): ByteArray {
        val length = payload.size + 7
        val header = byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((1 shl 6) or (4 shl 2) or (1 shr 2)).toByte(),
            (((1 and 3) shl 6) or (length shr 11)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        return header + payload
    }

    @Test
    fun parsesMediaRecordersFrames() {
        val bytes = frame(ByteArray(300) { 1 })
        val h = AdtsHeader.parse(bytes, 0, bytes.size)!!
        assertEquals(1, h.profile)
        assertEquals(44_100, h.sampleRate)
        assertEquals(1, h.channelConfig)
        assertEquals(307, h.frameLength)
        assertEquals(7, h.headerLength)
        // The well-known AudioSpecificConfig for AAC LC, 44.1 kHz, mono.
        assertArrayEquals(byteArrayOf(0x12, 0x08), h.audioSpecificConfig())
        assertNull(AdtsHeader.parse(bytes, 1, bytes.size))
        assertNull(AdtsHeader.parse(bytes, 0, 6))
    }

    @Test
    fun handsOutFramesOnlyOnceTheyAreComplete() {
        val a = ByteArray(200) { 2 }
        val b = ByteArray(450) { 3 }
        val stream = frame(a) + frame(b)
        val reader = AdtsFrameReader()
        // The file grows a few bytes at a time while it's being recorded.
        reader.append(stream, 100)
        assertNull(reader.next())
        reader.append(stream.copyOfRange(100, 300), 200)
        assertArrayEquals(a, reader.next()!!.payload)
        assertNull(reader.next())
        reader.append(stream.copyOfRange(300, stream.size), stream.size - 300)
        assertArrayEquals(b, reader.next()!!.payload)
        assertNull(reader.next())
    }

    @Test
    fun skipsBytesThatArentAFrame() {
        val payload = ByteArray(64) { 9 }
        val stream = byteArrayOf(0, 0x12, 0xFF.toByte(), 0x00) + frame(payload)
        val reader = AdtsFrameReader()
        reader.append(stream, stream.size)
        assertArrayEquals(payload, reader.next()!!.payload)
    }

    @Test
    fun growsForLargeAppends() {
        val frames = (1..400).map { frame(ByteArray(300) { i -> (i + it).toByte() }) }
        val stream = frames.reduce(ByteArray::plus) // ~120 KB, more than the initial buffer
        val reader = AdtsFrameReader()
        reader.append(stream, stream.size)
        var count = 0
        while (reader.next() != null) count++
        assertEquals(400, count)
    }
}
