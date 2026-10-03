package com.kjwindham.audiocool.desktop

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.kjwindham.audiocool.summarize.Summarizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Summaries written on the paired desktop. AudioCool Desktop can run Gemma 4 26B, a much bigger model
 * than the phone's, and while it can be reached the summary model's prompts go there instead (see
 * SummaryController). Whether it can is looked up at most once a minute.
 */
object DesktopSummaries {
    /** The desktop's summary model: its id and name ("Gemma 4 26B"), and the desktop's name. */
    data class Model(val id: String, val name: String, val desktop: String)

    /** What's known of the paired desktop: not looked at yet, out of reach, or reached (with its model, if it has one). */
    data class State(val checked: Boolean = false, val reachable: Boolean = false, val model: Model? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The desktop's model, while it can be reached; else null. */
    val model: Model? get() = _state.value.model

    private const val RECHECK_MS = 60_000L
    private const val TIMEOUT_MS = 4_000

    @Volatile private var checkedAt = 0L
    @Volatile private var checkedPairing: DesktopSync.Pairing? = null

    /** Stands in for the desktop in tests: replies to prompts as its model would. */
    @VisibleForTesting
    var replyForTest: ((prompt: String, maxTokens: Int) -> String)? = null

    @VisibleForTesting
    fun useForTest(model: Model?, reply: ((String, Int) -> String)?) {
        forget()
        replyForTest = reply
        if (model != null) _state.value = State(checked = true, reachable = true, model = model)
    }

    /** No desktop paired any more (or a different one): nothing known about it. */
    fun forget() {
        checkedAt = 0L
        checkedPairing = null
        _state.value = State()
    }

    /**
     * Asks the paired desktop whether it can summarize, unless that was asked in the last minute (or
     * [force]). Blocks for a few seconds at most, so call it off the main thread.
     */
    fun check(force: Boolean = false): Model? {
        if (replyForTest != null) return model
        val pairing = DesktopSync.pairing.value
        if (pairing == null) {
            _state.value = State()
            return null
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && pairing == checkedPairing && checkedAt != 0L && now - checkedAt < RECHECK_MS) return model
        checkedAt = now
        checkedPairing = pairing
        _state.value = try {
            val info = DesktopClient(pairing.url, pairing.token).ping(TIMEOUT_MS)
            State(checked = true, reachable = true, model = info.summaries?.let { Model(it.id, it.name, info.name) })
        } catch (e: Exception) {
            State(checked = true, reachable = false)
        }
        return model
    }

    /** The desktop stopped answering mid-way: the phone's model takes over until it's looked for again. */
    fun lost() {
        _state.update { it.copy(reachable = false, model = null) }
        checkedAt = SystemClock.elapsedRealtime()
    }

    /** Something that sends prompts to the desktop's model, while it has one; else null. */
    fun summarizer(): Summarizer? {
        val m = model ?: return null
        replyForTest?.let { return DesktopSummarizer(m, it) }
        val pairing = DesktopSync.pairing.value ?: return null
        val client = DesktopClient(pairing.url, pairing.token)
        return DesktopSummarizer(m) { prompt, maxTokens -> client.reply(prompt, maxTokens) }
    }
}

/** The summary model, on the desktop: each reply is a request to it. */
class DesktopSummarizer(model: DesktopSummaries.Model, private val send: (String, Int) -> String) : Summarizer {
    override val where = "your desktop"
    override val lastSpeed: Pair<Double, Double>? = null
    override val modelId = "${model.id}@desktop"

    override fun reply(prompt: String, maxTokens: Int) = send(prompt, maxTokens)

    override fun close() {}
}
