package com.kjwindham.audiocool.summarize

import android.content.Context
import com.kjwindham.audiocool.util.fetchResumable
import com.kjwindham.audiocool.util.sha256
import java.io.File
import java.io.IOException

/**
 * The summary model: Google's Gemma 4 E2B in LiteRT-LM's format, 2.6 GB, downloaded once from Hugging
 * Face. Kept out of Android's own backups (it's far over their size limit, and can be fetched again).
 */
object SummaryModel {
    const val ID = "gemma-4-e2b"
    const val NAME = "Gemma 4 E2B"
    const val LICENSE_NOTICE = "Gemma 4 is made by Google and released under the Apache License 2.0"
    const val LICENSE_URL = "https://huggingface.co/google/gemma-4-E2B-it"

    // Pinned to a commit, so the file can't change underneath the checksum.
    private const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
        "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm"
    const val SIZE = 2_588_147_712L
    private const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    fun file(context: Context) = File(context.noBackupFilesDir, "models/$ID/gemma-4-E2B-it.litertlm")

    fun isReady(context: Context) = file(context).length() == SIZE

    /** Downloads the model, resuming a partial download, and checks it. */
    fun download(context: Context, isCancelled: () -> Boolean, onProgress: (done: Long, total: Long) -> Unit) {
        val target = file(context)
        if (target.length() == SIZE) return
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        fetchResumable(URL, part, SIZE, isCancelled) { onProgress(it, SIZE) }
        if (part.length() != SIZE || sha256(part) != SHA256) {
            part.delete()
            throw IOException("The download was damaged; try again")
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't save the model")
    }

    /** Frees the space: the model, a partial download, and the engine's cache of it. */
    fun delete(context: Context) {
        file(context).parentFile?.deleteRecursively()
        context.cacheDir.listFiles { f -> f.name.startsWith("gemma-4-E2B-it") }?.forEach { it.delete() }
    }
}
