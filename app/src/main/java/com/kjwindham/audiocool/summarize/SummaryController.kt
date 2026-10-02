package com.kjwindham.audiocool.summarize

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.ChapterSummary
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.SessionSummary
import com.kjwindham.audiocool.transcribe.LiveTranscription
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.Prefs
import com.kjwindham.audiocool.util.defaultSessionTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * Summaries, made on the phone in the background as they become possible: each chapter once it's
 * complete (during a recording, once the live transcript is past the next slide), then the whole
 * session once all of it is. One model call at a time, on a low-priority thread, with fewer threads
 * while recording, so live transcription keeps up; it waits while recordings are being transcribed.
 */
object SummaryController {
    data class State(
        val modelReady: Boolean = false,
        val downloading: Boolean = false,
        val downloadProgress: Float = 0f,
        /** The session being summarized, and how far through its parts. */
        val sessionId: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        /** Where the model runs ("GPU", "CPU (4 threads)") and its last speed, once it has. */
        val where: String? = null,
        val speed: Pair<Double, Double>? = null,
        val error: String? = null,
    )

    const val MODEL = "${SummaryModel.ID}@phone"
    private const val TAG = "SummaryController"
    private const val IDLE_CLOSE_MS = 60_000L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** Downloading or summarizing; [SummaryService] stays in the foreground until this is false. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Stands in for the model in tests. */
    @VisibleForTesting
    var summarizerForTest: (() -> Summarizer)? = null

    /** Uses [summarizer] in place of the model, as if it were downloaded; null goes back to the real one. */
    @VisibleForTesting
    fun useForTest(summarizer: (() -> Summarizer)?) {
        summarizerForTest = summarizer
        failed.clear()
        worker.execute(::closeSummarizer)
        _state.update { it.copy(modelReady = summarizer != null || SummaryModel.isReady(app)) }
    }

    // The application context, which lives as long as the process, so holding it isn't a leak.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "summaries")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    // Touched only on the worker thread.
    private var summarizer: Summarizer? = null
    private var summarizerThreads = 0
    @Volatile private var running = false
    @Volatile private var cancelDownload = false
    // Work that failed, by basis, so it isn't retried in a loop.
    private val failed = HashSet<String>()

    val enabled: Boolean get() = prefs.summaries

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        _state.update { it.copy(modelReady = SummaryModel.isReady(app)) }
        // A fresh start: nothing can be due from before (tests start the app over in the same process).
        main.removeCallbacks(kick)
        kickPending = false
        watcher?.cancel()
        watcher = scope.launch {
            // Only what can make something ready to summarize: not the recorder's timer and level, which
            // change ten times a second, nor whether someone is speaking right now.
            combine(
                SessionRepository.sessions,
                RecorderController.state.map { it.status to it.recId }.distinctUntilChanged(),
                TranscriptionController.state.map { Triple(it.phase, it.current, it.queue.size) }.distinctUntilChanged(),
                LiveTranscription.state.map { it.recId }.distinctUntilChanged(),
            ) { _, _, _, _ -> }.collect { schedule() }
        }
    }

    fun setEnabled(on: Boolean) {
        prefs.summaries = on
        if (on) prefs.summaryNoSpeculative = false
        schedule()
    }

    /** Whether to run the model on the GPU (faster where it works; it falls back to the CPU if it doesn't). */
    var useGpu: Boolean
        get() = prefs.summaryGpu == "on"
        set(value) {
            prefs.summaryGpu = if (value) "on" else "off"
            worker.execute(::closeSummarizer)
        }

    /** The GPU was tried and didn't work. */
    val gpuFailed: Boolean get() = prefs.summaryGpu == "failed"

    /**
     * Looks for something to summarize a moment from now (changes come in bursts). A look already due
     * stays due, so a steady stream of changes can't put it off for good.
     */
    fun schedule() {
        if (kickPending) return
        kickPending = true
        main.postDelayed(kick, 1_500)
    }

    private var kickPending = false

    private val kick = Runnable {
        kickPending = false
        if (!running && _state.value.modelReady && enabled) {
            running = true
            worker.execute(::work)
        }
    }

    /** Runs after everything handed to the worker so far; for tests. */
    @VisibleForTesting
    fun afterQueued(block: () -> Unit) = worker.execute(block)

    // ---- Downloading the model ----

    fun download() {
        if (_state.value.downloading || _state.value.modelReady) return
        cancelDownload = false
        _state.update { it.copy(downloading = true, downloadProgress = 0f, error = null) }
        _busy.value = true
        SummaryService.start(app)
        worker.execute {
            val error = try {
                SummaryModel.download(app, { cancelDownload }) { done, total ->
                    _state.update { it.copy(downloadProgress = done.toFloat() / total) }
                }
                null
            } catch (e: CancellationException) {
                null
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed", e)
                "Couldn't download the summary model: ${e.message ?: e.javaClass.simpleName}"
            }
            val ready = SummaryModel.isReady(app)
            _state.update { it.copy(downloading = false, modelReady = ready, error = error) }
            if (ready) prefs.summaries = true
            _busy.value = false
            main.post { schedule() }
        }
    }

    fun cancelDownload() {
        cancelDownload = true
    }

    fun deleteModel() {
        worker.execute {
            closeSummarizer()
            SummaryModel.delete(app)
            _state.update { it.copy(modelReady = false, where = null, speed = null) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---- The work ----

    private sealed interface Work {
        val sessionId: String
        val basis: String

        class Chapter(override val sessionId: String, val chapter: com.kjwindham.audiocool.summarize.Chapter, val prompt: String, override val basis: String, val all: Set<String>) : Work
        class Whole(override val sessionId: String, val prompt: String, override val basis: String) : Work
    }

    private fun work() {
        var serviceStarted = false
        try {
            while (true) {
                if (!enabled || !SummaryModel.isReady(app) && summarizerForTest == null) break
                val next = nextWork() ?: break
                _busy.value = true
                if (!serviceStarted && RecorderController.state.value.status == RecorderController.Status.IDLE) {
                    // Keeps the app alive to finish when it's closed; while recording, the recording does that.
                    main.post { SummaryService.start(app) }
                    serviceStarted = true
                }
                perform(next)
            }
        } finally {
            running = false
            main.post { schedule() }
            _state.update { it.copy(sessionId = null, done = 0, total = 0) }
            if (!_state.value.downloading) _busy.value = false
            // Free the model's memory if nothing else turns up for a while.
            main.post {
                main.removeCallbacks(closeWhenIdle)
                main.postDelayed(closeWhenIdle, IDLE_CLOSE_MS)
            }
        }
    }

    private val closeWhenIdle = Runnable { if (!running) worker.execute(::closeSummarizer) }

    private fun closeSummarizer() {
        runCatching { summarizer?.close() }
        summarizer = null
    }

    private fun perform(work: Work) {
        val model = try {
            summarizerFor(threads = if (RecorderController.state.value.status == RecorderController.Status.IDLE) 4 else 2)
        } catch (e: Throwable) {
            Log.e(TAG, "Couldn't start the summary model", e)
            failed += work.basis
            _state.update { it.copy(error = "The summary model wouldn't start: ${e.message ?: e.javaClass.simpleName}") }
            return
        }
        try {
            when (work) {
                is Work.Chapter -> {
                    val text = SummaryPrompts.cleanChapter(model.reply(work.prompt, SummaryPrompts.CHAPTER_TOKENS)).orEmpty()
                    SessionRepository.setChapterSummary(work.sessionId, ChapterSummary(work.chapter.key, text, work.basis, MODEL), work.all)
                }
                is Work.Whole -> {
                    val parsed = SummaryPrompts.parseSession(model.reply(work.prompt, SummaryPrompts.SESSION_TOKENS))
                    if (parsed == null) {
                        failed += work.basis
                    } else {
                        SessionRepository.setSessionSummary(
                            work.sessionId,
                            SessionSummary(parsed.summary, parsed.keyPoints, parsed.actionItems, parsed.title, work.basis, MODEL, System.currentTimeMillis()),
                        )
                        // Like a slide's title, a summary's title only replaces the date-and-time name.
                        val session = SessionRepository.get(work.sessionId)
                        if (session != null && parsed.title != null && session.title == defaultSessionTitle(session.createdAt)) {
                            SessionRepository.rename(work.sessionId, parsed.title)
                        }
                    }
                }
            }
            _state.update { it.copy(where = model.where, speed = model.lastSpeed ?: it.speed, done = it.done + 1, error = null) }
        } catch (e: android.os.DeadObjectException) {
            engineCrashed(e)
        } catch (e: Throwable) {
            Log.e(TAG, "Summarizing failed", e)
            failed += work.basis
            // A model that's failed may be in a bad state: start afresh next time.
            closeSummarizer()
        }
    }

    /**
     * The model, in its own process (see [SummaryEngineService]), with [threads] CPU threads; reopened
     * if the count should change, as when recording stops. The GPU only if asked for, and speculative
     * decoding until it's been the cause of a crash.
     */
    private fun summarizerFor(threads: Int): Summarizer {
        summarizerForTest?.let { return summarizer ?: it().also { s -> summarizer = s } }
        summarizer?.let { s -> if (summarizerThreads == threads) return s }
        closeSummarizer()
        val opened = RemoteSummarizer.open(app, gpu = prefs.summaryGpu == "on", threads = threads, speculative = !prefs.summaryNoSpeculative)
        summarizer = opened
        summarizerThreads = threads
        return opened
    }

    /**
     * The model's process died mid-way: a crash in the engine. Try more carefully next time (without the
     * GPU, then without speculative decoding); after that, turn summaries off rather than crash over and over.
     */
    private fun engineCrashed(e: Throwable) {
        Log.e(TAG, "The summary model's process died", e)
        // Let go of it, or Android starts its process again for the connection that's left.
        closeSummarizer()
        val error = when {
            prefs.summaryGpu == "on" -> {
                prefs.summaryGpu = "failed"
                "The summary model didn't work on this phone's GPU; it'll use the CPU from now on."
            }
            !prefs.summaryNoSpeculative -> {
                prefs.summaryNoSpeculative = true
                null // quietly try again the plain way
            }
            else -> {
                prefs.summaries = false
                "Summaries keep failing on this phone, so they're off for now. ⋮ › Share the app's log has the details."
            }
        }
        _state.update { it.copy(error = error ?: it.error) }
    }

    /** The next thing to summarize: chapters of the session being recorded first, then other sessions, newest first. */
    private fun nextWork(): Work? {
        val rec = RecorderController.state.value
        val transcription = TranscriptionController.state.value
        // Transcribing in the background comes first: summaries need it, and both are heavy.
        if (transcription.phase == TranscriptionController.Phase.TRANSCRIBING) return null
        val sessions = SessionRepository.sessions.value.sortedWith(
            compareByDescending<Session> { it.id == rec.sessionId && rec.status != RecorderController.Status.IDLE }.thenByDescending { it.updatedAt },
        )
        for (session in sessions) {
            val chapters = chapters(session) { settledUntil(session, it, rec, transcription) }.filterNot { it.slight }
            if (chapters.isEmpty()) continue
            val keys = chapters.map { it.key }.toSet()
            if (chapters.size > 1) {
                for (c in chapters) {
                    if (!c.complete) continue
                    val prompt = SummaryPrompts.chapter(c)
                    val basis = SummaryPrompts.basis(prompt)
                    if (basis in failed || session.chapterSummaries.any { it.key == c.key && it.basis == basis }) continue
                    report(session, chapters)
                    return Work.Chapter(session.id, c, prompt, basis, keys)
                }
            }
            val recordingHere = rec.sessionId == session.id && rec.status != RecorderController.Status.IDLE
            if (recordingHere || chapters.any { !it.complete }) continue
            val summaries = session.chapterSummaries.filter { it.text.isNotBlank() }.associate { it.key to it.text }
            if (chapters.size > 1 && chapters.any { c -> session.chapterSummaries.none { it.key == c.key } }) continue
            val prompt = SummaryPrompts.session(session, chapters, summaries)
            val basis = SummaryPrompts.basis(prompt)
            if (basis in failed || session.summary?.basis == basis) continue
            report(session, chapters)
            return Work.Whole(session.id, prompt, basis)
        }
        return null
    }

    private fun report(session: Session, chapters: List<Chapter>) {
        val summarized = chapters.count { c -> session.chapterSummaries.any { it.key == c.key } }
        val total = (if (chapters.size > 1) chapters.size else 0) + 1
        _state.update { it.copy(sessionId = session.id, done = summarized, total = total) }
    }

    /** How much of [rec]'s transcript is final: see [chapters]. */
    private fun settledUntil(session: Session, rec: Recording, recorder: RecorderController.State, transcription: TranscriptionController.State): Long? {
        val live = LiveTranscription.state.value
        val transcribingLive = live.active && live.recId == rec.id
        val recordingIt = recorder.status != RecorderController.Status.IDLE && recorder.recId == rec.id
        return when {
            transcribingLive -> rec.transcript?.lastOrNull()?.endMs ?: 0L
            recordingIt -> -1L // recorded without live transcription: it's transcribed afterwards
            transcription.isPending(session.id, rec.id) || rec.transcript == null -> -1L
            else -> null
        }
    }

    /** Whether [session] has summaries still to come (to show they're on the way). */
    fun pending(session: Session): Boolean {
        if (!enabled || !_state.value.modelReady) return false
        val rec = RecorderController.state.value
        val chapters = chapters(session) { settledUntil(session, it, rec, TranscriptionController.state.value) }.filterNot { it.slight }
        if (chapters.isEmpty()) return false
        val chaptersDone = chapters.size == 1 || chapters.all { c -> session.chapterSummaries.any { it.key == c.key } }
        return !chaptersDone || session.summary == null
    }
}
