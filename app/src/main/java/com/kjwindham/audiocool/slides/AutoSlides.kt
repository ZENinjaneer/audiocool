package com.kjwindham.audiocool.slides

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.kjwindham.audiocool.data.Photos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

/**
 * Hands-free slides: while the capture screen's camera faces the screen, each new slide is
 * photographed by itself once it holds still ([SlideWatcher] decides when) and lands in the recording
 * where it went up; more appearing on a slide retakes its photo. This outlives the screen, so turning
 * the phone or pausing keeps what it has seen.
 */
object AutoSlides {
    data class State(
        val sessionId: String? = null,
        val watching: Boolean = false,
        /** How many slides it has saved in the session. */
        val slides: Int = 0,
        /** When the latest was saved or retaken (wall clock), if one has been. */
        val lastSavedAt: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var watcher = SlideWatcher()
    /** Each slide's photo note, once saved. */
    private val notes = HashMap<Int, String>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "auto-slides") }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Starts watching for slides in [sessionId], remembering those seen in it before. [reframed] when
     * the camera may point differently from before (the screen was turned).
     */
    @Synchronized
    fun start(sessionId: String, reframed: Boolean = false) {
        if (_state.value.sessionId != sessionId) {
            watcher = SlideWatcher()
            notes.clear()
            _state.value = State(sessionId)
        } else {
            watcher.resume()
            if (reframed) watcher.reframed(0)
        }
        _state.update { it.copy(watching = true) }
    }

    @Synchronized
    fun stop() = _state.update { it.copy(watching = false) }

    /** Looks at the camera's latest picture ([at]: a steady clock, in ms); what to photograph, if anything. */
    @Synchronized
    fun frame(grid: SlideWatcher.Grid?, at: Long): SlideWatcher.Decision? = if (_state.value.watching) watcher.frame(grid, at) else null

    /** Someone zoomed the camera. */
    @Synchronized
    fun reframed(at: Long) = watcher.reframed(at)

    /** The photo for [decision] couldn't be taken: a new slide is forgotten, to be seen afresh. */
    @Synchronized
    fun notTaken(sessionId: String, decision: SlideWatcher.Decision) {
        if (decision is SlideWatcher.Decision.New && _state.value.sessionId == sessionId) watcher.forget(decision.slide)
    }

    /**
     * Saves the photo in [file], taken for [decision]: a new slide at [offsetMs] of [recId], or a better
     * picture of one already saved. [onDone] (on the main thread) gets whether it was saved.
     */
    fun save(context: Context, sessionId: String, recId: String?, decision: SlideWatcher.Decision, file: File, offsetMs: Long?, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        io.execute {
            val ok = when (decision) {
                is SlideWatcher.Decision.New -> {
                    val id = Photos.addNow(app, sessionId, file, recId, offsetMs)
                    synchronized(this) { if (id != null && _state.value.sessionId == sessionId) notes[decision.slide] = id }
                    id != null
                }
                is SlideWatcher.Decision.Better -> {
                    val id = synchronized(this) { notes[decision.slide].takeIf { _state.value.sessionId == sessionId } }
                    // Its photo was deleted meanwhile: that's left as it is.
                    if (id == null) file.delete().let { false } else Photos.replaceNow(app, sessionId, id, file)
                }
            }
            if (ok) {
                _state.update { s ->
                    if (s.sessionId != sessionId) s else s.copy(slides = s.slides + if (decision is SlideWatcher.Decision.New) 1 else 0, lastSavedAt = System.currentTimeMillis())
                }
            } else {
                notTaken(sessionId, decision)
            }
            main.post { onDone(ok) }
        }
    }

    /** Runs after everything handed over so far is saved; for tests. */
    @VisibleForTesting
    fun afterQueued(block: () -> Unit) = io.execute(block)

    @VisibleForTesting
    @Synchronized
    fun resetForTest() {
        watcher = SlideWatcher()
        notes.clear()
        _state.value = State()
    }
}
