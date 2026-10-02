package com.kjwindham.audiocool.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Downloads [url] into [part], picking up where an earlier attempt left off (a model is hundreds of
 * megabytes, or gigabytes, and connections drop). [onBytes] gets the bytes in [part] so far.
 */
fun fetchResumable(url: String, part: File, expected: Long, isCancelled: () -> Boolean, onBytes: (Long) -> Unit) {
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

fun sha256(f: File): String {
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
