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
        put("recordings", JSONArray().apply {
            s.recordings.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("file", r.file)
                    put("createdAt", r.createdAt)
                    put("durationMs", r.durationMs)
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
            recordings = List(recs.length()) { i ->
                val r = recs.getJSONObject(i)
                Recording(r.getString("id"), r.getString("file"), r.optLong("createdAt"), r.optLong("durationMs"))
            },
            notes = List(notes.length()) { i ->
                val n = notes.getJSONObject(i)
                Note(
                    id = n.getString("id"),
                    text = n.optString("text"),
                    createdAt = n.optLong("createdAt"),
                    recId = if (n.has("recId")) n.getString("recId") else null,
                    offsetMs = if (n.has("offsetMs")) n.getLong("offsetMs") else null,
                )
            },
        )
    }
}
