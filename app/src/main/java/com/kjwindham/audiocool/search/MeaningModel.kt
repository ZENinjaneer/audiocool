package com.kjwindham.audiocool.search

import android.content.Context
import com.kjwindham.audiocool.util.fetchResumable
import com.kjwindham.audiocool.util.sha256
import java.io.File
import java.io.IOException

/**
 * The model for searching by meaning: IBM's Granite Embedding (311M, multilingual, Apache 2.0), as
 * LiteRT-LM runs it, downloaded once. It turns a passage into numbers that are close for passages
 * that mean much the same, whatever their words.
 */
object MeaningModel {
    private const val URL =
        "https://huggingface.co/litert-community/granite-embedding-311m-multilingual-r2/resolve/1b10683d630c0d2877bf0430af89668cda7f26c4/granite-embedding-311m-r2_wi8fc.litertlm"
    private const val NAME = "granite-embedding-311m-r2_wi8fc.litertlm"
    const val SIZE = 332_365_313L
    private const val SHA256 = "beb2be205abc766a670522e651be5713cecb4e4c33e5ef5c30f6a710d4226db5"

    /** A passage this long at most, in tokens: about a paragraph. */
    const val MAX_INPUT = 512

    fun file(context: Context) = File(context.filesDir, "models/meaning/$NAME")

    fun isReady(context: Context) = file(context).length() == SIZE

    fun download(context: Context, isCancelled: () -> Boolean, onProgress: (Float) -> Unit) {
        val target = file(context)
        if (target.length() == SIZE) return
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        fetchResumable(URL, part, SIZE, isCancelled) { onProgress(it.toFloat() / SIZE) }
        if (part.length() != SIZE || sha256(part) != SHA256) {
            part.delete()
            throw IOException("The download was damaged; try again")
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Couldn't save the model")
    }

    fun delete(context: Context) {
        file(context).parentFile?.deleteRecursively()
    }
}
