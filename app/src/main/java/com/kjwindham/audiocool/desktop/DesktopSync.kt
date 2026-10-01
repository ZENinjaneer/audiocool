package com.kjwindham.audiocool.desktop

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * Sends sessions to AudioCool Desktop on the same Wi-Fi, where bigger models transcribe them, and
 * brings the transcripts back. Waits in progress are remembered and resumed when the app opens.
 */
object DesktopSync {
    data class Pairing(val url: String, val token: String, val name: String)

    enum class Phase { SENDING, TRANSCRIBING, DONE, FAILED }

    data class Progress(val phase: Phase, val fraction: Float = 0f, val message: String? = null)

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    // The application context, which lives as long as the process, so holding it isn't a leak.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = HashMap<String, Job>()

    @VisibleForTesting
    var pollMs = 2_000L

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        _pairing.value = prefs.desktopPairing.split("\n").takeIf { it.size == 3 }?.let { Pairing(it[0], it[1], it[2]) }
        _progress.value = emptyMap()
        // Transcripts the desktop was still working on when the app last closed.
        for (sessionId in awaiting()) start(sessionId, upload = false)
    }

    /** Checks the address and code with the desktop; keeps the pairing if it answers. */
    suspend fun pair(info: PairingInfo): Result<Pairing> = withContext(Dispatchers.IO) {
        runCatching {
            val desktop = DesktopClient(info.url, info.token).ping()
            Pairing(info.url, info.token, desktop.name).also {
                prefs.desktopPairing = "${it.url}\n${it.token}\n${it.name}"
                _pairing.value = it
            }
        }
    }

    fun unpair() {
        prefs.desktopPairing = ""
        _pairing.value = null
    }

    /** Sends the session to the desktop, has its recordings transcribed there, and saves the results. */
    fun send(sessionId: String) = start(sessionId, upload = true)

    fun clear(sessionId: String) = _progress.update { it - sessionId }

    private fun start(sessionId: String, upload: Boolean) {
        val pairing = _pairing.value ?: return
        synchronized(running) {
            if (running[sessionId]?.isActive == true) return
            running[sessionId] = scope.launch { run(DesktopClient(pairing.url, pairing.token), sessionId, upload) }
        }
    }

    private suspend fun run(client: DesktopClient, sessionId: String, upload: Boolean) {
        try {
            if (upload) {
                report(sessionId, Progress(Phase.SENDING))
                val session = SessionRepository.get(sessionId) ?: return
                // A recording still in progress is sent once it's finished.
                val recordings = session.recordings.filter { it.durationMs > 0 }
                val sizes = recordings.associate { it.file to SessionRepository.audioFile(sessionId, it).length() }
                val needed = client.putSession(session, sizes)
                val total = needed.sumOf { sizes[it] ?: 0L }.coerceAtLeast(1L)
                var done = 0L
                for (name in needed) {
                    val rec = recordings.firstOrNull { it.file == name } ?: continue
                    val file = SessionRepository.audioFile(sessionId, rec)
                    client.upload(sessionId, name, file) { sent -> report(sessionId, Progress(Phase.SENDING, (done + sent).toFloat() / total)) }
                    done += file.length()
                }
                // Don't redo recordings the desktop has already transcribed (e.g. when retrying).
                val remote = client.get(sessionId)
                val missing = recordings.map { it.id }.filter { it !in remote.transcriptModels && remote.jobs.none { j -> j.recordingId == it && j.active } }
                if (missing.isNotEmpty()) client.transcribe(sessionId, recordingIds = missing)
                setAwaiting(sessionId, true)
            }
            while (true) {
                val remote = client.get(sessionId)
                val active = remote.jobs.filter { it.active }
                if (active.isEmpty()) {
                    for (rec in remote.session.recordings) {
                        val model = remote.transcriptModels[rec.id] ?: continue
                        SessionRepository.setTranscript(sessionId, rec.id, rec.transcript.orEmpty(), model)
                    }
                    setAwaiting(sessionId, false)
                    val failed = remote.jobs.lastOrNull { it.status == "error" }
                    report(sessionId, if (failed != null) Progress(Phase.FAILED, message = failed.error ?: "The desktop couldn't transcribe it") else Progress(Phase.DONE))
                    return
                }
                val fraction = remote.jobs.map { if (it.status == "done") 1f else it.progress }.average().toFloat()
                report(sessionId, Progress(Phase.TRANSCRIBING, fraction))
                delay(pollMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(sessionId, Progress(Phase.FAILED, message = e.message ?: "Couldn't reach the desktop. Is it on the same Wi-Fi?"))
        }
    }

    private fun report(sessionId: String, progress: Progress) = _progress.update { it + (sessionId to progress) }

    private fun awaiting(): Set<String> = prefs.desktopAwaiting.split(",").filter { it.isNotBlank() }.toSet()

    private fun setAwaiting(sessionId: String, waiting: Boolean) = synchronized(this) {
        val now = awaiting().let { if (waiting) it + sessionId else it - sessionId }
        prefs.desktopAwaiting = now.joinToString(",")
    }
}
