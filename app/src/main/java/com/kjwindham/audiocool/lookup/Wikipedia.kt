package com.kjwindham.audiocool.lookup

import com.kjwindham.audiocool.summarize.Words
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looking a word up on Wikipedia. Only the word goes out (and then the title of the article picked):
 * when it means several things, the meanings' one-line descriptions come back and the one that fits
 * the talk is picked here, on the phone, by the words they share with it.
 */
object Wikipedia {
    /** An article: its title, one-line description, first paragraph and address. */
    data class Article(val title: String, val description: String?, val extract: String, val url: String)

    /** What was found: the article that fits best (if any), and other things the word can mean. */
    data class Found(val article: Article?, val others: List<String>)

    private const val API = "https://en.wikipedia.org"
    private const val USER_AGENT = "AudioCool (https://github.com/ZENinjaneer/audiocool)"

    /**
     * Looks up [word]: [context] is what was said around it, and [meaning] what the summary model made
     * of it from the talk (if it could), both for picking a meaning. [fetch] gets a URL's body, or null
     * when there's no such page; it throws when the network fails.
     */
    fun lookUp(word: String, context: Collection<String>, meaning: String? = null, fetch: (String) -> String? = ::get): Found {
        // The word itself is in every meaning's title: it says nothing about which.
        val itself = Words.all(word).toSet()
        val about = context.flatMap { Words.content(it) }.toSet() - itself
        val meant = meaning?.let { Words.content(it).toSet() - itself }.orEmpty()
        val summary = fetch("$API/api/rest_v1/page/summary/${encode(word)}")?.let(::parseSummary)
        if (summary != null && summary.second != "disambiguation") return Found(summary.first, emptyList())
        // Several meanings (or none by that name): the candidates, with what each is.
        val candidates = if (summary != null) {
            fetch("$API/w/api.php?action=query&titles=${encode(word)}&redirects=1&generator=links&gpllimit=80&gplnamespace=0&prop=description&format=json&formatversion=2")
                ?.let(::parseLinks).orEmpty()
        } else {
            fetch("$API/w/api.php?action=query&generator=search&gsrsearch=${encode(word)}&gsrlimit=10&prop=description&format=json&formatversion=2")
                ?.let(::parseLinks).orEmpty()
        }.filterNot { (title, description) -> title.startsWith("List of") || description?.contains("same term") == true }
        if (candidates.isEmpty()) return Found(null, emptyList())
        val scored = candidates.map { it to fit(it, word, about, meant) }.sortedByDescending { it.second }
        val best = scored.first().takeIf { it.second > 0 }?.first
        val article = best?.let { (title, _) -> fetch("$API/api/rest_v1/page/summary/${encode(title)}")?.let(::parseSummary)?.first }
        // Other meanings: what else the letters stand for, and namesakes ("R.E.M.", the band).
        val others = scored.map { it.first }.filter { it != best && (expands(it.first, word) || plain(it.first) == plain(word)) }
            .sortedBy { it.first.length }
            .take(3)
            .map { (title, description) -> description?.let { "$title ($it)" } ?: title }
        return Found(article, others)
    }

    /**
     * How well a meaning fits: its title and description's words found in the talk around the word,
     * and twice over in what the talk took it to mean.
     */
    private fun fit(candidate: Pair<String, String?>, word: String, about: Set<String>, meant: Set<String>): Double {
        val (title, description) = candidate
        val words = Words.content("$title ${description.orEmpty()}").toSet()
        var score = words.count { it in about } + 2.0 * words.count { it in meant }
        // An expansion of an acronym ("Rapid eye movement" for REM) fits a little better.
        if (expands(title, word)) score += 0.5
        return score
    }

    /** Whether [title]'s initials spell [word], an acronym ("Rapid eye movement sleep" for REM). */
    private fun expands(title: String, word: String): Boolean {
        if (word.length !in 2..6 || !word.all { it.isUpperCase() || it.isDigit() }) return false
        val initials = title.substringBefore(" (").split(' ', '-').filter { it.isNotBlank() }.joinToString("") { it.first().lowercase() }
        return initials.startsWith(word.lowercase())
    }

    /** Letters and digits only, lowercase: "R.E.M." is "rem". */
    private fun plain(s: String) = s.substringBefore(" (").filter { it.isLetterOrDigit() }.lowercase()

    internal fun parseSummary(body: String): Pair<Article, String>? = runCatching {
        val o = JSONObject(body)
        val title = o.optString("title").takeIf { it.isNotBlank() } ?: return null
        val url = o.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
            ?: "https://en.m.wikipedia.org/wiki/${encode(title.replace(' ', '_'))}"
        Article(title, o.optString("description").takeIf { it.isNotBlank() }, o.optString("extract").trim(), url) to o.optString("type")
    }.getOrNull()

    internal fun parseLinks(body: String): List<Pair<String, String?>> = runCatching {
        val pages = JSONObject(body).optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        (0 until pages.length()).mapNotNull { i ->
            val p = pages.getJSONObject(i)
            p.optString("title").takeIf { it.isNotBlank() }?.let { it to p.optString("description").takeIf { d -> d.isNotBlank() } }
        }
    }.getOrDefault(emptyList())

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The body at [url]; null for a page that isn't there. */
    private fun get(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            if (connection.responseCode == 404) null else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
