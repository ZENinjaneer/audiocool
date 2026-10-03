package com.kjwindham.audiocool.speakers

import android.content.Context
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.kjwindham.audiocool.transcribe.SpeechModel.ModelFile
import com.kjwindham.audiocool.util.fetchResumable
import com.kjwindham.audiocool.util.sha256
import java.io.File
import java.io.IOException

/**
 * The models for "who said what", downloaded once from Hugging Face: pyannote's segmentation model
 * (finds the stretches each voice speaks; MIT licence) and 3D-Speaker's ERes2Net voiceprint model
 * (tells voices apart; Apache 2.0). Of the voiceprint models tried on AMI meetings, ERes2Net told
 * people apart best through a distant mic; see DiarizationBenchHostTest.
 */
object VoiceModel {
    private const val SEGMENTATION_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0"
    private const val VOICEPRINT_URL =
        "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/0743f301363dec56491a490f6d6cbc9d67f9a3bf"

    const val SEGMENTATION = "model.int8.onnx"
    const val VOICEPRINTS = "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"

    private val sources = listOf(
        ModelFile(SEGMENTATION, 1_540_506, "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d") to SEGMENTATION_URL,
        ModelFile(VOICEPRINTS, 26_485_263, "c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5") to VOICEPRINT_URL,
    )

    val totalBytes: Long = sources.sumOf { it.first.size }

    /**
     * How alike two voiceprints must be to be the same person. Tuned on AMI meetings: with these, a
     * headset recording came out with 22% diarization error and the right number of people, a single
     * distant mic with 42% and within one person. It leans to finding a person twice (fixed by naming
     * both the same) over taking two people for one (which can't be undone).
     */
    const val SAME_VOICE = 0.45f

    /** Someone named before, known again in a later recording. */
    const val KNOWN_VOICE = 0.6f

    /** The diarization's own clustering; clusters that sound alike are joined afterwards ([WhoSaidWhat]). */
    private const val CLUSTER_THRESHOLD = 1.1f

    fun dir(context: Context) = File(context.filesDir, "models/voices")

    fun isReady(context: Context) = sources.all { (f, _) -> File(dir(context), f.name).length() == f.size }

    fun diarizationConfig(context: Context, threads: Int) = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(File(dir(context), SEGMENTATION).path),
            numThreads = threads,
        ),
        embedding = embeddingConfig(context, threads),
        clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD),
        minDurationOn = 0.3f,
        minDurationOff = 0.5f,
    )

    fun embeddingConfig(context: Context, threads: Int) = SpeakerEmbeddingExtractorConfig(model = File(dir(context), VOICEPRINTS).path, numThreads = threads)

    /** Downloads whatever is missing, resuming partial files, and verifies each one. */
    fun download(context: Context, isCancelled: () -> Boolean, onProgress: (done: Long, total: Long) -> Unit) {
        val dir = dir(context).apply { mkdirs() }
        var finished = 0L
        for ((f, base) in sources) {
            val target = File(dir, f.name)
            if (target.length() != f.size) {
                val part = File(dir, f.name + ".part")
                fetchResumable("$base/${f.name}", part, f.size, isCancelled) { onProgress(finished + it, totalBytes) }
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
