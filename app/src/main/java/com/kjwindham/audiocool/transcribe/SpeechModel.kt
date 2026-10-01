package com.kjwindham.audiocool.transcribe

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * The on-device speech model: NVIDIA Parakeet 0.6B (English, int8), downloaded once from Hugging Face.
 * In tests on lecture-style audio it made roughly a quarter of the errors of Moonshine Base, which
 * the app used before, and it doesn't drop long stretches of speech the way Moonshine did.
 */
object SpeechModel {
    const val ID = "parakeet-unified-en-0.6b"
    const val NAME = "Parakeet 0.6B"

    // Pinned to a commit, so the files can't change underneath the checksums below.
    private const val BASE_URL =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming/" +
            "resolve/8c3a10fb13408c7a7054f6898958bf1c64a8d6c7"

    data class ModelFile(val name: String, val size: Long, val sha256: String)

    val files = listOf(
        ModelFile("encoder.int8.onnx", 654_040_552, "6716910b7a0833997fec7a410494c995d70124001a0e9b66d6370d6aced577e0"),
        ModelFile("decoder.int8.onnx", 7_257_753, "a5e223392c90e75f8144cdb5eb95af7625db389e39edef2bd1a9c872b3298fe6"),
        ModelFile("joiner.int8.onnx", 1_735_860, "869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a"),
        ModelFile("tokens.txt", 8_952, "dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff"),
    )

    val totalBytes: Long = files.sumOf { it.size }

    /** Models earlier versions downloaded; removed once this one is in place. */
    private val OLD_MODEL_DIRS = listOf("models/moonshine-base-en-v2")

    fun dir(context: Context) = File(context.filesDir, "models/$ID")

    fun recognizerConfig(context: Context, threads: Int) = recognizerConfig(dir(context), threads)

    /** A NeMo transducer (TDT): encoder, decoder and joiner. */
    fun recognizerConfig(modelDir: File, threads: Int) = OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(modelDir, "encoder.int8.onnx").path,
                decoder = File(modelDir, "decoder.int8.onnx").path,
                joiner = File(modelDir, "joiner.int8.onnx").path,
            ),
            tokens = File(modelDir, "tokens.txt").path,
            modelType = "nemo_transducer",
            numThreads = threads,
        ),
    )

    fun removeOldModels(context: Context) {
        OLD_MODEL_DIRS.forEach { File(context.filesDir, it).deleteRecursively() }
    }

    /** Every file is in place. Checksums were verified when each one was downloaded. */
    fun isReady(context: Context) = files.all { File(dir(context), it.name).length() == it.size }

    /** Downloads whatever is missing, resuming partial files, and verifies each one. */
    fun download(context: Context, isCancelled: () -> Boolean, onProgress: (done: Long, total: Long) -> Unit) {
        val dir = dir(context).apply { mkdirs() }
        var finished = 0L
        for (f in files) {
            val target = File(dir, f.name)
            if (target.length() != f.size) {
                val part = File(dir, f.name + ".part")
                fetch("$BASE_URL/${f.name}", part, f.size, isCancelled) { onProgress(finished + it, totalBytes) }
                if (part.length() != f.size || sha256(part) != f.sha256) {
                    part.delete()
                    throw IOException("The download of ${f.name} was damaged; try again")
                }
                target.delete()
                if (!part.renameTo(target)) throw IOException("Couldn't save ${f.name}")
            }
            finished += f.size
            onProgress(finished, totalBytes)
        }
    }

    private fun fetch(url: String, part: File, expected: Long, isCancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        var have = part.length()
        if (have >= expected) {
            part.delete()
            have = 0
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("The model download failed (HTTP $code)")
            }
            // A server that ignores the Range request sends the whole file again.
            val resume = code == HttpURLConnection.HTTP_PARTIAL && have > 0
            var total = if (resume) have else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, resume).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (isCancelled()) throw CancellationException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
