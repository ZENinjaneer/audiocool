package com.kjwindham.audiocool.lookup

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.summarize.AskPrompts
import com.kjwindham.audiocool.summarize.Moment
import com.kjwindham.audiocool.summarize.Passage
import com.kjwindham.audiocool.summarize.SummaryController
import com.kjwindham.audiocool.summarize.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/**
 * Looking a word up from a session: what it means in the talk (the summary model, from where it's
 * said), where it's said, and what Wikipedia says. Only the word goes to Wikipedia; the meaning the
 * talk gives it picks between the things it can mean, here on the phone.
 */
object LookUp {
    data class State(
        val sessionId: String,
        val word: String,
        val acronym: Boolean,
        /** Where it's said in the session (the first few), and how many times. */
        val moments: List<Moment>,
        val count: Int,
        /** Where it was looked up from: a note made of it goes there. */
        val from: Moment?,
        val meaning: String? = null,
        /** The summary model is working out what it means here. */
        val working: Boolean = false,
        val article: Wikipedia.Article? = null,
        val others: List<String> = emptyList(),
        val searching: Boolean = true,
        val webProblem: String? = null,
    )

    private const val TAG = "LookUp"
    private const val MEANING_TOKENS = 70

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** Stands in for the network in tests: a URL's body, or null for no such page. */
    @VisibleForTesting
    var fetchForTest: ((String) -> String?)? = null

    private val io = Executors.newSingleThreadExecutor { Thread(it, "look-up") }

    /** Looks up [word] from [session], looked up at [from]; [around] is more of what it was seen with (a slide's text). */
    fun start(session: Session, word: String, from: Moment?, around: List<String> = emptyList()) {
        val clean = word.trim().trim('.', ',', ';', ':', '!', '?', '"', '“', '”', '(', ')', '\'')
        if (clean.isEmpty()) return
        val pattern = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(clean)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        val said = AskPrompts.passages(session).filter { pattern.containsMatchIn(it.text) }
        val count = said.filter { it.kind == Passage.Kind.SAID }.sumOf { pattern.findAll(it.text).count() }
        val acronym = clean.length in 2..6 && clean.any { it.isLetter() } && clean.all { it.isUpperCase() || it.isDigit() }
        val context = said.take(5).map { it.text } + around
        val canAsk = SummaryController.canAsk && context.isNotEmpty()
        _state.value = State(session.id, clean, acronym, said.filter { it.kind == Passage.Kind.SAID }.map { it.moment }.distinct().take(6), count, from, working = canAsk)
        if (canAsk) {
            SummaryController.request(meaningPrompt(clean, context), MEANING_TOKENS) { reply ->
                val meaning = reply?.let(::cleanMeaning)
                update(session.id, clean) { it.copy(meaning = meaning, working = false) }
                web(session.id, clean, context, meaning)
            }
        } else {
            web(session.id, clean, context, null)
        }
    }

    fun close() {
        _state.value = null
    }

    /** Runs after the look-ups handed over so far; for tests. */
    @VisibleForTesting
    fun afterQueued(block: () -> Unit) = io.execute(block)

    private fun web(sessionId: String, word: String, context: List<String>, meaning: String?) = io.execute {
        val found = runCatching { fetchForTest?.let { Wikipedia.lookUp(word, context, meaning, it) } ?: Wikipedia.lookUp(word, context, meaning) }
        found.exceptionOrNull()?.let { Log.w(TAG, "Couldn't look up $word", it) }
        update(sessionId, word) { s ->
            found.fold(
                { s.copy(article = it.article, others = it.others, searching = false) },
                { s.copy(searching = false, webProblem = "Couldn't reach Wikipedia.") },
            )
        }
    }

    private fun update(sessionId: String, word: String, change: (State) -> State) =
        _state.update { s -> if (s != null && s.sessionId == sessionId && s.word == word) change(s) else s }

    internal fun meaningPrompt(word: String, context: List<String>): String = buildString {
        appendLine("Here is what was said in a talk where \"$word\" comes up:")
        context.forEach { appendLine("- ${it.trim()}") }
        appendLine()
        append("What does \"$word\" mean here? Reply with one short sentence. If it's short for something, start with what it stands for.")
    }

    /** The meaning in a reply: its first sentence or two, without quotes or a lead-in. */
    internal fun cleanMeaning(reply: String): String? {
        val text = reply.trim().removeSurrounding("\"").replace("**", "").lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return text.take(240).takeIf { it.count(Char::isLetter) >= 6 }
    }
}

/**
 * Explaining a slide: the summary model, from the slide's text and what was said while it was up,
 * with the moments it drew on, and the slide's key terms to look up.
 */
object Explain {
    data class State(
        val sessionId: String,
        val noteId: String,
        val title: String?,
        val terms: List<String>,
        val text: String? = null,
        val moments: List<Moment> = emptyList(),
        val working: Boolean = true,
        val problem: String? = null,
    )

    private const val QUESTION = "Explain this slide in plain words: what it says, and what the speaker made of it."

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun start(session: Session, photo: Note, title: String?) {
        val excerpts = excerpts(session, photo)
        val terms = keyTerms(photo.photoText.orEmpty(), excerpts.filter { it.kind == Passage.Kind.SAID }.joinToString(" ") { it.text })
        if (!SummaryController.canAsk || excerpts.isEmpty()) {
            _state.value = State(session.id, photo.id, title, terms, working = false, problem = "Explaining a slide needs summaries on (⋮ › Summaries).")
            return
        }
        _state.value = State(session.id, photo.id, title, terms)
        SummaryController.request(AskPrompts.prompt(session, QUESTION, excerpts), AskPrompts.ANSWER_TOKENS + 60) { reply ->
            _state.update { s ->
                if (s?.noteId != photo.id) return@update s
                if (reply == null) {
                    s.copy(working = false, problem = "Couldn't explain it just now.")
                } else {
                    val (text, moments) = AskPrompts.parse(reply, excerpts)
                    s.copy(text = text, moments = moments, working = false)
                }
            }
        }
    }

    fun close() {
        _state.value = null
    }

    /** The slide's text, and what was said from when it went up until the next photo, numbered for the model. */
    private fun excerpts(session: Session, photo: Note): List<Passage> {
        val all = AskPrompts.passages(session)
        val slide = all.firstOrNull { it.kind == Passage.Kind.PHOTO && it.moment.recId == photo.recId && it.moment.atMs == photo.offsetMs }
        val start = photo.offsetMs ?: return listOfNotNull(slide)
        val next = session.notes.filter { it.photo != null && it.recId == photo.recId && (it.offsetMs ?: -1) > start }.minOfOrNull { it.offsetMs!! } ?: Long.MAX_VALUE
        val said = all.filter { it.kind == Passage.Kind.SAID && it.moment.recId == photo.recId && it.moment.atMs >= start - 5_000 && it.moment.atMs < next }
        var words = 0
        return listOfNotNull(slide) + said.takeWhile { p -> (words < 1_000).also { words += p.text.split(' ').size } }.take(7)
    }

    /**
     * Words on the slide worth looking up: its acronyms first, then its long words, and shorter ones the
     * speaker kept coming back to; a field's terms, not everyday words ("Morning").
     */
    internal fun keyTerms(slide: String, said: String): List<String> {
        val tokens = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*").findAll(slide).map { it.value.trimEnd('-', '\'') }.toList()
        val spoken = Words.all(said).groupingBy { it }.eachCount()
        fun timesSaid(t: String) = spoken[Words.all(t).firstOrNull()] ?: 0
        val acronyms = tokens.filter { it.length in 2..6 && it.any(Char::isLetter) && it.all { c -> c.isUpperCase() || c.isDigit() } }
        val words = tokens.filter { t ->
            !Words.isStop(t.lowercase()) && t.any(Char::isLowerCase) && (t.length >= 9 || t.length >= 7 && timesSaid(t) >= 2)
        }.sortedWith(compareByDescending<String> { timesSaid(it) > 0 }.thenByDescending { it.length })
        return (acronyms + words).distinctBy { it.lowercase() }.take(4)
    }
}
