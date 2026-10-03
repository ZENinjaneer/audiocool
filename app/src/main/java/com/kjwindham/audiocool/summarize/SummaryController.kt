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
import com.kjwindham.audiocool.data.FolderRepository
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.SessionSummary
import com.kjwindham.audiocool.data.folderSummaries
import com.kjwindham.audiocool.search.MeaningIndex
import com.kjwindham.audiocool.search.MeaningModel
import com.kjwindham.audiocool.transcribe.LiveTranscription
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.Prefs
import com.kjwindham.audiocool.util.defaultSessionTitle
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
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
        MeaningIndex.init(app)
        meaningBroken = false
        _meaning.value = MeaningState(ready = MeaningModel.isReady(app))
        // A fresh start: nothing can be due from before (tests start the app over in the same process).
        main.removeCallbacks(kick)
        kickPending = false
        watcher?.cancel()
        watcher = scope.launch {
            // Only what can make something ready to summarize: not the recorder's timer and level, which
            // change ten times a second, nor whether someone is speaking right now.
            combine(
                SessionRepository.sessions,
                FolderRepository.folders,
                RecorderController.state.map { it.status to it.recId }.distinctUntilChanged(),
                TranscriptionController.state.map { Triple(it.phase, it.current, it.queue.size) }.distinctUntilChanged(),
                LiveTranscription.state.map { it.recId }.distinctUntilChanged(),
            ) { _, _, _, _, _ -> }.collect { schedule() }
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

    // ---- Searching by meaning ----

    /** Search by meaning: whether its model is here (or on its way), and how many sessions are indexed. */
    data class MeaningState(
        val ready: Boolean = false,
        val downloading: Boolean = false,
        val progress: Float = 0f,
        val indexed: Int = 0,
        /** Indexing sessions right now. */
        val indexing: Boolean = false,
        val error: String? = null,
    )

    private val _meaning = MutableStateFlow(MeaningState())
    val meaning: StateFlow<MeaningState> = _meaning.asStateFlow()

    @Volatile
    private var meaningBroken = false

    /** Stands in for the meaning model in tests (with a [summarizerForTest] that can embed). */
    @VisibleForTesting
    var meaningForTest = false
        set(value) {
            field = value
            _meaning.update { it.copy(ready = value || MeaningModel.isReady(app)) }
        }

    private const val INDEX_BATCH = 32

    @Volatile
    private var cancelMeaningDownload = false

    /** Downloads the meaning model (332 MB), then indexes the sessions in the background. */
    fun downloadMeaning() {
        if (_meaning.value.downloading || _meaning.value.ready) return
        cancelMeaningDownload = false
        _meaning.update { it.copy(downloading = true, progress = 0f, error = null) }
        _busy.value = true
        SummaryService.start(app)
        worker.execute {
            val error = try {
                MeaningModel.download(app, { cancelMeaningDownload }) { p -> _meaning.update { it.copy(progress = p) } }
                null
            } catch (e: Exception) {
                if (cancelMeaningDownload) null else "Couldn't download the meaning model: ${e.message ?: e.javaClass.simpleName}"
            }
            _meaning.update { it.copy(downloading = false, ready = MeaningModel.isReady(app), error = error) }
            _busy.value = false
            main.post { schedule() }
        }
    }

    fun cancelMeaningDownload() {
        cancelMeaningDownload = true
    }

    fun deleteMeaning() = worker.execute {
        closeSummarizer()
        MeaningModel.delete(app)
        SessionRepository.sessions.value.forEach { MeaningIndex.remove(it.id) }
        _meaning.value = MeaningState()
    }

    /**
     * What [query] means, for searching by meaning; [onVector] gets it (or null) on the worker thread.
     * Ahead of everything else: someone's waiting.
     */
    fun meaningOf(query: String, onVector: (FloatArray?) -> Unit) {
        if (!meaningOn) return onVector(null)
        urgent.removeAll { it is Work.Meaning }
        urgent += Work.Meaning(query, onVector)
        main.post {
            if (!running) {
                running = true
                worker.execute(::work)
            }
        }
    }

    // ---- Asking about a session ----

    /** A question about a session, and its answer once the model has one. */
    data class Answer(
        val sessionId: String,
        val question: String,
        val text: String? = null,
        /** The moments the answer came from. */
        val moments: List<Moment> = emptyList(),
        val thinking: Boolean = true,
        /** Nothing in the session had anything to do with the question. */
        val nothingFound: Boolean = false,
        val error: String? = null,
    )

    private val _answer = MutableStateFlow<Answer?>(null)
    val answer: StateFlow<Answer?> = _answer.asStateFlow()

    /** Whether a question can be asked: summaries are on and the model is here. */
    val canAsk: Boolean get() = enabled && (_state.value.modelReady || summarizerForTest != null)

    /** What someone's waiting for (questions, look-ups), done before the summaries. */
    private val urgent = java.util.concurrent.ConcurrentLinkedQueue<Work>()

    /**
     * Answers [question] from the session: the excerpts most to do with it, read by the summary model,
     * before anything else it has to do (someone's waiting).
     */
    fun ask(sessionId: String, question: String) {
        val session = SessionRepository.get(sessionId) ?: return
        val excerpts = AskPrompts.relevant(AskPrompts.passages(session), question)
        // Only the latest question counts.
        urgent.removeAll { it is Work.Ask }
        if (excerpts.isEmpty()) {
            _answer.value = Answer(sessionId, question, thinking = false, nothingFound = true)
            return
        }
        _answer.value = Answer(sessionId, question)
        urgent += Work.Ask(sessionId, question, excerpts, AskPrompts.prompt(session, question, excerpts))
        startUrgent()
    }

    fun clearAnswer() {
        urgent.removeAll { it is Work.Ask }
        _answer.value = null
    }

    /**
     * Asks the model [prompt] straight away (before the summaries); [onReply] gets the reply, or null if
     * it couldn't, on the worker thread.
     */
    fun request(prompt: String, maxTokens: Int, onReply: (String?) -> Unit) {
        urgent += Work.Request(prompt, maxTokens, onReply)
        startUrgent()
    }

    private fun startUrgent() = main.post {
        if (!running && enabled) {
            running = true
            worker.execute(::work)
        }
    }

    private fun answerFailed(work: Work.Ask) = _answer.update { a ->
        if (a?.sessionId == work.sessionId && a.question == work.question) a.copy(thinking = false, error = "Couldn't answer that just now.") else a
    }

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
        if (!running && (_state.value.modelReady && enabled || meaningOn)) {
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

        /** Which of [sessionIds] (none in a folder) go together. */
        class Suggest(val sessionIds: List<String>, val prompt: String, override val basis: String) : Work {
            override val sessionId = ""
        }

        /** Which of [folders] the new session belongs in. */
        class File(override val sessionId: String, val folders: List<String>, val prompt: String, override val basis: String) : Work

        /** A question about the session, from [excerpts]. */
        class Ask(override val sessionId: String, val question: String, val excerpts: List<Passage>, val prompt: String) : Work {
            override val basis = ""
        }

        /** Anything else someone's waiting for: a look-up, an explanation. */
        class Request(val prompt: String, val maxTokens: Int, val onReply: (String?) -> Unit) : Work {
            override val sessionId = ""
            override val basis = ""
        }

        /** What a search means ([onVector] gets it, or null), for searching by meaning. */
        class Meaning(val query: String, val onVector: (FloatArray?) -> Unit) : Work {
            override val sessionId = ""
            override val basis = ""
        }

        /** [passages] of the session to add to its index for searching by meaning. */
        class Index(override val sessionId: String, val passages: List<MeaningIndex.Passage>) : Work {
            override val basis = "index:$sessionId"
        }
    }

    private fun work() {
        var serviceStarted = false
        try {
            while (true) {
                if (!summariesOn && !meaningOn) break
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
            when (work) {
                is Work.Ask -> answerFailed(work)
                is Work.Request -> work.onReply(null)
                is Work.Meaning -> work.onVector(null)
                else -> failed += work.basis
            }
            if (work is Work.Suggest) Organizer.noneFound()
            _state.update { it.copy(error = "The summary model wouldn't start: ${e.message ?: e.javaClass.simpleName}") }
            return
        }
        try {
            when (work) {
                is Work.Chapter -> {
                    val (title, text) = SummaryPrompts.parseChapter(model.reply(work.prompt, SummaryPrompts.CHAPTER_TOKENS)) ?: (null to "")
                    SessionRepository.setChapterSummary(work.sessionId, ChapterSummary(work.chapter.key, text, work.basis, MODEL, title), work.all)
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
                is Work.Suggest -> {
                    val folders = folderSummaries(SessionRepository.sessions.value, FolderRepository.folders.value)
                    val suggested = OrganizePrompts.parseSuggestions(model.reply(work.prompt, OrganizePrompts.SUGGEST_TOKENS), work.sessionIds, folders)
                    prefs.suggestBasis = work.basis
                    if (suggested.isNullOrEmpty()) Organizer.noneFound() else Organizer.offer(suggested)
                }
                is Work.Request -> work.onReply(model.reply(work.prompt, work.maxTokens))
                is Work.Meaning -> work.onVector(model.embed(MeaningModel.file(app).path, listOf(work.query)).firstOrNull())
                is Work.Index -> {
                    _meaning.update { it.copy(indexing = true) }
                    try {
                        val vectors = model.embed(MeaningModel.file(app).path, work.passages.map { it.text })
                        SessionRepository.get(work.sessionId)?.let { MeaningIndex.store(it, work.passages, vectors) }
                    } finally {
                        _meaning.update { it.copy(indexing = false, indexed = SessionRepository.sessions.value.count { s -> MeaningIndex.size(s.id) > 0 }) }
                    }
                }
                is Work.Ask -> {
                    val (text, moments) = AskPrompts.parse(model.reply(work.prompt, AskPrompts.ANSWER_TOKENS), work.excerpts)
                    // Unless another question has been asked meanwhile.
                    _answer.update { a -> if (a?.sessionId == work.sessionId && a.question == work.question) a.copy(text = text, moments = moments, thinking = false) else a }
                }
                is Work.File -> {
                    val number = OrganizePrompts.parseFile(model.reply(work.prompt, OrganizePrompts.FILE_TOKENS), work.folders.size)
                    if (number == null) {
                        failed += work.basis
                    } else {
                        prefs.autoFileDone = prefs.autoFileDone + work.sessionId
                        val session = SessionRepository.get(work.sessionId)
                        // Unless it was filed meanwhile, by hand.
                        if (number > 0 && session != null && session.folder == null) {
                            val folder = work.folders[number - 1]
                            SessionRepository.moveToFolder(work.sessionId, folder)
                            Organizer.filed(Organizer.Filed(work.sessionId, folder))
                        }
                    }
                }
            }
            val counts = work is Work.Chapter || work is Work.Whole
            _state.update { it.copy(where = model.where, speed = model.lastSpeed ?: it.speed, done = it.done + if (counts) 1 else 0, error = if (counts) null else it.error) }
        } catch (e: android.os.DeadObjectException) {
            if (work is Work.Suggest) Organizer.noneFound()
            if (work is Work.Ask) answerFailed(work)
            if (work is Work.Request) work.onReply(null)
            if (work is Work.Meaning) work.onVector(null)
            if (work is Work.Meaning || work is Work.Index) {
                // The meaning model took its process down: it's off until the app starts again.
                Log.e(TAG, "The meaning model's process died", e)
                closeSummarizer()
                meaningBroken = true
                _meaning.update { it.copy(error = "Search by meaning stopped working on this phone.") }
            } else {
                engineCrashed(e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Summarizing failed", e)
            if (work is Work.Suggest) Organizer.noneFound()
            when (work) {
                is Work.Ask -> answerFailed(work)
                is Work.Request -> work.onReply(null)
                is Work.Meaning -> work.onVector(null)
                else -> failed += work.basis
            }
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
        // Questions and look-ups first, even while transcribing: someone's waiting for them.
        urgent.poll()?.let { return it }
        val rec = RecorderController.state.value
        val transcription = TranscriptionController.state.value
        // Transcribing in the background comes first: summaries need it, and both are heavy.
        if (transcription.phase == TranscriptionController.Phase.TRANSCRIBING) return null
        return (if (summariesOn) summaryWork(rec, transcription) else null) ?: indexing(rec, transcription)
    }

    /** Summaries are on and their model is here. */
    private val summariesOn: Boolean get() = enabled && (SummaryModel.isReady(app) || summarizerForTest != null)

    /** The meaning model is here and working (search by meaning). */
    private val meaningOn: Boolean get() = !meaningBroken && (MeaningModel.isReady(app) || meaningForTest)

    /**
     * Search by meaning: the next passages to index, from the session changed last whose transcript is
     * settled (not being recorded or transcribed).
     */
    private fun indexing(rec: RecorderController.State, transcription: TranscriptionController.State): Work? {
        if (!meaningOn) return null
        for (session in SessionRepository.sessions.value.sortedByDescending { it.updatedAt }) {
            if (rec.sessionId == session.id && rec.status != RecorderController.Status.IDLE) continue
            if (session.recordings.any { transcription.isPending(session.id, it.id) }) continue
            if ("index:${session.id}" in failed) continue
            val stale = MeaningIndex.stale(session)
            if (stale.isNotEmpty()) return Work.Index(session.id, stale.take(INDEX_BATCH))
        }
        return null
    }

    private fun summaryWork(rec: RecorderController.State, transcription: TranscriptionController.State): Work? {
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
        return organizing()
    }

    /**
     * With nothing to summarize: a new session to file, if keeping sessions organized; else suggestions
     * for the sessions in no folder, once a few are waiting (or right away, when asked).
     */
    private fun organizing(): Work? {
        val sessions = SessionRepository.sessions.value
        val folders = folderSummaries(sessions, FolderRepository.folders.value)
        if (prefs.autoFile && folders.isNotEmpty()) {
            val done = prefs.autoFileDone
            val examples = sessions.filter { it.folder != null }.sortedByDescending { it.createdAt }.groupBy({ it.folder!! }, { it.title })
            for (s in sessions.sortedByDescending { it.createdAt }) {
                if (s.folder != null || s.summary == null || s.createdAt < prefs.autoFileSince || s.id in done) continue
                val prompt = OrganizePrompts.file(s, folders, examples)
                val basis = SummaryPrompts.basis(prompt)
                if (basis in failed) continue
                return Work.File(s.id, folders.map { it.name }, prompt, basis)
            }
        }
        if (Organizer.suggestions.value != null) return null
        val waiting = sessions.filter { it.folder == null && it.summary != null }.sortedByDescending { it.createdAt }.take(OrganizePrompts.MAX_SESSIONS)
        val due = if (Organizer.requested) waiting.size >= 2 else waiting.size >= 4 && waiting.size >= prefs.suggestDismissedAt + 3
        if (!due) {
            if (Organizer.requested) Organizer.noneFound()
            return null
        }
        val prompt = OrganizePrompts.suggest(waiting, folders)
        val basis = SummaryPrompts.basis(prompt)
        if (basis in failed || (basis == prefs.suggestBasis && !Organizer.requested)) {
            if (Organizer.requested) Organizer.noneFound()
            return null
        }
        return Work.Suggest(waiting.map { it.id }, prompt, basis)
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
