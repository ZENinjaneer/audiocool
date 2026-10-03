package com.kjwindham.audiocool.search

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.TimelineMode
import com.kjwindham.audiocool.data.TimelineRow
import com.kjwindham.audiocool.data.timelineRows
import com.kjwindham.audiocool.summarize.parseChapterKey
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What each session's passages mean, as [MeaningModel] puts it: a vector for each paragraph of what was
 * said, note, slide's text and chapter summary, kept in files/meaning (about 770 bytes each), and
 * searched by how close they are to a query's.
 */
object MeaningIndex {
    /** A passage to find by meaning: where it is, and its text. */
    data class Passage(
        val key: String,
        val kind: HitKind,
        val text: String,
        val recId: String? = null,
        val atMs: Long? = null,
        val noteId: String? = null,
        val chapterKey: String? = null,
    ) {
        val hash: Int get() = text.hashCode()
    }

    /** A passage's vector, stored small: a byte per number, and the scale they share. */
    class Entry(val passage: Passage, val hash: Int, val vector: ByteArray, val scale: Float)

    /** A passage found, and how close its meaning is to what was searched for (0 to 1). */
    data class Found(val sessionId: String, val passage: Passage, val score: Float)

    private const val TAG = "MeaningIndex"
    private const val VERSION = 1

    private var dir: File? = null
    private val cache = ConcurrentHashMap<String, Map<String, Entry>>()

    fun init(context: Context) {
        dir = File(context.filesDir, "meaning")
    }

    /** Everything in [session] worth finding by meaning. */
    fun passages(session: Session): List<Passage> = timelineRows(session, TimelineMode.EVERYTHING).mapNotNull { row ->
        when (row) {
            is TimelineRow.Speech -> Passage("said:${row.recId}:${row.startMs}", HitKind.SPEECH, row.text, row.recId, row.startMs)
            is TimelineRow.Written -> row.note.text.takeIf { it.isNotBlank() }?.let { Passage("note:${row.note.id}", HitKind.NOTE, it, row.note.recId, row.note.offsetMs, row.note.id) }
            is TimelineRow.Photo -> row.note.photoText?.takeIf { it.isNotBlank() }?.let { Passage("photo:${row.note.id}", HitKind.PHOTO, it, row.note.recId, row.note.offsetMs, row.note.id) }
            is TimelineRow.Summary -> {
                val (recId, ms) = parseChapterKey(row.chapterKey) ?: return@mapNotNull null
                Passage("summary:${row.chapterKey}", HitKind.SUMMARY, listOfNotNull(row.title, row.text).joinToString(": "), recId, ms, chapterKey = row.chapterKey)
            }
            else -> null
        }
    }

    /** The passages of [session] not in its index yet, or changed since. */
    fun stale(session: Session): List<Passage> {
        val have = entries(session.id)
        return passages(session).filter { p -> have[p.key]?.hash != p.hash }
    }

    /** How many passages [session] has in its index. */
    fun size(sessionId: String) = entries(sessionId).size

    /** Adds [vectors] for [passages] to [session]'s index, dropping passages it doesn't have any more. */
    fun store(session: Session, passages: List<Passage>, vectors: List<FloatArray>) {
        val current = passages(session).associateBy { it.key }
        val merged = entries(session.id).filterKeys { it in current }.toMutableMap()
        passages.zip(vectors).forEach { (p, v) -> merged[p.key] = quantize(p, v) }
        cache[session.id] = merged
        save(session.id, merged.values)
    }

    fun remove(sessionId: String) {
        cache.remove(sessionId)
        dir?.let { File(it, "$sessionId.bin").delete() }
    }

    /** The passages of [sessions] closest in meaning to [query] (a vector of length 1), best first. */
    fun search(sessions: List<Session>, query: FloatArray, limit: Int = 8): List<Found> {
        val found = ArrayList<Found>()
        for (s in sessions) {
            for (e in entries(s.id).values) {
                if (e.vector.size != query.size) continue
                var dot = 0f
                for (i in query.indices) dot += query[i] * e.vector[i]
                found += Found(s.id, e.passage, dot * e.scale)
            }
        }
        val best = found.maxOfOrNull { it.score } ?: return emptyList()
        // Close enough to mean the same, and not far behind the best.
        return found.filter { it.score >= MIN_SCORE && it.score >= best - SPREAD }.sortedByDescending { it.score }.take(limit)
    }

    /** Scores below this aren't about the query; a step below the best is as far as it goes. */
    private const val MIN_SCORE = 0.86f
    private const val SPREAD = 0.05f

    private fun quantize(p: Passage, v: FloatArray): Entry {
        val most = v.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val scale = most / 127f
        return Entry(p, p.hash, ByteArray(v.size) { (v[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }, scale)
    }

    private fun entries(sessionId: String): Map<String, Entry> = cache.getOrPut(sessionId) { load(sessionId) }

    private fun save(sessionId: String, entries: Collection<Entry>) {
        val folder = dir ?: return
        folder.mkdirs()
        val atomic = AtomicFile(File(folder, "$sessionId.bin"))
        val stream = atomic.startWrite()
        try {
            DataOutputStream(stream.buffered()).let { out ->
                out.writeInt(VERSION)
                out.writeInt(entries.size)
                for (e in entries) {
                    val p = e.passage
                    out.writeUTF(p.key)
                    out.writeUTF(p.kind.name)
                    out.writeUTF(p.text.take(2_000))
                    out.writeUTF(p.recId.orEmpty())
                    out.writeLong(p.atMs ?: -1)
                    out.writeUTF(p.noteId.orEmpty())
                    out.writeUTF(p.chapterKey.orEmpty())
                    out.writeInt(e.hash)
                    out.writeFloat(e.scale)
                    out.writeInt(e.vector.size)
                    out.write(e.vector)
                }
                out.flush()
            }
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            Log.e(TAG, "Couldn't save the index of $sessionId", e)
        }
    }

    private fun load(sessionId: String): Map<String, Entry> {
        val file = File(dir ?: return emptyMap(), "$sessionId.bin")
        return try {
            DataInputStream(AtomicFile(file).openRead().buffered()).use { input ->
                if (input.readInt() != VERSION) return emptyMap()
                val n = input.readInt()
                HashMap<String, Entry>(n).apply {
                    repeat(n) {
                        val key = input.readUTF()
                        val kind = HitKind.valueOf(input.readUTF())
                        val text = input.readUTF()
                        val recId = input.readUTF().ifEmpty { null }
                        val atMs = input.readLong().takeIf { it >= 0 }
                        val noteId = input.readUTF().ifEmpty { null }
                        val chapterKey = input.readUTF().ifEmpty { null }
                        val hash = input.readInt()
                        val scale = input.readFloat()
                        val vector = ByteArray(input.readInt()).also { input.readFully(it) }
                        put(key, Entry(Passage(key, kind, text, recId, atMs, noteId, chapterKey), hash, vector, scale))
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the index of $sessionId", e)
            emptyMap()
        }
    }
}
