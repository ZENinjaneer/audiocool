package com.kjwindham.audiocool.audio

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.transcribe.AudioDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.log10

/**
 * How loud each recording is along its length, for the waveform you scrub on: one level per
 * [STEP_MS], 0..255 on the recording meter's scale (-50..0 dBFS). A recording keeps the levels its
 * meter saw while recording; older ones are measured from the audio file once, in the background.
 * Either way they're saved next to the audio as levels-<recording id>.bin.
 */
object Waveform {
    const val STEP_MS = 100L
    private const val TAG = "Waveform"

    private val _levels = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    /** Levels by recording id, for the recordings asked for with [request] (once they're ready). */
    val levels: StateFlow<Map<String, ByteArray>> = _levels.asStateFlow()

    /** Stands in for decoding the audio in tests. */
    @VisibleForTesting
    var measureForTest: ((File) -> ByteArray)? = null

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "waveform").apply { priority = Thread.MIN_PRIORITY } }
    private val requested = ConcurrentHashMap.newKeySet<String>()

    fun file(sessionId: String, recId: String) = File(SessionRepository.sessionDir(sessionId), "levels-$recId.bin")

    /** Makes [rec]'s levels available in [levels]: read from their file, or else measured from the audio. */
    fun request(sessionId: String, rec: Recording) {
        if (rec.id in _levels.value || !requested.add(rec.id)) return
        worker.execute {
            val saved = file(sessionId, rec.id)
            val levels = if (saved.isFile && saved.length() > 0) {
                saved.readBytes()
            } else {
                measure(SessionRepository.audioFile(sessionId, rec))?.also { write(saved, it) }
            }
            if (levels != null) _levels.update { it + (rec.id to levels) } else requested.remove(rec.id)
        }
    }

    /** Runs after everything asked for so far is done; for tests. */
    @VisibleForTesting
    fun afterQueued(block: () -> Unit) = worker.execute(block)

    // The levels of the recording in progress, kept by RecorderController's meter.
    private val live = ByteArrayOutputStream()
    private var liveRecId: String? = null

    @Synchronized
    fun liveStart(recId: String) {
        live.reset()
        liveRecId = recId
    }

    /** The meter's [level] (0..1) at [offsetMs] into the recording in progress. */
    @Synchronized
    fun liveLevel(offsetMs: Long, level: Float) {
        if (liveRecId == null) return
        val value = (level.coerceIn(0f, 1f) * 255).toInt()
        val index = (offsetMs / STEP_MS).toInt()
        val bytes = live.size()
        // Readings arrive about once per step; a late one fills the gap so the levels stay in step with the audio.
        if (index >= bytes) repeat(index - bytes + 1) { live.write(value) }
    }

    /** The recording stopped: save what its meter saw. */
    @Synchronized
    fun liveStop(sessionId: String) {
        val recId = liveRecId ?: return
        liveRecId = null
        val levels = live.toByteArray()
        live.reset()
        if (levels.isEmpty()) return
        _levels.update { it + (recId to levels) }
        requested += recId
        worker.execute { write(file(sessionId, recId), levels) }
    }

    private fun write(file: File, levels: ByteArray) {
        runCatching { file.writeBytes(levels) }.onFailure { Log.w(TAG, "Couldn't save ${file.name}", it) }
    }

    private fun measure(audio: File): ByteArray? {
        measureForTest?.let { return it(audio) }
        if (!audio.isFile || audio.length() == 0L) return null
        return try {
            val out = ByteArrayOutputStream()
            val perStep = (MEASURE_RATE * STEP_MS / 1000).toInt()
            var peak = 0f
            var count = 0
            AudioDecoder(audio, MEASURE_RATE).decode(isCancelled = { false }, onProgress = {}) { chunk ->
                for (sample in chunk) {
                    peak = maxOf(peak, abs(sample))
                    if (++count == perStep) {
                        out.write(levelOf(peak))
                        peak = 0f
                        count = 0
                    }
                }
            }
            if (count > 0) out.write(levelOf(peak))
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't measure ${audio.name}", e)
            null
        }
    }

    /** A peak (0..1) on the meter's scale, -50..0 dBFS, as 0..255. */
    private fun levelOf(peak: Float): Int =
        if (peak <= 0f) 0 else (((20 * log10(peak) + 50f) / 50f).coerceIn(0f, 1f) * 255).toInt()

    // Plenty for a picture of loudness, and quick to decode down to.
    private const val MEASURE_RATE = 8_000
}
