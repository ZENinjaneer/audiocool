package com.kjwindham.audiocool.speakers

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.kjwindham.audiocool.data.SessionJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/** A voice to know again: its name, and its voiceprint averaged over [samples] times it was named. */
class KnownVoice(val name: String, val voiceprint: FloatArray, val samples: Int = 1, val updatedAt: Long = System.currentTimeMillis())

/**
 * The voices you asked to have recognized next time, by name, in files/voices.json. They never leave
 * the phone: backups and the desktop don't carry them.
 */
object KnownVoices {
    private const val TAG = "KnownVoices"

    private lateinit var file: File
    private val _voices = MutableStateFlow<List<KnownVoice>>(emptyList())
    val voices: StateFlow<List<KnownVoice>> = _voices.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    fun init(context: Context) {
        file = File(context.filesDir, "voices.json")
        _voices.value = load()
    }

    /** Remembers [voiceprint] as [name]'s, averaged with what's known of them already. */
    fun remember(name: String, voiceprint: FloatArray) {
        _voices.update { list ->
            val known = list.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val updated = if (known == null) {
                KnownVoice(name, Voices.normalized(voiceprint))
            } else {
                val n = known.samples
                val mixed = FloatArray(voiceprint.size) { (known.voiceprint[it] * n + Voices.normalized(voiceprint)[it]) / (n + 1) }
                KnownVoice(known.name, Voices.normalized(mixed), n + 1)
            }
            list.filterNot { it.name.equals(name, ignoreCase = true) } + updated
        }
        save()
    }

    fun forget(name: String) {
        _voices.update { list -> list.filterNot { it.name.equals(name, ignoreCase = true) } }
        save()
    }

    fun isKnown(name: String) = _voices.value.any { it.name.equals(name, ignoreCase = true) }

    private fun save() {
        val snapshot = _voices.value
        io.launch {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                val json = JSONArray().apply {
                    snapshot.forEach { v ->
                        put(JSONObject().put("name", v.name).put("print", SessionJson.encodeVoiceprint(v.voiceprint)).put("samples", v.samples).put("updatedAt", v.updatedAt))
                    }
                }
                out.write(JSONObject().put("voices", json).toString(2).toByteArray())
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                Log.e(TAG, "Couldn't save the voices", e)
            }
        }
    }

    private fun load(): List<KnownVoice> = try {
        val array = JSONObject(String(AtomicFile(file).readFully())).optJSONArray("voices") ?: JSONArray()
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            SessionJson.decodeVoiceprint(o.getString("print"))?.let { KnownVoice(o.getString("name"), it, o.optInt("samples", 1), o.optLong("updatedAt")) }
        }
    } catch (e: FileNotFoundException) {
        emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't read the voices", e)
        emptyList()
    }
}
