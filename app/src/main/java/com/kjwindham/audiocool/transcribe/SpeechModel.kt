package com.kjwindham.audiocool.transcribe

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** The on-device speech model, Moonshine Base v2 (English), downloaded once from Hugging Face. */
object SpeechModel {
    // Pinned to a commit, so the files can't change underneath the checksums below.
    private const val BASE_URL =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27/" +
            "resolve/8f4d6c58c03d40bcea40043bb7120a878f2bbef6"

    data class ModelFile(val name: String, val size: Long, val sha256: String)

    val files = listOf(
        ModelFile("encoder_model.ort", 31_326_816, "7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf"),
        ModelFile("decoder_model_merged.ort", 109_424_400, "d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d"),
        ModelFile("tokens.txt", 549_350, "2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049"),
    )

    val totalBytes: Long = files.sumOf { it.size }

    fun dir(context: Context) = File(context.filesDir, "models/moonshine-base-en-v2")

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
