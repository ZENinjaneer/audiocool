package com.kjwindham.audiocool.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.newId
import com.kjwindham.audiocool.transcribe.LiveTranscription
import com.kjwindham.audiocool.transcribe.SpeechModel
import com.kjwindham.audiocool.transcribe.Transcriber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Spoken notes. While the button is held it listens with the headset's mic (the phone's, without a
 * headset), then transcribes what was said on the phone and adds it as a note linked to the moment
 * you started speaking. A recording in progress carries on from the phone's mic throughout.
 */
object Dictation {
    data class State(
        val listening: Boolean = false,
        val transcribing: Boolean = false,
        /** The mic it's listening with, e.g. "headset mic". */
        val mic: String? = null,
        /** Input level, 0..1. */
        val level: Float = 0f,
        /** Why the last note didn't work out, to show once; see [clearProblem]. */
        val problem: String? = null,
    )

    private const val RATE = Transcriber.SAMPLE_RATE
    private const val VAD_ASSET = "silero_vad.onnx"
    private const val THREADS = 2
    private const val MAX_MS = 120_000L

    /** How long to listen before checking that the recording still has its mic. */
    private const val CHECK_AFTER_MS = 500L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Stands in for the speech model in tests. */
    @VisibleForTesting
    var recognizeForTest: ((FloatArray) -> String)? = null

    private class Take(
        val sessionId: String,
        val recId: String?,
        val offsetMs: Long?,
        val record: AudioRecord,
        val mic: AudioDeviceInfo?,
        val communicationDevice: Boolean,
        /** The mic the recording was using when this started, to tell if listening took it away. */
        val roomMic: AudioDeviceInfo?,
    ) {
        val chunks = ArrayList<ShortArray>()

        @Volatile
        var stopped = false

        @Volatile
        var problem: String? = null
        lateinit var reader: Thread
    }

    private var take: Take? = null

    // Set when this phone couldn't record from the headset and the phone mic at once; dictation
    // then uses the phone's mic for the rest of the recording.
    @Volatile
    private var phoneMicOnly = false
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dictation") }
    private var ownRecognizer: OfflineRecognizer? = null // used only on the worker

    /**
     * Starts listening; the note will link to [recId] at [offsetMs] (both null for an unlinked note).
     * Returns false if the mic couldn't be opened.
     */
    @SuppressLint("MissingPermission") // only offered once the app can record
    fun start(context: Context, sessionId: String, recId: String?, offsetMs: Long?): Boolean {
        if (take != null) return false
        val app = context.applicationContext
        val recording = RecorderController.state.value.status != RecorderController.Status.IDLE
        // Use the mic the recording isn't using: normally the headset's.
        val external = Mics.external(app)?.takeIf { !(recording && (phoneMicOnly || RecorderController.state.value.usingExternalMic)) }
        // Bluetooth mics have to be switched on as the "communication device" (Android 12 and later).
        val mic = external?.takeIf { !Mics.isBluetooth(it) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S } ?: Mics.phone(app)
        val bluetooth = mic != null && Mics.isBluetooth(mic)
        val audio = app.getSystemService(AudioManager::class.java)
        if (bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.setCommunicationDevice(mic!!)
        val source = if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        } catch (e: Exception) {
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            if (bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
            _state.update { it.copy(problem = "Couldn't open the ${Mics.label(mic)}.") }
            return false
        }
        mic?.let { record.setPreferredDevice(it) }
        val t = Take(sessionId, recId, offsetMs, record, mic, communicationDevice = bluetooth, roomMic = if (recording) RecorderController.activeMic() else null)
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            if (bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
            _state.update { it.copy(problem = "Couldn't open the ${Mics.label(mic)}.") }
            return false
        }
        take = t
        _state.update { it.copy(listening = true, mic = Mics.label(mic), level = 0f, problem = null) }
        t.reader = thread(name = "dictation-mic") { listen(t, recording) }
        return true
    }

    /** Stops listening and adds what was said as a note (in the background). */
    fun stop(context: Context) {
        val t = take ?: return
        take = null
        t.stopped = true
        t.reader.join(2_000)
        runCatching { t.record.stop() }
        t.record.release()
        if (t.communicationDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AudioManager::class.java).clearCommunicationDevice()
        }
        val samples = t.samples()
        if (t.problem == null && samples.size >= RATE / 4 && samples.all { it == 0f }) {
            t.problem = "The ${Mics.label(t.mic)} didn't pick up any sound."
        }
        if (t.problem != null || samples.size < RATE / 4) {
            _state.update { it.copy(listening = false, level = 0f, problem = t.problem) }
            return
        }
        _state.update { it.copy(listening = false, transcribing = true, level = 0f) }
        val app = context.applicationContext
        worker.execute {
            val text = runCatching { transcribe(app, samples) }.getOrDefault("").trim()
            if (text.isNotEmpty()) {
                SessionRepository.addNote(t.sessionId, Note(newId(), text, System.currentTimeMillis(), t.recId, t.offsetMs, spoken = true))
            }
            _state.update { it.copy(transcribing = false, problem = if (text.isEmpty()) "Didn't catch that. Try again, a little closer to the mic." else it.problem) }
        }
    }

    fun clearProblem() = _state.update { it.copy(problem = null) }

    /** The recording ended: finish any note in progress, then free the speech model if dictation loaded its own. */
    fun recordingStopped(context: Context) {
        stop(context)
        phoneMicOnly = false
        worker.execute {
            ownRecognizer?.release()
            ownRecognizer = null
        }
    }

    private fun listen(t: Take, recording: Boolean) {
        val buffer = ShortArray(RATE / 10)
        val started = SystemClock.elapsedRealtime()
        var checked = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        while (!t.stopped) {
            val n = t.record.read(buffer, 0, buffer.size)
            if (n < 0) {
                t.problem = "The ${Mics.label(t.mic)} stopped working."
                break
            }
            if (n == 0) continue
            t.chunks += buffer.copyOf(n)
            _state.update { it.copy(level = level(buffer, n)) }
            val elapsed = SystemClock.elapsedRealtime() - started
            if (!checked && elapsed >= CHECK_AFTER_MS) {
                checked = true
                val problem = check(t, recording)
                if (problem != null) {
                    t.problem = problem
                    runCatching { t.record.stop() }
                    _state.update { it.copy(listening = false, level = 0f, problem = problem) }
                    break
                }
            }
            if (elapsed >= MAX_MS) {
                runCatching { t.record.stop() }
                break
            }
        }
    }

    /**
     * After a moment's listening: whether that cost a recording in progress its mic (some phones can
     * only use one mic at a time) or Android is keeping the mic from us. Also shows which mic Android
     * actually gave us, which may not be the one asked for.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun check(t: Take, recording: Boolean): String? {
        if (recording) {
            val roomMic = RecorderController.activeMic()
            if (RecorderController.isSilenced() == true || (t.roomMic != null && roomMic != null && roomMic.id != t.roomMic.id)) {
                phoneMicOnly = true
                return "This phone can't record from the headset and its own mic at once, so the note was dropped " +
                    "to protect the recording. Spoken notes will use the phone's mic for the rest of this recording."
            }
        }
        val config = t.record.activeRecordingConfiguration ?: return null
        if (config.isClientSilenced) return "Android didn't let AudioCool hear the ${Mics.label(t.mic)}."
        config.audioDevice?.let { actual -> _state.update { it.copy(mic = Mics.label(actual)) } }
        return null
    }

    private fun Take.samples(): FloatArray {
        val out = FloatArray(chunks.sumOf { it.size })
        var i = 0
        for (c in chunks) for (s in c) out[i++] = s / 32768f
        return out
    }

    private fun level(buffer: ShortArray, n: Int): Float {
        var power = 0.0
        for (i in 0 until n) power += buffer[i].toDouble() * buffer[i]
        val rms = sqrt(power / n) / 32768
        // -60 dBFS..-10 dBFS onto 0..1.
        return if (rms <= 0) 0f else ((20 * log10(rms) + 60) / 50).toFloat().coerceIn(0f, 1f)
    }

    private fun transcribe(app: Context, samples: FloatArray): String {
        recognizeForTest?.let { return it(samples) }
        // Use the model live transcription already has loaded, else load one for the rest of the recording.
        return LiveTranscription.withRecognizer { recognize(app, it, samples) }
            ?: recognize(app, ownRecognizer ?: OfflineRecognizer(null, SpeechModel.recognizerConfig(app, THREADS)).also { ownRecognizer = it }, samples)
    }

    private fun recognize(app: Context, recognizer: OfflineRecognizer, samples: FloatArray): String {
        val vad = Vad(app.assets, Transcriber.vadConfig(VAD_ASSET))
        try {
            val transcriber = Transcriber(recognizer, vad)
            transcriber.accept(samples)
            return transcriber.finish().joinToString(" ") { it.text }
        } finally {
            vad.release()
        }
    }
}
