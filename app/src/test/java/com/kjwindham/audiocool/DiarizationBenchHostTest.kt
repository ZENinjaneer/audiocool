package com.kjwindham.audiocool

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compares speaker models for "who said what" on meetings with known speakers. Skipped unless given
 * -PsherpaHostDir (the engine) and -PdiarBenchDir=<dir> holding eval/<meeting>.wav + .json (turns:
 * [start s, end s, speaker]) and the models; -PdiarModels=a.onnx,b.onnx picks embedding models.
 */
class DiarizationBenchHostTest {
    private val dir = System.getProperty("diar.bench.dir")?.let(::File)

    @Test
    fun compare() {
        assumeTrue(System.getProperty("sherpa.host.dir") != null && dir != null)
        val meetings = File(dir, "eval").listFiles { f -> f.name.endsWith(".wav") }!!.sorted()
        val models = System.getProperty("diar.models")?.split(",") ?: listOf("3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx")
        val thresholds = System.getProperty("diar.thresholds")?.split(",")?.map { it.toFloat() } ?: listOf(0.5f)
        val segModel = System.getProperty("diar.seg") ?: "seg.onnx"
        for (model in models) for (threshold in thresholds) {
            var refTotal = 0.0
            var errTotal = 0.0
            val line = StringBuilder()
            var seconds = 0.0
            var audioSeconds = 0.0
            for (wav in meetings) {
                val samples = readWav(wav)
                val ref = JSONArray(File(wav.path.removeSuffix(".wav") + ".json").readText())
                val config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(File(dir, segModel).path),
                        numThreads = 8,
                    ),
                    embedding = SpeakerEmbeddingExtractorConfig(model = File(dir, model).path, numThreads = 8),
                    clustering = FastClusteringConfig(numClusters = -1, threshold = threshold),
                    minDurationOn = 0.3f,
                    minDurationOff = 0.5f,
                )
                val sd = OfflineSpeakerDiarization(null, config)
                val started = System.nanoTime()
                val segments = sd.process(samples)
                seconds += (System.nanoTime() - started) / 1e9
                audioSeconds += samples.size / 16_000.0
                sd.release()
                val hyp = segments.map { Triple(it.start.toDouble(), it.end.toDouble(), it.speaker.toString()) }
                val refTurns = (0 until ref.length()).map { val t = ref.getJSONArray(it); Triple(t.getDouble(0), t.getDouble(1), t.getString(2)) }
                val (err, total, nHyp) = der(refTurns, hyp, samples.size / 16_000.0)
                refTotal += total
                errTotal += err
                line.append("  ${wav.nameWithoutExtension}: DER %.1f%% speakers %d/%d".format(100 * err / total, nHyp, refTurns.map { it.third }.toSet().size))
            }
            report("pyannote $model t=$threshold seg=$segModel DER %.1f%% RTF %.3f |".format(100 * errTotal / refTotal, seconds / audioSeconds) + line)
        }
    }

    /**
     * The app's way: diarization, then clusters that sound alike (-PdiarSame: how alike) made one voice
     * by [com.kjwindham.audiocool.speakers.WhoSaidWhat].
     */
    @Test
    fun pipeline() {
        assumeTrue(System.getProperty("sherpa.host.dir") != null && dir != null && System.getProperty("diar.same") != null)
        val meetings = File(dir, "eval").listFiles { f -> f.name.endsWith(".wav") }!!.sorted()
        val models = System.getProperty("diar.models")?.split(",") ?: listOf("3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx")
        val thresholds = System.getProperty("diar.thresholds")?.split(",")?.map { it.toFloat() } ?: listOf(0.5f)
        val sames = System.getProperty("diar.same")!!.split(",").map { it.toFloat() }
        val segModel = System.getProperty("diar.seg") ?: "seg.onnx"
        val audio = meetings.associateWith { readWav(it) }
        for (model in models) {
            val extractor = com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = File(dir, model).path, numThreads = 8))
            for (threshold in thresholds) {
                val config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(File(dir, segModel).path),
                        numThreads = 8,
                    ),
                    embedding = SpeakerEmbeddingExtractorConfig(model = File(dir, model).path, numThreads = 8),
                    clustering = FastClusteringConfig(numClusters = -1, threshold = threshold),
                    minDurationOn = 0.3f,
                    minDurationOff = 0.5f,
                )
                val sd = OfflineSpeakerDiarization(null, config)
                val chunkS = System.getProperty("diar.chunk")?.toInt()
                if (chunkS != null) {
                    // The app's way with long recordings: a chunk at a time, voices matched across chunks.
                    for (same in sames) {
                        var refTotal = 0.0
                        var errTotal = 0.0
                        val line = StringBuilder()
                        for (wav in meetings) {
                            val samples = audio.getValue(wav)
                            val who = com.kjwindham.audiocool.speakers.WhoSaidWhat(sd, extractor, same)
                            var at = 0
                            while (at < samples.size) {
                                val end = minOf(samples.size, at + chunkS * 16_000)
                                who.chunk(samples.copyOfRange(at, end), at * 1000L / 16_000)
                                at = end
                            }
                            val (turns, _) = who.result()
                            val hyp = turns.map { Triple(it.startMs / 1000.0, it.endMs / 1000.0, it.voice.toString()) }
                            val ref = JSONArray(File(wav.path.removeSuffix(".wav") + ".json").readText())
                            val refTurns = (0 until ref.length()).map { val t = ref.getJSONArray(it); Triple(t.getDouble(0), t.getDouble(1), t.getString(2)) }
                            val (err, total, nHyp) = der(refTurns, hyp, samples.size / 16_000.0)
                            refTotal += total
                            errTotal += err
                            line.append("  ${wav.nameWithoutExtension}: DER %.1f%% speakers %d/%d".format(100 * err / total, nHyp, refTurns.map { it.third }.toSet().size))
                        }
                        report("chunked ${chunkS}s $model t=$threshold same=$same DER %.1f%% |".format(100 * errTotal / refTotal) + line)
                    }
                    sd.release()
                    continue
                }
                val diarized = meetings.associateWith { sd.process(audio.getValue(it)) }
                sd.release()
                for (same in sames) {
                    var refTotal = 0.0
                    var errTotal = 0.0
                    val line = StringBuilder()
                    val started = System.nanoTime()
                    for (wav in meetings) {
                        val who = com.kjwindham.audiocool.speakers.WhoSaidWhat({ _ -> diarized.getValue(wav) }, extractor, same)
                        who.chunk(audio.getValue(wav), 0)
                        val (turns, _) = who.result()
                        val hyp = turns.map { Triple(it.startMs / 1000.0, it.endMs / 1000.0, it.voice.toString()) }
                        val ref = JSONArray(File(wav.path.removeSuffix(".wav") + ".json").readText())
                        val refTurns = (0 until ref.length()).map { val t = ref.getJSONArray(it); Triple(t.getDouble(0), t.getDouble(1), t.getString(2)) }
                        val (err, total, nHyp) = der(refTurns, hyp, audio.getValue(wav).size / 16_000.0)
                        refTotal += total
                        errTotal += err
                        line.append("  ${wav.nameWithoutExtension}: DER %.1f%% speakers %d/%d".format(100 * err / total, nHyp, refTurns.map { it.third }.toSet().size))
                    }
                    report("pipeline $model t=$threshold same=$same DER %.1f%% mergeS %.1f |".format(100 * errTotal / refTotal, (System.nanoTime() - started) / 1e9) + line)
                }
            }
            extractor.release()
        }
    }

    /**
     * How alike the voiceprints of clusters are when they're the same person, and when they're not: to
     * pick how alike counts as the same voice. Each cluster is the person who speaks most of it.
     */
    @Test
    fun similarities() {
        assumeTrue(System.getProperty("sherpa.host.dir") != null && dir != null && System.getProperty("diar.similarities") != null)
        val meetings = File(dir, "eval").listFiles { f -> f.name.endsWith(".wav") }!!.sorted()
        val models = System.getProperty("diar.models")?.split(",") ?: listOf("3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx")
        val segModel = System.getProperty("diar.seg") ?: "seg.onnx"
        for (model in models) {
            val extractor = com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = File(dir, model).path, numThreads = 8))
            val config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(File(dir, segModel).path), numThreads = 8),
                embedding = SpeakerEmbeddingExtractorConfig(model = File(dir, model).path, numThreads = 8),
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                minDurationOn = 0.3f,
                minDurationOff = 0.5f,
            )
            val sd = OfflineSpeakerDiarization(null, config)
            val same = ArrayList<Float>()
            val different = ArrayList<Float>()
            // Prints of each person across meetings, to see how well one is known again later.
            val byPerson = HashMap<String, MutableList<FloatArray>>()
            for (wav in meetings) {
                val samples = readWav(wav)
                val ref = JSONArray(File(wav.path.removeSuffix(".wav") + ".json").readText())
                val refTurns = (0 until ref.length()).map { val t = ref.getJSONArray(it); Triple(t.getDouble(0), t.getDouble(1), t.getString(2)) }
                val segments = sd.process(samples)
                val clusters = segments.groupBy { it.speaker }.mapNotNull { (_, segs) ->
                    // Who speaks most of it.
                    val talk = HashMap<String, Double>()
                    for (s in segs) for ((rs, re, who) in refTurns) {
                        val o = minOf(re, s.end.toDouble()) - maxOf(rs, s.start.toDouble())
                        if (o > 0) talk[who] = (talk[who] ?: 0.0) + o
                    }
                    val who = talk.maxByOrNull { it.value }?.key ?: return@mapNotNull null
                    val longest = segs.sortedByDescending { it.end - it.start }.take(6).filter { it.end - it.start >= 1.5f }
                    val prints = longest.mapNotNull { s ->
                        val from = (s.start * 16_000).toInt().coerceIn(0, samples.size)
                        val to = (s.end * 16_000).toInt().coerceIn(from, samples.size)
                        val stream = extractor.createStream()
                        stream.acceptWaveform(samples.copyOfRange(from, to), 16_000)
                        stream.inputFinished()
                        val v = if (extractor.isReady(stream)) extractor.compute(stream) else null
                        stream.release()
                        v
                    }
                    if (prints.isEmpty()) null else who to com.kjwindham.audiocool.speakers.Voices.normalized(FloatArray(prints[0].size) { i -> prints.sumOf { it[i].toDouble() }.toFloat() })
                }
                for (i in clusters.indices) for (j in i + 1 until clusters.size) {
                    val c = com.kjwindham.audiocool.speakers.Voices.cosine(clusters[i].second, clusters[j].second)
                    if (clusters[i].first == clusters[j].first) same += c else different += c
                }
                for ((who, p) in clusters) byPerson.getOrPut(who) { ArrayList() } += p
            }
            sd.release()
            extractor.release()
            fun pct(xs: List<Float>) = xs.sorted().let { s -> if (s.isEmpty()) "-" else listOf(10, 25, 50, 75, 90).joinToString("/") { p -> "%.2f".format(s[(s.size - 1) * p / 100]) } }
            report("similar $model same(n=${same.size}) p10/25/50/75/90 ${pct(same)}   different(n=${different.size}) ${pct(different)}")
        }
    }

    /**
     * Knowing someone again in another meeting: the voices the app's way finds in each meeting (each the
     * person who speaks most of it), compared across meetings, the same person and not.
     */
    @Test
    fun recognition() {
        assumeTrue(System.getProperty("sherpa.host.dir") != null && dir != null && System.getProperty("diar.recognition") != null)
        val meetings = File(dir, "eval").listFiles { f -> f.name.endsWith(".wav") }!!.sorted()
        val model = System.getProperty("diar.models")?.split(",")?.first() ?: "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"
        val threshold = System.getProperty("diar.thresholds")?.split(",")?.first()?.toFloat() ?: 1.1f
        val same = System.getProperty("diar.same")?.split(",")?.first()?.toFloat() ?: 0.45f
        val extractor = com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = File(dir, model).path, numThreads = 8))
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(File(dir, System.getProperty("diar.seg") ?: "seg.int8.onnx").path), numThreads = 8),
            embedding = SpeakerEmbeddingExtractorConfig(model = File(dir, model).path, numThreads = 8),
            clustering = FastClusteringConfig(numClusters = -1, threshold = threshold),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        )
        val sd = OfflineSpeakerDiarization(null, config)
        // Each meeting's voices: who each is, and its voiceprint.
        val voices = meetings.map { wav ->
            val samples = readWav(wav)
            val ref = JSONArray(File(wav.path.removeSuffix(".wav") + ".json").readText())
            val refTurns = (0 until ref.length()).map { val t = ref.getJSONArray(it); Triple(t.getDouble(0), t.getDouble(1), t.getString(2)) }
            val who = com.kjwindham.audiocool.speakers.WhoSaidWhat(sd, extractor, same)
            who.chunk(samples, 0)
            val (turns, prints) = who.result()
            prints.indices.mapNotNull { v ->
                val talk = HashMap<String, Double>()
                for (t in turns.filter { it.voice == v }) for ((rs, re, person) in refTurns) {
                    val o = minOf(re, t.endMs / 1000.0) - maxOf(rs, t.startMs / 1000.0)
                    if (o > 0) talk[person] = (talk[person] ?: 0.0) + o
                }
                talk.maxByOrNull { it.value }?.key?.let { it to prints[v] }
            }
        }
        sd.release()
        extractor.release()
        val samePerson = ArrayList<Float>()
        val others = ArrayList<Float>()
        for (a in voices.indices) for (b in a + 1 until voices.size) for ((pa, va) in voices[a]) for ((pb, vb) in voices[b]) {
            val c = com.kjwindham.audiocool.speakers.Voices.cosine(va, vb)
            if (pa == pb) samePerson += c else others += c
        }
        fun pct(xs: List<Float>) = xs.sorted().let { s -> if (s.isEmpty()) "-" else listOf(0, 10, 25, 50, 75, 90, 100).joinToString("/") { p -> "%.2f".format(s[(s.size - 1) * p / 100]) } }
        report("recognition $model t=$threshold same=$same  same person(n=${samePerson.size}) min/10/25/50/75/90/max ${pct(samePerson)}   others(n=${others.size}) ${pct(others)}")
    }

    /** Diarization error (missed + false alarm + confusion, with the best one-to-one speaker mapping), the reference speech, and how many speakers were found; in 10 ms frames. */
    private fun der(ref: List<Triple<Double, Double, String>>, hyp: List<Triple<Double, Double, String>>, seconds: Double): Triple<Double, Double, Int> {
        val n = (seconds * 100).toInt() + 1
        val refSpk = ref.map { it.third }.distinct()
        val hypSpk = hyp.map { it.third }.distinct()
        val r = IntArray(n) { -1 }
        val h = IntArray(n) { -1 }
        for ((s, e, k) in ref) for (i in (s * 100).toInt() until minOf(n, (e * 100).toInt())) r[i] = refSpk.indexOf(k)
        for ((s, e, k) in hyp) for (i in (s * 100).toInt() until minOf(n, (e * 100).toInt())) h[i] = hypSpk.indexOf(k)
        // Overlap counts between each reference and found speaker; then a greedy best mapping.
        val counts = Array(refSpk.size) { IntArray(hypSpk.size) }
        for (i in 0 until n) if (r[i] >= 0 && h[i] >= 0) counts[r[i]][h[i]]++
        val map = IntArray(hypSpk.size) { -1 }
        val usedRef = BooleanArray(refSpk.size)
        val pairs = refSpk.indices.flatMap { a -> hypSpk.indices.map { b -> Triple(a, b, counts[a][b]) } }.sortedByDescending { it.third }
        for ((a, b, _) in pairs) if (!usedRef[a] && map[b] < 0) {
            map[b] = a
            usedRef[a] = true
        }
        var err = 0.0
        var total = 0.0
        for (i in 0 until n) {
            if (r[i] >= 0) total++
            when {
                r[i] >= 0 && h[i] < 0 -> err++
                r[i] < 0 && h[i] >= 0 -> err++
                r[i] >= 0 && map[h[i]] != r[i] -> err++
            }
        }
        return Triple(err, total, hypSpk.size)
    }

    /** Each result as soon as it's in, in results.txt (a long run's output only shows at the end otherwise). */
    private fun report(line: String) {
        println("DEBUG $line")
        File(dir, "results.txt").appendText(line + "\n")
    }

    private fun readWav(f: File): FloatArray {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bb.limit()) {
            val id = String(ByteArray(4) { bb.get(pos + it) })
            val size = bb.getInt(pos + 4)
            if (id == "data") {
                return FloatArray(size / 2) { bb.getShort(pos + 8 + it * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data in $f")
    }
}
