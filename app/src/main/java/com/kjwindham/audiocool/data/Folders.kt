package com.kjwindham.audiocool.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
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

/** A folder sessions are filed in. [description] says what's in it, for search. */
data class Folder(val name: String, val createdAt: Long, val description: String? = null)

/** A folder as the main screen lists it: how many sessions it holds. */
data class FolderSummary(val name: String, val sessions: Int, val description: String?)

/**
 * The folders. A session names its own folder (so backups and the desktop keep it); this keeps the
 * folders themselves, including ones still empty, in files/folders.json.
 */
object FolderRepository {
    private const val TAG = "FolderRepository"

    private lateinit var file: File
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()
    private val io = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    fun init(context: Context) {
        file = File(context.filesDir, "folders.json")
        _folders.value = load()
    }

    /** Makes a folder named [name], or finds the one already called that (whatever its case); returns its name. */
    fun create(name: String): String {
        val wanted = name.trim()
        _folders.value.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it.name }
        _folders.update { it + Folder(wanted, System.currentTimeMillis()) }
        save()
        return wanted
    }

    /** Renames [from] to [to], and its sessions with it; to an existing folder's name, that merges the two. */
    fun rename(from: String, to: String): String {
        val existing = _folders.value.firstOrNull { it.name.equals(to.trim(), ignoreCase = true) && it.name != from }
        val name = existing?.name ?: to.trim()
        _folders.update { list ->
            val kept = if (existing != null) list.filterNot { it.name == from } else list.map { if (it.name == from) it.copy(name = name) else it }
            // A folder known only from its sessions (as after a restore) joins the list under its new name.
            if (kept.none { it.name == name }) kept + Folder(name, System.currentTimeMillis()) else kept
        }
        save()
        SessionRepository.renameFolder(from, name)
        return name
    }

    /** Removes the folder; its sessions stay, in no folder. */
    fun delete(name: String) {
        _folders.update { list -> list.filterNot { it.name == name } }
        save()
        SessionRepository.renameFolder(name, null)
    }

    fun describe(name: String, description: String?) {
        _folders.update { list -> list.map { if (it.name == name) it.copy(description = description) else it } }
        save()
    }

    private fun save() {
        val snapshot = _folders.value
        io.launch {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                val json = JSONArray().apply {
                    snapshot.forEach { f ->
                        put(JSONObject().put("name", f.name).put("createdAt", f.createdAt).apply { f.description?.let { put("description", it) } })
                    }
                }
                out.write(JSONObject().put("folders", json).toString(2).toByteArray())
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                Log.e(TAG, "Couldn't save the folders", e)
            }
        }
    }

    private fun load(): List<Folder> = try {
        val array = JSONObject(String(AtomicFile(file).readFully())).optJSONArray("folders") ?: JSONArray()
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Folder(o.getString("name"), o.optLong("createdAt"), o.optString("description").takeIf { it.isNotBlank() })
        }
    } catch (e: FileNotFoundException) {
        emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't read the folders", e)
        emptyList()
    }
}

/**
 * Every folder, A to Z: the ones made here, and any a session names that isn't among them (as after a
 * restore), each with how many sessions it holds.
 */
fun folderSummaries(sessions: List<Session>, folders: List<Folder>): List<FolderSummary> {
    val counts = sessions.mapNotNull { it.folder }.groupingBy { it }.eachCount()
    val named = folders.map { FolderSummary(it.name, counts[it.name] ?: 0, it.description) }
    val unlisted = counts.keys.filter { name -> folders.none { it.name == name } }.map { FolderSummary(it, counts.getValue(it), null) }
    return (named + unlisted).sortedBy { it.name.lowercase() }
}
