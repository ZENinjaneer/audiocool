package com.kjwindham.audiocool.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

object SessionJson {
    fun encode(s: Session): String = JSONObject().apply {
        put("version", 1)
        put("id", s.id)
        put("title", s.title)
        put("createdAt", s.createdAt)
        put("updatedAt", s.updatedAt)
        s.thumbnail?.let { put("thumbnail", it) }
        s.folder?.let { put("folder", it) }
        if (s.voices.isNotEmpty()) {
            put("voices", JSONArray().apply {
                s.voices.forEach { v ->
                    put(JSONObject().apply {
                        put("id", v.id)
                        v.name?.let { put("name", it) }
                    })
                }
            })
        }
        if (s.chapterSummaries.isNotEmpty()) {
            put("chapterSummaries", JSONArray().apply {
                s.chapterSummaries.forEach { c ->
                    put(JSONObject().put("key", c.key).put("text", c.text).put("basis", c.basis).put("model", c.model).apply { c.title?.let { put("title", it) } })
                }
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
                    r.speakers?.let { turns -> put("speakers", JSONArray().apply { turns.forEach { put(JSONArray().put(it.startMs).put(it.endMs).put(it.voice)) } }) }
                    r.transcript?.let { segments ->
                        put("transcript", JSONArray().apply {
                            segments.forEach { seg ->
                                put(JSONObject().put("s", seg.startMs).put("e", seg.endMs).put("t", seg.text).apply { seg.words?.let { put("w", JSONArray(it)) } })
                            }
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
            folder = o.optString("folder").takeIf { it.isNotBlank() },
            voices = o.optJSONArray("voices")?.let { a ->
                List(a.length()) { i ->
                    val v = a.getJSONObject(i)
                    Voice(v.getInt("id"), v.optString("name").takeIf { it.isNotBlank() })
                }
            }.orEmpty(),
            recordings = List(recs.length()) { i ->
                val r = recs.getJSONObject(i)
                Recording(
                    id = r.getString("id"),
                    file = r.getString("file"),
                    createdAt = r.optLong("createdAt"),
                    durationMs = r.optLong("durationMs"),
                    transcriptModel = if (r.has("transcriptModel")) r.getString("transcriptModel") else null,
                    speakers = r.optJSONArray("speakers")?.let { a ->
                        List(a.length()) { j -> a.getJSONArray(j).let { t -> SpeakerTurn(t.getLong(0), t.getLong(1), t.getInt(2)) } }
                    },
                    transcript = r.optJSONArray("transcript")?.let { a ->
                        List(a.length()) { j ->
                            val t = a.getJSONObject(j)
                            TranscriptSegment(
                                t.getLong("s"), t.getLong("e"), t.getString("t"),
                                t.optJSONArray("w")?.let { w -> (0 until w.length()).map { w.getInt(it) } },
                            )
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
                    ChapterSummary(c.getString("key"), c.getString("text"), c.optString("basis"), c.optString("model"), c.optString("title").takeIf { it.isNotBlank() })
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

    /** A voiceprint as text: its floats' bytes, in Base64. */
    fun encodeVoiceprint(v: FloatArray): String {
        val bytes = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { bytes.putFloat(it) }
        return Base64.getEncoder().encodeToString(bytes.array())
    }

    fun decodeVoiceprint(text: String): FloatArray? = runCatching {
        val bytes = ByteBuffer.wrap(Base64.getDecoder().decode(text)).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray(bytes.remaining() / 4) { bytes.getFloat() }
    }.getOrNull()
}
