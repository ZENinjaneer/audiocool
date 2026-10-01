package com.kjwindham.audiocool

import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.kjwindham.audiocool.transcribe.SpeechModel
import com.kjwindham.audiocool.transcribe.Transcriber
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compares speech models on real speech: word error rate and speed. Not part of the normal suite;
 * runs only with -PasrBenchDir=<dir with refs.json and wav/> and -PasrBenchModels=type:dir,...
 * (types: moonshine, nemo_transducer, canary, qwen3, whisper).
 */
class ModelBenchmarkHostTest {
    private val hostDir = System.getProperty("sherpa.host.dir")?.let(::File)
    private val benchDir = System.getProperty("asr.bench.dir")?.let(::File)
    private val models = System.getProperty("asr.bench.models")?.split(",")?.filter { it.isNotBlank() }.orEmpty()

    @Test
    fun benchmark() {
        assumeTrue(hostDir != null && benchDir != null && models.isNotEmpty())
        val refs = JSONArray(File(benchDir, "refs.json").readText())
        val threads = System.getProperty("asr.bench.threads")?.toInt() ?: 4
        for (spec in models) {
            val (type, path) = spec.split(":", limit = 2)
            val dir = File(path)
            val loadStart = System.nanoTime()
            val recognizer = OfflineRecognizer(null, config(type, dir, threads))
            val loadSeconds = (System.nanoTime() - loadStart) / 1e9
            val results = JSONObject().put("model", dir.name).put("type", type).put("threads", threads).put("loadSeconds", loadSeconds).put("leveling", leveling)
            val bySet = LinkedHashMap<String, DoubleArray>() // errors, words, audio s, compute s
            val samplesOut = JSONArray()
            for (i in 0 until refs.length()) {
                val item = refs.getJSONObject(i)
                val audio = readWav(File(benchDir, "wav/${item.getString("name")}.wav"))
                val started = System.nanoTime()
                val hypothesis = if (item.optBoolean("long")) longForm(recognizer, audio) else recognize(recognizer, audio)
                val compute = (System.nanoTime() - started) / 1e9
                val ref = normalize(item.getString("text"))
                val hyp = normalize(hypothesis)
                val errors = editDistance(ref, hyp)
                val acc = bySet.getOrPut(item.getString("set")) { DoubleArray(4) }
                acc[0] += errors.toDouble()
                acc[1] += ref.size.toDouble()
                acc[2] += audio.size / 16_000.0
                acc[3] += compute
                samplesOut.put(
                    JSONObject().put("name", item.getString("name")).put("errors", errors).put("words", ref.size)
                        .put("hyp", hypothesis).put("ref", item.getString("text")),
                )
            }
            recognizer.release()
            val sets = JSONObject()
            val line = StringBuilder("${dir.name} (load %.1f s):".format(loadSeconds))
            for ((set, a) in bySet) {
                val wer = 100 * a[0] / a[1]
                val speed = a[2] / a[3]
                sets.put(set, JSONObject().put("wer", wer).put("words", a[1]).put("audioSeconds", a[2]).put("computeSeconds", a[3]).put("realTimeFactor", speed))
                line.append("  $set WER %.1f%% (%.0fx RT)".format(wer, speed))
            }
            results.put("sets", sets).put("samples", samplesOut)
            val tag = "$leveling-${System.getProperty("asr.bench.maxSegment") ?: "20"}s"
            File(benchDir, "results-${dir.name}-$tag.json").writeText(results.toString(2))
            println("$line  [$tag]")
        }
    }

    private fun config(type: String, dir: File, threads: Int): OfflineRecognizerConfig {
        fun f(name: String) = File(dir, name).path
        fun glob(suffix: String) = dir.listFiles()!!.map { it.name }.filter { it.endsWith(suffix) }.minByOrNull { it.length }!!
        return when (type) {
            "moonshine" -> SpeechModel.recognizerConfig(dir, threads)
            "nemo_transducer" -> OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(f("encoder.int8.onnx"), f("decoder.int8.onnx"), f("joiner.int8.onnx")),
                    tokens = f("tokens.txt"),
                    modelType = "nemo_transducer",
                    numThreads = threads,
                ),
            )
            "canary" -> OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    canary = OfflineCanaryModelConfig(f("encoder.int8.onnx"), f("decoder.int8.onnx"), srcLang = "en", tgtLang = "en", usePnc = true),
                    tokens = f("tokens.txt"),
                    numThreads = threads,
                ),
            )
            "qwen3" -> OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(f("conv_frontend.onnx"), f("encoder.int8.onnx"), f("decoder.int8.onnx"), f("tokenizer")),
                    tokens = "",
                    numThreads = threads,
                ),
            )
            "whisper" -> OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(f(glob("-encoder.int8.onnx")), f(glob("-decoder.int8.onnx")), language = "en"),
                    tokens = f(glob("-tokens.txt")),
                    numThreads = threads,
                ),
            )
            else -> error("unknown model type $type")
        }
    }

    private fun recognize(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        stream.acceptWaveform(if (leveling != "none") Transcriber.level(samples) else samples, 16_000)
        recognizer.decode(stream)
        return recognizer.getResult(stream).text.also { stream.release() }
    }

    /** The app's pipeline: VAD splits the talk into phrases, each transcribed on its own. */
    private val leveling = System.getProperty("asr.bench.leveling") ?: "none"

    private fun longForm(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val maxSegment = System.getProperty("asr.bench.maxSegment")?.toFloat() ?: Transcriber.MAX_SEGMENT_SECONDS
        val vad = Vad(null, Transcriber.vadConfig(File(hostDir, "silero_vad.onnx").path, maxSegment))
        val transcriber = Transcriber(recognizer, vad, levelSegments = leveling == "segment")
        val leveler = if (leveling == "stream") LevelNormalizer(16_000) else null
        var i = 0
        while (i < samples.size) {
            val n = minOf(16_000, samples.size - i)
            val chunk = samples.copyOfRange(i, i + n)
            transcriber.accept(leveler?.process(chunk) ?: chunk)
            i += n
        }
        leveler?.let { transcriber.accept(it.flush()) }
        return transcriber.finish().joinToString(" ") { it.text }.also { vad.release() }
    }

    private fun readWav(f: File): FloatArray {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            if (id == "data") return FloatArray(size / 2) { bb.getShort(pos + 8 + it * 2) / 32768f }
            pos += 8 + size + (size and 1)
        }
        error("no audio in $f")
    }

    companion object {
        private val FILLERS = setOf("um", "uh", "hmm", "mm", "mhm", "mmhmm", "uhhuh", "ah", "eh", "er", "oh", "huh")

        /** Lowercase words without punctuation, fillers dropped, numbers spelled out (as the references are). */
        fun normalize(text: String): List<String> {
            val cleaned = text.lowercase()
                .replace("<unk>", " ")
                .replace("%", " percent")
                .replace(Regex("(?<=\\d),(?=\\d{3})"), "")
                .replace(Regex("[^a-z0-9' ]"), " ")
            return cleaned.split(" ").filter { it.isNotBlank() }
                .map { it.trim('\'') }
                .flatMap { if (it.all(Char::isDigit) && it.length <= 6) spell(it.toInt()).split(" ") else listOf(it) }
                .filter { it.isNotEmpty() && it.replace("-", "") !in FILLERS }
        }

        private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

        private fun spell(n: Int): String = when {
            n < 20 -> ONES[n]
            n < 100 -> TENS[n / 10] + if (n % 10 != 0) " " + ONES[n % 10] else ""
            // Years like 1984 are read as "nineteen eighty four".
            n in 1100..2099 && n % 100 != 0 && n / 100 != 20 -> spell(n / 100) + " " + spell(n % 100).let { if (n % 100 < 10) "oh $it" else it }
            n < 1000 -> ONES[n / 100] + " hundred" + if (n % 100 != 0) " " + spell(n % 100) else ""
            else -> spell(n / 1000) + " thousand" + if (n % 1000 != 0) " " + spell(n % 1000) else ""
        }

        fun editDistance(a: List<String>, b: List<String>): Int {
            var prev = IntArray(b.size + 1) { it }
            var cur = IntArray(b.size + 1)
            for (i in 1..a.size) {
                cur[0] = i
                for (j in 1..b.size) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                val t = prev
                prev = cur
                cur = t
            }
            return prev[b.size]
        }
    }
}
