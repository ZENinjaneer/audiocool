package com.kjwindham.audiocool.transcribe

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.kjwindham.audiocool.util.fetchResumable
import com.kjwindham.audiocool.util.sha256
import java.io.File
import java.io.IOException

/**
 * The on-device speech model: NVIDIA Parakeet 0.6B (English, int8), downloaded once from Hugging Face.
 * In tests on lecture-style audio it made roughly a quarter of the errors of Moonshine Base, which
 * the app used before, and it doesn't drop long stretches of speech the way Moonshine did.
 */
object SpeechModel {
    const val ID = "parakeet-unified-en-0.6b"
    const val NAME = "Parakeet 0.6B"

    // The model's licence asks for this exact notice wherever the model is distributed.
    const val LICENSE_NOTICE = "Licensed by NVIDIA Corporation under the NVIDIA Open Model License"
    const val LICENSE_URL = "https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/"

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
                fetchResumable("$BASE_URL/${f.name}", part, f.size, isCancelled) { onProgress(finished + it, totalBytes) }
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
}
