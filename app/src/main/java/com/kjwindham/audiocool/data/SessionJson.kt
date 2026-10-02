package com.kjwindham.audiocool.data

import org.json.JSONArray
import org.json.JSONObject

object SessionJson {
    fun encode(s: Session): String = JSONObject().apply {
        put("version", 1)
        put("id", s.id)
        put("title", s.title)
        put("createdAt", s.createdAt)
        put("updatedAt", s.updatedAt)
        s.thumbnail?.let { put("thumbnail", it) }
        if (s.chapterSummaries.isNotEmpty()) {
            put("chapterSummaries", JSONArray().apply {
                s.chapterSummaries.forEach { put(JSONObject().put("key", it.key).put("text", it.text).put("basis", it.basis).put("model", it.model)) }
            })
        }
        s.summary?.let { sum ->
            put("summary", JSONObject().apply {
                put("text", sum.text)
                put("keyPoints", JSONArray(sum.keyPoints))
                put("actionItems", JSONArray(sum.actionItems))
                sum.title?.let { put("title", it) }
                put("basis", sum.basis)
                put("model", sum.model)
                put("createdAt", sum.createdAt)
            })
        }
        put("recordings", JSONArray().apply {
            s.recordings.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("file", r.file)
                    put("createdAt", r.createdAt)
                    put("durationMs", r.durationMs)
                    r.transcriptModel?.let { put("transcriptModel", it) }
                    r.transcript?.let { segments ->
                        put("transcript", JSONArray().apply {
                            segments.forEach { put(JSONObject().put("s", it.startMs).put("e", it.endMs).put("t", it.text)) }
                        })
                    }
                })
            }
        })
        put("notes", JSONArray().apply {
            s.notes.forEach { n ->
                put(JSONObject().apply {
                    put("id", n.id)
                    put("text", n.text)
                    put("createdAt", n.createdAt)
                    if (n.recId != null && n.offsetMs != null) {
                        put("recId", n.recId)
                        put("offsetMs", n.offsetMs)
                    }
                    n.photo?.let { put("photo", it) }
                    n.photoText?.let { put("photoText", it) }
                    if (n.spoken) put("spoken", true)
                })
            }
        })
    }.toString(2)

    fun decode(text: String): Session {
        val o = JSONObject(text)
        val recs = o.optJSONArray("recordings") ?: JSONArray()
        val notes = o.optJSONArray("notes") ?: JSONArray()
        return Session(
            id = o.getString("id"),
            title = o.optString("title", "Untitled"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            thumbnail = if (o.has("thumbnail")) o.getString("thumbnail") else null,
            recordings = List(recs.length()) { i ->
                val r = recs.getJSONObject(i)
                Recording(
                    id = r.getString("id"),
                    file = r.getString("file"),
                    createdAt = r.optLong("createdAt"),
                    durationMs = r.optLong("durationMs"),
                    transcriptModel = if (r.has("transcriptModel")) r.getString("transcriptModel") else null,
                    transcript = r.optJSONArray("transcript")?.let { a ->
                        List(a.length()) { j ->
                            val t = a.getJSONObject(j)
                            TranscriptSegment(t.getLong("s"), t.getLong("e"), t.getString("t"))
                        }
                    },
                )
            },
            notes = List(notes.length()) { i ->
                val n = notes.getJSONObject(i)
                Note(
                    id = n.getString("id"),
                    text = n.optString("text"),
                    createdAt = n.optLong("createdAt"),
                    recId = if (n.has("recId")) n.getString("recId") else null,
                    offsetMs = if (n.has("offsetMs")) n.getLong("offsetMs") else null,
                    photo = if (n.has("photo")) n.getString("photo") else null,
                    photoText = if (n.has("photoText")) n.getString("photoText") else null,
                    spoken = n.optBoolean("spoken"),
                )
            },
            chapterSummaries = o.optJSONArray("chapterSummaries")?.let { a ->
                List(a.length()) { i ->
                    val c = a.getJSONObject(i)
                    ChapterSummary(c.getString("key"), c.getString("text"), c.optString("basis"), c.optString("model"))
                }
            }.orEmpty(),
            summary = o.optJSONObject("summary")?.let { sum ->
                SessionSummary(
                    text = sum.getString("text"),
                    keyPoints = sum.optJSONArray("keyPoints").strings(),
                    actionItems = sum.optJSONArray("actionItems").strings(),
                    title = if (sum.has("title")) sum.getString("title") else null,
                    basis = sum.optString("basis"),
                    model = sum.optString("model"),
                    createdAt = sum.optLong("createdAt"),
                )
            },
        )
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
}
