package com.kjwindham.audiocool.transcribe

/** One ADTS frame header (the framing MediaRecorder's AAC_ADTS output uses). */
data class AdtsHeader(
    /** 0 = AAC Main, 1 = AAC LC, 2 = SSR, 3 = LTP. */
    val profile: Int,
    val sampleRateIndex: Int,
    val channelConfig: Int,
    /** Header plus payload, in bytes. */
    val frameLength: Int,
    /** 7 bytes, or 9 when a CRC follows. */
    val headerLength: Int,
) {
    val sampleRate: Int get() = SAMPLE_RATES.getOrElse(sampleRateIndex) { 0 }

    /** The decoder's codec-specific data ("csd-0"): a 2-byte AudioSpecificConfig. */
    fun audioSpecificConfig(): ByteArray {
        val objectType = profile + 1
        return byteArrayOf(
            ((objectType shl 3) or (sampleRateIndex shr 1)).toByte(),
            (((sampleRateIndex and 1) shl 7) or (channelConfig shl 3)).toByte(),
        )
    }

    companion object {
        val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** The header starting at [offset], or null if there isn't a valid one there or fewer than 7 bytes before [end]. */
        fun parse(b: ByteArray, offset: Int, end: Int): AdtsHeader? {
            if (end - offset < 7) return null
            fun u(i: Int) = b[offset + i].toInt() and 0xFF
            // 12-bit sync word, then layer 00.
            if (u(0) != 0xFF || (u(1) and 0xF6) != 0xF0) return null
            val sampleRateIndex = (u(2) shr 2) and 0xF
            if (sampleRateIndex >= SAMPLE_RATES.size) return null
            val headerLength = if (u(1) and 1 == 1) 7 else 9
            val frameLength = ((u(3) and 0x3) shl 11) or (u(4) shl 3) or (u(5) shr 5)
            if (frameLength <= headerLength) return null
            return AdtsHeader(
                profile = u(2) shr 6,
                sampleRateIndex = sampleRateIndex,
                channelConfig = ((u(2) and 1) shl 2) or (u(3) shr 6),
                frameLength = frameLength,
                headerLength = headerLength,
            )
        }
    }
}

/**
 * Takes the bytes of an ADTS stream as they arrive and hands back whole frames. A frame whose
 * bytes haven't all arrived yet stays buffered; bytes that aren't a frame header are skipped.
 */
class AdtsFrameReader {
    class Frame(val header: AdtsHeader, val payload: ByteArray)

    private var buf = ByteArray(64 * 1024)
    private var start = 0
    private var end = 0

    fun append(data: ByteArray, count: Int) {
        if (end + count > buf.size) {
            val live = end - start
            if (live + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, live + count))
            System.arraycopy(buf, start, buf, 0, live)
            start = 0
            end = live
        }
        System.arraycopy(data, 0, buf, end, count)
        end += count
    }

    /** The next complete frame, or null until more bytes arrive. */
    fun next(): Frame? {
        while (end - start >= 7) {
            val header = AdtsHeader.parse(buf, start, end)
            if (header == null) {
                start++ // resync
                continue
            }
            if (end - start < header.frameLength) return null
            val payload = buf.copyOfRange(start + header.headerLength, start + header.frameLength)
            start += header.frameLength
            return Frame(header, payload)
        }
        return null
    }
}
