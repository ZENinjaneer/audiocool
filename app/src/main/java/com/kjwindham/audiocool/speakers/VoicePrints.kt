package com.kjwindham.audiocool.speakers

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.kjwindham.audiocool.data.SessionJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The voiceprints of each session's voices, to match the voices in its next recordings. They're kept
 * apart from the sessions, in files/voiceprints.json, so they stay on this phone: backups and the
 * desktop get the voices' names, never their voiceprints.
 */
object VoicePrints {
    private const val TAG = "VoicePrints"

    private var file: File? = null
    private val prints = ConcurrentHashMap<String, Map<Int, FloatArray>>()
    private val io = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    fun init(context: Context) {
        file = File(context.filesDir, "voiceprints.json")
        prints.clear()
        prints.putAll(load())
    }

    /** The session's voices' voiceprints, by voice id. */
    fun of(sessionId: String): Map<Int, FloatArray> = prints[sessionId].orEmpty()

    fun set(sessionId: String, voices: Map<Int, FloatArray>) {
        if (voices.isEmpty()) prints.remove(sessionId) else prints[sessionId] = voices
        save()
    }

    fun remove(sessionId: String) {
        if (prints.remove(sessionId) != null) save()
    }

    private fun save() {
        val target = file ?: return
        val snapshot = HashMap(prints)
        io.launch {
            val atomic = AtomicFile(target)
            val out = atomic.startWrite()
            try {
                val json = JSONObject()
                for ((session, voices) in snapshot) {
                    json.put(session, JSONObject().apply { voices.forEach { (id, v) -> put(id.toString(), SessionJson.encodeVoiceprint(v)) } })
                }
                out.write(json.toString().toByteArray())
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                Log.e(TAG, "Couldn't save the voiceprints", e)
            }
        }
    }

    private fun load(): Map<String, Map<Int, FloatArray>> = try {
        val json = JSONObject(String(AtomicFile(file!!).readFully()))
        json.keys().asSequence().associateWith { session ->
            val voices = json.getJSONObject(session)
            voices.keys().asSequence().mapNotNull { id -> SessionJson.decodeVoiceprint(voices.getString(id))?.let { id.toInt() to it } }.toMap()
        }
    } catch (e: FileNotFoundException) {
        emptyMap()
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't read the voiceprints", e)
        emptyMap()
    }
}
