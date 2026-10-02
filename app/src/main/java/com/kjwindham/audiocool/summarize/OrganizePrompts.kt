package com.kjwindham.audiocool.summarize

import com.kjwindham.audiocool.data.FolderSummary
import com.kjwindham.audiocool.data.Session
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A folder the model suggests, with the sessions to put in it; [existing] when it's one there already. */
data class FolderSuggestion(val name: String, val description: String?, val sessionIds: List<String>, val existing: Boolean)

/**
 * What the summary model is asked to organize sessions: which go together in folders, and which folder
 * a new session belongs in. It reads each session's title, when it was and its summary, not the talk.
 */
object OrganizePrompts {
    const val SUGGEST_TOKENS = 400
    const val FILE_TOKENS = 8

    /** The newest this many sessions at most, so the prompt stays a manageable size for the phone. */
    const val MAX_SESSIONS = 40

    private const val MAX_NAME = 40
    private const val MAX_DESCRIPTION = 120

    /** A session in a line: its title, the day and time (a weekly course shows), and what it was about. */
    fun describe(s: Session): String {
        val whenText = SimpleDateFormat("EEE h:mm a", Locale.US).format(Date(s.createdAt))
        val about = s.summary?.text?.let(::firstSentence)
            ?: s.notes.mapNotNull { n -> n.photoText?.lineSequence()?.firstOrNull { it.isNotBlank() } }.take(2).joinToString("; ").takeIf { it.isNotBlank() }
        return "\"${s.title}\" ($whenText)" + (about?.let { ": $it" } ?: "")
    }

    fun suggest(sessions: List<Session>, folders: List<FolderSummary>): String = buildString {
        appendLine("Folders there are already: " + if (folders.isEmpty()) "none" else folders.joinToString("; ") { folderLine(it) })
        appendLine()
        appendLine("Recordings to sort:")
        sessions.forEachIndexed { i, s -> appendLine("${i + 1}. ${describe(s)}") }
        appendLine()
        appendLine(
            "Group the recordings that belong together: the same course, project, recurring meeting or topic. " +
                "Put a recording in a folder that's there already when it fits. Give each new folder a short name " +
                "of two to four words and a one-line description of what's in it. Leave out a recording that fits " +
                "no group, and don't make a new folder for a single recording.",
        )
        appendLine()
        append("Reply as JSON: {\"folders\": [{\"name\": \"...\", \"description\": \"...\", \"recordings\": [1, 2]}]}")
    }

    /**
     * The folders in the model's [reply] to [suggest], whose sessions were numbered from 1 in the order of
     * [sessionIds]; each session goes in one folder at most, and a new folder needs two or more. Null if
     * there's no JSON to read.
     */
    fun parseSuggestions(reply: String, sessionIds: List<String>, folders: List<FolderSummary>): List<FolderSuggestion>? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val array = runCatching { JSONObject(reply.substring(start, end + 1)).optJSONArray("folders") }.getOrNull() ?: return null
        val taken = HashSet<String>()
        val out = ArrayList<FolderSuggestion>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("name").trim().take(MAX_NAME).takeIf { it.isNotEmpty() } ?: continue
            val existing = folders.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val numbers = (o.optJSONArray("recordings") ?: o.optJSONArray("sessions") ?: JSONArray())
            val ids = (0 until numbers.length()).mapNotNull { numbers.optInt(it, 0).takeIf { n -> n in 1..sessionIds.size } }
                .map { sessionIds[it - 1] }.distinct().filter { it !in taken }
            if (ids.isEmpty() || (existing == null && ids.size < 2)) continue
            // Only a folder that's kept claims its sessions.
            taken += ids
            val description = o.optString("description").trim().take(MAX_DESCRIPTION).takeIf { it.isNotEmpty() && existing == null }
            out += FolderSuggestion(existing?.name ?: name, description, ids, existing != null)
        }
        return out
    }

    /** Asks which of [folders] a new session belongs in; [examples] are a few session titles from each. */
    fun file(session: Session, folders: List<FolderSummary>, examples: Map<String, List<String>>): String = buildString {
        appendLine("Folders:")
        folders.forEachIndexed { i, f ->
            val seen = examples[f.name].orEmpty().take(3)
            appendLine("${i + 1}. ${folderLine(f)}" + if (seen.isEmpty()) "" else " (for example: ${seen.joinToString("; ")})")
        }
        appendLine()
        appendLine("A new recording: ${describe(session)}")
        appendLine()
        append("Which folder does it belong in? Reply with just the folder's number, or 0 if none fits.")
    }

    /** The folder number in a reply to [file]: 0 for none, null if there's no number in range. */
    fun parseFile(reply: String, folderCount: Int): Int? {
        if (Regex("\\b(none|no folder)\\b", RegexOption.IGNORE_CASE).containsMatchIn(reply)) return 0
        return Regex("\\d+").find(reply)?.value?.toIntOrNull()?.takeIf { it in 0..folderCount }
    }

    private fun folderLine(f: FolderSummary) = f.name + (f.description?.let { ": $it" } ?: "")

    private fun firstSentence(text: String): String {
        val end = Regex("[.!?](\\s|$)").find(text)?.range?.first
        return (if (end != null) text.substring(0, end + 1) else text).trim()
    }
}
