package com.kjwindham.audiocool.speakers

import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SpeakerTurn
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.Voice
import kotlin.math.sqrt

/**
 * Who said what, worked with: matching voices by their voiceprints (speaker embeddings), turns, and
 * which voice said each word.
 */
object Voices {
    /** What [match] found: each cluster's voice id, and the session's voices and voiceprints with the new ones. */
    class Matched(val ids: List<Int>, val voices: List<Voice>, val prints: Map<Int, FloatArray>)

    /**
     * Which of the session's [voices] (whose voiceprints are [prints]) each new cluster is ([same] alike
     * at least), each voice taken once; the rest are new voices, named after a [known] voice they match
     * ([knownAt]).
     */
    fun match(clusterPrints: List<FloatArray>, voices: List<Voice>, prints: Map<Int, FloatArray>, known: List<KnownVoice>, same: Float, knownAt: Float): Matched {
        val result = voices.toMutableList()
        val newPrints = HashMap(prints)
        val assigned = HashMap<Int, Int>()
        val used = HashSet<Int>()
        val pairs = clusterPrints.indices.flatMap { c -> voices.mapNotNull { v -> prints[v.id]?.let { Triple(c, v.id, cosine(clusterPrints[c], it)) } } }
            .filter { it.third >= same }
            .sortedByDescending { it.third }
        for ((c, v, _) in pairs) {
            if (c in assigned || v in used) continue
            assigned[c] = v
            used += v
        }
        var nextId = (voices.maxOfOrNull { it.id } ?: -1) + 1
        val ids = clusterPrints.indices.map { c ->
            assigned[c] ?: run {
                val name = known.map { it to cosine(it.voiceprint, clusterPrints[c]) }.filter { it.second >= knownAt }.maxByOrNull { it.second }?.first?.name
                // Someone known who's in the session already (from another recording): that voice.
                val named = name?.let { n -> result.firstOrNull { it.name.equals(n, ignoreCase = true) && it.id !in used } }
                val id = named?.id ?: nextId++.also {
                    result += Voice(it, name)
                    newPrints[it] = normalized(clusterPrints[c])
                }
                used += id
                id
            }
        }
        return Matched(ids, result, newPrints)
    }

    /** The voice speaking most of [startMs]..[endMs], by [turns]; null if none does. */
    fun voiceAt(turns: List<SpeakerTurn>?, startMs: Long, endMs: Long): Int? {
        if (turns.isNullOrEmpty()) return null
        var best: Int? = null
        var most = 0L
        for (t in turns) {
            val overlap = minOf(t.endMs, endMs) - maxOf(t.startMs, startMs)
            if (overlap > most) {
                most = overlap
                best = t.voice
            }
        }
        return best
    }

    /**
     * [segment] cut where the speaker changes, by [turns]: each word goes with the voice speaking when
     * it starts. Only a phrase timed word by word can be cut; others stay whole.
     */
    fun splitBySpeaker(segment: TranscriptSegment, turns: List<SpeakerTurn>?): List<TranscriptSegment> {
        val offsets = segment.words
        if (turns.isNullOrEmpty() || offsets == null) return listOf(segment)
        val words = segment.text.split(' ')
        if (words.size != offsets.size || words.size < 2) return listOf(segment)
        val voices = offsets.map { at -> voiceNear(turns, segment.startMs + at) }
        if (voices.distinct().size < 2) return listOf(segment)
        val out = ArrayList<TranscriptSegment>()
        var from = 0
        for (i in 1..words.size) {
            if (i < words.size && voices[i] == voices[from]) continue
            val start = if (from == 0) segment.startMs else segment.startMs + offsets[from]
            val end = if (i == words.size) segment.endMs else segment.startMs + offsets[i]
            out += TranscriptSegment(start, maxOf(start + 1, end), words.subList(from, i).joinToString(" "), offsets.subList(from, i).map { (segment.startMs + it - start).toInt() })
            from = i
        }
        return out
    }

    /** The voice speaking at [atMs], or the nearest turn's when it falls between turns. */
    private fun voiceNear(turns: List<SpeakerTurn>, atMs: Long): Int =
        turns.firstOrNull { atMs in it.startMs until it.endMs }?.voice
            ?: turns.minBy { minOf(kotlin.math.abs(it.startMs - atMs), kotlin.math.abs(it.endMs - atMs)) }.voice

    /** What a voice is called: its name, or "Speaker 2" (numbered as they first spoke) until it has one. */
    fun name(session: Session, voiceId: Int): String =
        session.voices.firstOrNull { it.id == voiceId }?.name ?: "Speaker ${session.voices.sortedBy { it.id }.indexOfFirst { it.id == voiceId } + 1}"

    fun normalized(v: FloatArray): FloatArray {
        val length = sqrt(dot(v, v))
        return if (length == 0f) v.copyOf() else FloatArray(v.size) { v[it] / length }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val d = sqrt(dot(a, a) * dot(b, b))
        return if (d == 0f) 0f else dot(a, b) / d
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
