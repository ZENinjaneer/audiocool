package com.kjwindham.audiocool

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * A stand-in for AudioCool Desktop implementing the phone-facing API (desktop/README.md), so the
 * phone's client can be tested end to end. Each transcription job finishes after [pollsToFinish] status checks.
 */
class FakeDesktop(private val token: String = "PAIR1234", private val pollsToFinish: Int = 2) : AutoCloseable {
    private val server = MockWebServer()
    val url: String get() = server.url("/").toString().trimEnd('/')

    val sessions = HashMap<String, JSONObject>()
    val files = HashMap<String, ByteArray>() // "sessionId/name"
    val transcribeRequests = ArrayList<JSONObject>()
    private val jobs = HashMap<String, MutableList<JSONObject>>() // sessionId -> jobs
    private val desktopTranscripts = HashMap<String, MutableMap<String, JSONArray>>() // sessionId -> recId -> segments
    private var polls = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = synchronized(this@FakeDesktop) { handle(request) }
        }
        server.start()
    }

    override fun close() = server.shutdown()

    private fun handle(ex: RecordedRequest): MockResponse {
        if (ex.getHeader("Authorization") != "Bearer $token") return send(401, JSONObject().put("detail", "bad token"))
        val parts = ex.requestUrl!!.encodedPath.trim('/').split("/") // api v1 ...
        val method = ex.method
        val body = { ex.body.readUtf8() }
        return when {
            parts == listOf("api", "v1", "ping") -> send(200, JSONObject().put("app", "audiocool-desktop").put("version", "1.0").put("name", "Test PC")
                .put("models", JSONArray().put(JSONObject().put("id", "big-model").put("name", "Big Model").put("default", true))))
            parts.size == 4 && parts[2] == "sessions" && method == "PUT" -> {
                val put = JSONObject(body())
                val session = put.getJSONObject("session")
                sessions[parts[3]] = session
                val sizes = put.getJSONObject("files")
                val needed = JSONArray()
                sizes.keys().forEach { name -> if (files["${parts[3]}/$name"]?.size?.toLong() != sizes.getLong(name)) needed.put(name) }
                send(200, JSONObject().put("needed", needed))
            }
            parts.size == 6 && parts[4] == "files" && method == "PUT" -> {
                files["${parts[3]}/${parts[5]}"] = ex.body.readByteArray()
                MockResponse().setResponseCode(204)
            }
            parts.size == 5 && parts[4] == "transcribe" && method == "POST" -> {
                val post = JSONObject(body())
                transcribeRequests += post
                val session = sessions[parts[3]] ?: return send(404, JSONObject())
                val ids = post.optJSONArray("recordingIds")?.let { a -> List(a.length()) { a.getString(it) } }
                    ?: session.getJSONArray("recordings").let { a -> List(a.length()) { a.getJSONObject(it).getString("id") } }
                val created = JSONArray()
                for (id in ids) {
                    val job = JSONObject().put("id", "job-$id").put("recordingId", id).put("model", "big-model").put("status", "queued").put("progress", 0.0).put("error", JSONObject.NULL)
                    jobs.getOrPut(parts[3]) { ArrayList() } += job
                    created.put(job)
                }
                polls = 0
                send(202, JSONObject().put("jobs", created))
            }
            parts.size == 4 && parts[2] == "sessions" && method == "GET" -> {
                val id = parts[3]
                val session = sessions[id] ?: return send(404, JSONObject())
                polls++
                val sessionJobs = jobs[id].orEmpty()
                for (job in sessionJobs) {
                    if (job.getString("status") == "done") continue
                    if (polls >= pollsToFinish) {
                        job.put("status", "done").put("progress", 1.0)
                        desktopTranscripts.getOrPut(id) { HashMap() }[job.getString("recordingId")] =
                            JSONArray().put(JSONObject().put("s", 1_000).put("e", 4_000).put("t", "Transcribed by the desktop."))
                    } else {
                        job.put("status", "running").put("progress", 0.5)
                    }
                }
                val merged = JSONObject(session.toString())
                val models = JSONObject()
                val recs = merged.getJSONArray("recordings")
                for (i in 0 until recs.length()) {
                    val rec = recs.getJSONObject(i)
                    desktopTranscripts[id]?.get(rec.getString("id"))?.let {
                        rec.put("transcript", it)
                        models.put(rec.getString("id"), "big-model")
                    }
                }
                send(200, JSONObject().put("session", merged).put("transcriptModels", models).put("jobs", JSONArray(sessionJobs)))
            }
            else -> send(404, JSONObject())
        }
    }

    private fun send(code: Int, body: JSONObject) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())
}
