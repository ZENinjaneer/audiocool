package com.kjwindham.audiocool.summarize

/**
 * The words in text that say what it's about: lowercase, without the commonest ones ("the", "what"),
 * and with a plural's s and common endings dropped, so "cycles" and "cycle" count as one.
 */
object Words {
    private val STOP = setOf(
        "a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "by", "from", "about", "as", "into",
        "is", "are", "was", "were", "be", "been", "being", "do", "does", "did", "done", "have", "has", "had", "it", "its", "this",
        "that", "these", "those", "there", "here", "what", "which", "who", "whom", "whose", "when", "where", "why", "how", "i",
        "you", "he", "she", "we", "they", "me", "him", "her", "us", "them", "my", "your", "our", "their", "can", "could", "would",
        "should", "will", "shall", "may", "might", "must", "not", "no", "so", "if", "then", "than", "too", "very", "just", "also",
        "say", "said", "says", "tell", "talk", "talked", "mention", "mentioned", "lecture", "session", "speaker", "any", "some",
        "yeah", "okay", "ok", "um", "uh", "like", "really", "right", "well", "know", "think", "going", "get", "got", "one", "thing",
    )

    /** Every word, normalized (stop words kept). */
    fun all(text: String): List<String> =
        Regex("[\\p{L}\\p{N}']+").findAll(text.lowercase()).map { stem(it.value.trim('\'')) }.filter { it.length > 1 }.toList()

    /** The words that say what [text] is about. */
    fun content(text: String): List<String> = all(text).filter { it !in STOP }

    fun isStop(word: String) = word in STOP

    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }
}
