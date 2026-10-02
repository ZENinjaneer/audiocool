package com.kjwindham.audiocool.summarize

import android.content.Context
import com.kjwindham.audiocool.data.FolderRepository
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.util.Prefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sessions organized into folders by the summary model: folders it suggests, to review and apply, and
 * new sessions filed as their summaries arrive. The model's part runs in [SummaryController], between
 * summaries; this keeps what came of it and does the filing.
 */
object Organizer {
    /** A session filed for you, and where; [byCalendar] when it was from your calendar rather than the model. */
    data class Filed(val sessionId: String, val folder: String, val byCalendar: Boolean = false)

    private val _suggestions = MutableStateFlow<List<FolderSuggestion>?>(null)

    /** Folders suggested for sessions in none, to review; null when there's nothing to offer. */
    val suggestions: StateFlow<List<FolderSuggestion>?> = _suggestions.asStateFlow()

    private val _looking = MutableStateFlow(false)

    /** Suggestions were asked for and the model is on it. */
    val looking: StateFlow<Boolean> = _looking.asStateFlow()

    private val _filed = MutableSharedFlow<Filed>(extraBufferCapacity = 8)
    val filed: SharedFlow<Filed> = _filed.asSharedFlow()

    private val _nothingToSuggest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Suggestions were asked for, and nothing went together. */
    val nothingToSuggest: SharedFlow<Unit> = _nothingToSuggest.asSharedFlow()

    private lateinit var prefs: Prefs

    /** Asked for by the person: suggest even for a couple of sessions, and say so if there's nothing to group. */
    @Volatile
    var requested = false
        private set

    fun init(context: Context) {
        prefs = Prefs(context.applicationContext)
        _suggestions.value = decode(prefs.folderSuggestions)
        _looking.value = false
        requested = false
    }

    fun request() {
        requested = true
        _looking.value = true
        SummaryController.schedule()
    }

    internal fun offer(list: List<FolderSuggestion>) {
        prefs.folderSuggestions = encode(list)
        _suggestions.value = list
        finishedLooking()
    }

    internal fun noneFound() {
        if (requested) _nothingToSuggest.tryEmit(Unit)
        finishedLooking()
    }

    internal fun filed(f: Filed) {
        _filed.tryEmit(f)
    }

    private fun finishedLooking() {
        requested = false
        _looking.value = false
    }

    /** Not now: forget these, and don't offer again until a few more sessions ([waiting] now) are in no folder. */
    fun dismiss(waiting: Int) {
        prefs.suggestDismissedAt = waiting
        clear()
    }

    /**
     * Files the sessions of [chosen] (as named after any renaming), keeping new sessions organized from
     * now on if [keepOrganized]. Returns how to put things back.
     */
    fun apply(chosen: List<FolderSuggestion>, keepOrganized: Boolean): Undo {
        val before = LinkedHashMap<String, String?>()
        val made = ArrayList<String>()
        for (f in chosen) {
            val known = FolderRepository.folders.value.any { it.name.equals(f.name, ignoreCase = true) } ||
                SessionRepository.sessions.value.any { it.folder.equals(f.name, ignoreCase = true) }
            val name = FolderRepository.create(f.name)
            if (!known) made += name
            f.description?.let { FolderRepository.describe(name, it) }
            for (id in f.sessionIds) {
                val session = SessionRepository.get(id) ?: continue
                before[id] = session.folder
                SessionRepository.moveToFolder(id, name)
            }
        }
        if (keepOrganized && !prefs.autoFile) prefs.autoFileSince = System.currentTimeMillis()
        prefs.autoFile = keepOrganized
        clear()
        return Undo(before, made)
    }

    /** Puts sessions back where they were, and removes the folders made for them. */
    class Undo internal constructor(private val before: Map<String, String?>, private val made: List<String>) {
        val sessions: Int get() = before.size

        fun run() {
            before.forEach { (id, folder) -> SessionRepository.moveToFolder(id, folder) }
            made.forEach { FolderRepository.delete(it) }
        }
    }

    private fun clear() {
        _suggestions.value = null
        prefs.folderSuggestions = ""
    }

    private fun encode(list: List<FolderSuggestion>): String = JSONArray().apply {
        list.forEach { f ->
            put(JSONObject().put("name", f.name).put("existing", f.existing).put("sessions", JSONArray(f.sessionIds)).apply { f.description?.let { put("description", it) } })
        }
    }.toString()

    private fun decode(text: String): List<FolderSuggestion>? = runCatching {
        if (text.isBlank()) return null
        val array = JSONArray(text)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val ids = o.getJSONArray("sessions").let { a -> (0 until a.length()).map { a.getString(it) } }
            FolderSuggestion(o.getString("name"), o.optString("description").takeIf { it.isNotBlank() }, ids, o.optBoolean("existing"))
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
