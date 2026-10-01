package com.kjwindham.audiocool.desktop

import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class DesktopException(message: String, val status: Int = 0) : IOException(message)

/** A desktop to pair with: its address and pairing code, e.g. from the QR code it shows. */
data class PairingInfo(val url: String, val token: String)

/**
 * Reads what the desktop's Pair page offers: its QR code (`audiocool://pair?url=...&token=...`) or a
 * typed address such as `192.168.1.20` or `192.168.1.20:8765`. Returns null if it isn't one.
 */
fun parsePairing(raw: String, typedToken: String? = null): PairingInfo? {
    val text = raw.trim()
    if (text.startsWith("audiocool://pair")) {
        val query = runCatching { URI(text).rawQuery }.getOrNull() ?: return null
        val params = query.split("&").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }.toMap()
        val url = params["url"]?.trimEnd('/') ?: return null
        val token = params["token"] ?: return null
        return PairingInfo(url, token)
    }
    if (text.isEmpty() || typedToken.isNullOrBlank()) return null
    var url = if (text.startsWith("http://") || text.startsWith("https://")) text else "http://$text"
    url = url.trimEnd('/')
    if (runCatching { URI(url).port }.getOrDefault(-1) == -1) url += ":8765"
    return PairingInfo(url, typedToken.trim())
}

/** Talks to AudioCool Desktop on the local network (API: desktop/README.md). */
class DesktopClient(baseUrl: String, private val token: String) {
    private val base = baseUrl.trimEnd('/')

    data class Model(val id: String, val name: String, val isDefault: Boolean)
    data class Info(val name: String, val models: List<Model>)
    data class Job(val id: String, val recordingId: String, val model: String?, val status: String, val progress: Float, val error: String?) {
        val active: Boolean get() = status == "queued" || status == "running"
    }

    /** The desktop's copy of a session; [transcriptModels] marks which transcripts the desktop made. */
    data class Remote(val session: Session, val transcriptModels: Map<String, String>, val jobs: List<Job>)

    fun ping(): Info {
        val o = request("GET", "/api/v1/ping")
        val models = o.optJSONArray("models") ?: JSONArray()
        return Info(
            name = o.optString("name", "Desktop"),
            models = List(models.length()) { i ->
                val m = models.getJSONObject(i)
                Model(m.getString("id"), m.optString("name", m.getString("id")), m.optBoolean("default"))
            },
        )
    }

    /** Sends the session's data; returns the audio files the desktop still needs. */
    fun putSession(session: Session, fileSizes: Map<String, Long>): List<String> {
        val body = JSONObject().put("session", JSONObject(SessionJson.encode(session))).put("files", JSONObject(fileSizes))
        val o = request("PUT", "/api/v1/sessions/${enc(session.id)}", body)
        val needed = o.optJSONArray("needed") ?: JSONArray()
        return List(needed.length()) { needed.getString(it) }
    }

    fun upload(sessionId: String, name: String, file: File, onProgress: (sent: Long) -> Unit = {}) {
        val conn = open("PUT", "/api/v1/sessions/${enc(sessionId)}/files/${enc(name)}")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(file.length())
            conn.outputStream.use { out ->
                file.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
            check(conn)
        } finally {
            conn.disconnect()
        }
    }

    fun transcribe(sessionId: String, model: String? = null, recordingIds: List<String>? = null): List<Job> {
        val body = JSONObject()
            .put("model", model ?: JSONObject.NULL)
            .put("recordingIds", recordingIds?.let { JSONArray(it) } ?: JSONObject.NULL)
        return jobs(request("POST", "/api/v1/sessions/${enc(sessionId)}/transcribe", body).optJSONArray("jobs"))
    }

    fun get(sessionId: String): Remote {
        val o = request("GET", "/api/v1/sessions/${enc(sessionId)}")
        val models = o.optJSONObject("transcriptModels") ?: JSONObject()
        return Remote(
            session = SessionJson.decode(o.getJSONObject("session").toString()),
            transcriptModels = models.keys().asSequence().associateWith { models.getString(it) },
            jobs = jobs(o.optJSONArray("jobs")),
        )
    }

    private fun jobs(a: JSONArray?): List<Job> = if (a == null) emptyList() else List(a.length()) { i ->
        val j = a.getJSONObject(i)
        Job(
            id = j.optString("id"),
            recordingId = j.optString("recordingId"),
            model = if (j.isNull("model")) null else j.optString("model"),
            status = j.optString("status"),
            progress = j.optDouble("progress", 0.0).toFloat(),
            error = if (j.isNull("error")) null else j.optString("error"),
        )
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = open(method, path)
        try {
            if (body != null) {
                val bytes = body.toString().toByteArray()
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            check(conn)
            val text = conn.inputStream.use { it.readBytes().decodeToString() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, path: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

    private fun check(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code in 200..299) return
        val detail = runCatching { conn.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull().orEmpty().take(200)
        throw DesktopException(
            when (code) {
                401 -> "The desktop didn't accept the pairing code. Pair again."
                404 -> "The desktop doesn't have that (HTTP 404)."
                else -> "The desktop reported an error (HTTP $code) $detail".trim()
            },
            code,
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
