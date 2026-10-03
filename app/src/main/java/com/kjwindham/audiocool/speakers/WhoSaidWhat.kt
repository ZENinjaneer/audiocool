package com.kjwindham.audiocool.speakers

import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.kjwindham.audiocool.data.SpeakerTurn

/**
 * Finds who spoke when in a recording: sherpa-onnx's diarization (pyannote finds the stretches each
 * voice speaks, clustered by voiceprint) over the audio a chunk at a time, so a long recording doesn't
 * all have to be in memory; voices are matched from chunk to chunk by their voiceprints.
 */
class WhoSaidWhat(
    /** Diarizes a chunk of audio. */
    private val diarize: (FloatArray) -> Array<OfflineSpeakerDiarizationSegment>,
    private val extractor: SpeakerEmbeddingExtractor,
    /** How alike two voiceprints must be to be the same voice. */
    private val same: Float,
) {
    // Not processWithCallback: its progress callback isn't found from Kotlin (1.13.8 throws NoSuchMethodError).
    constructor(diarization: OfflineSpeakerDiarization, extractor: SpeakerEmbeddingExtractor, same: Float) : this(diarization::process, extractor, same)

    /** A voice found so far: its voiceprint (summed over chunks, weighted by speech). */
    private class Found(val sum: FloatArray, var speechMs: Long)

    private val found = ArrayList<Found>()
    private val turns = ArrayList<SpeakerTurn>()

    /** Takes the next chunk of audio (16 kHz), which starts [offsetMs] into the recording. */
    fun chunk(samples: FloatArray, offsetMs: Long) {
        val segments = diarize(samples)
        if (segments.isEmpty()) return
        // Each cluster the diarization found here, with how long it spoke and its voiceprint (from its
        // longest stretches, or all of it run together when they're all short).
        val local = segments.groupBy { it.speaker }
        val speech = local.mapValues { (_, segs) -> segs.sumOf { ((it.end - it.start) * 1000).toLong() } }
        val prints = local.mapValues { (_, segs) ->
            val longest = segs.sortedByDescending { it.end - it.start }.take(PRINT_SEGMENTS).filter { it.end - it.start >= MIN_PRINT_S }
            val vs = if (longest.isNotEmpty()) longest.mapNotNull { embed(samples, listOf(it.start to it.end)) } else listOfNotNull(embed(samples, segs.map { it.start to it.end }))
            if (vs.isEmpty()) null else Voices.normalized(FloatArray(vs[0].size) { i -> vs.sumOf { it[i].toDouble() }.toFloat() })
        }
        // Biggest first, each joins the voice it sounds most like (found in this chunk or before) if
        // alike enough, or is a new voice: diarization tends to split one person into several.
        val ids = HashMap<Int, Int>()
        for (l in local.keys.sortedByDescending { speech.getValue(it) }) {
            val p = prints[l]
            val best = if (p != null) {
                found.indices.map { f -> f to Voices.cosine(p, found[f].sum) }.filter { it.second >= same }.maxByOrNull { it.second }?.first
            } else {
                // Too little to go on (a few short words): the voice speaking nearest them.
                nearestVoice(local.getValue(l), segments, ids)
            }
            val f = best ?: run {
                found += Found(FloatArray(p?.size ?: extractor.dim()), 0)
                found.lastIndex
            }
            ids[l] = f
            // Weighted by how long it spoke: more speech, a surer voiceprint.
            if (p != null) for (i in p.indices) found[f].sum[i] += p[i] * speech.getValue(l) / 1000f
            found[f].speechMs += speech.getValue(l)
        }
        for (s in segments.sortedBy { it.start }) {
            turns += SpeakerTurn(offsetMs + (s.start * 1000).toLong(), offsetMs + (s.end * 1000).toLong(), ids.getValue(s.speaker))
        }
    }

    /** Who spoke when (each voice by its number here), and each voice's voiceprint. */
    fun result(): Pair<List<SpeakerTurn>, List<FloatArray>> {
        val joined = ArrayList<SpeakerTurn>()
        for (t in turns.sortedBy { it.startMs }) {
            val last = joined.lastOrNull()
            if (last != null && last.voice == t.voice && t.startMs - last.endMs < JOIN_GAP_MS) joined[joined.lastIndex] = last.copy(endMs = maxOf(last.endMs, t.endMs)) else joined += t
        }
        return joined to found.map { Voices.normalized(it.sum) }
    }

    /** The voice most of [mine] are nearest to, among [all] the segments whose cluster has a voice in [ids]. */
    private fun nearestVoice(mine: List<OfflineSpeakerDiarizationSegment>, all: Array<OfflineSpeakerDiarizationSegment>, ids: Map<Int, Int>): Int? {
        val known = all.filter { it.speaker in ids }
        if (known.isEmpty()) return null
        return mine.map { m ->
            val near = known.minBy { k -> maxOf(0f, maxOf(k.start, m.start) - minOf(k.end, m.end)) }
            ids.getValue(near.speaker)
        }.groupingBy { it }.eachCount().maxBy { it.value }.key
    }

    /** The voiceprint of [stretches] (start and end, in seconds) of [samples] run together, up to 10 s; null if under 1.5 s. */
    private fun embed(samples: FloatArray, stretches: List<Pair<Float, Float>>): FloatArray? {
        val parts = ArrayList<FloatArray>()
        var total = 0
        for ((start, end) in stretches) {
            val from = (start * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
            val to = (end * SAMPLE_RATE).toInt().coerceIn(from, samples.size)
            if (to <= from) continue
            parts += samples.copyOfRange(from, to)
            total += to - from
            if (total >= 10 * SAMPLE_RATE) break
        }
        if (total < MIN_PRINT_S * SAMPLE_RATE) return null
        val audio = FloatArray(total)
        var at = 0
        for (p in parts) {
            p.copyInto(audio, at)
            at += p.size
        }
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(audio, SAMPLE_RATE)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Audio is looked at this much at a time: about 38 MB of samples. */
        const val CHUNK_MS = 10 * 60 * 1000L

        /** A voice's voiceprint comes from its longest stretches in a chunk, of at least this long. */
        private const val PRINT_SEGMENTS = 6
        private const val MIN_PRINT_S = 1.5f

        /** Turns by the same voice closer than this are one turn. */
        private const val JOIN_GAP_MS = 300L
    }
}
