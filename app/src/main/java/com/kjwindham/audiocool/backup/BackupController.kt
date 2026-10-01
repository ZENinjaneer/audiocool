package com.kjwindham.audiocool.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.annotation.SuppressLint
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionJson
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors

/** Keeps a copy of every session in a folder the user picked, so recordings survive uninstalling. */
object BackupController {
    data class State(
        val folderName: String? = null,
        val lastBackupAt: Long = 0,
        val running: Boolean = false,
        val error: String? = null,
    )

    private const val TAG = "Backup"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // The application context, which lives as long as the process, so holding it isn't a leak.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        _state.value = State(folderName = root()?.name, lastBackupAt = prefs.lastBackupAt)
        // Back up a few seconds after changes settle (and once at startup).
        scope.launch { SessionRepository.sessions.debounce(3_000).collect { runBackup(force = false) } }
    }

    /** Use [uri] (from the system folder picker) for backups from now on, and back up everything to it. */
    fun setFolder(uri: Uri) {
        prefs.backupFolder?.let { old -> if (old != uri.toString()) releasePermission(old.toUri()) }
        app.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.backupFolder = uri.toString()
        prefs.backupFingerprints = ""
        _state.update { it.copy(folderName = root()?.name, error = null) }
        backUpNow()
    }

    fun turnOff() {
        prefs.backupFolder?.let { releasePermission(it.toUri()) }
        prefs.backupFolder = null
        _state.update { State(lastBackupAt = it.lastBackupAt) }
    }

    /** Checks every session against the backup, re-copying anything missing. */
    fun backUpNow() {
        scope.launch { runBackup(force = true) }
    }

    /** Restores the sessions in the folder at [uri] that aren't in the app yet. */
    fun restore(uri: Uri, onDone: (restored: Int?, error: String?) -> Unit) {
        scope.launch {
            val result = runCatching {
                val folder = DocumentFile.fromTreeUri(app, uri)?.let { DocumentBackupFolder(app, it) }
                    ?: error("That folder can't be opened")
                BackupManager.restore(
                    folder,
                    exists = { SessionRepository.get(it) != null },
                    sessionDir = SessionRepository::sessionDir,
                    import = { SessionRepository.importSession(it) },
                )
            }
            // After reinstalling, the folder restored from is the natural place to keep backing up to.
            if (result.isSuccess && prefs.backupFolder == null) runCatching { setFolder(uri) }
            onDone(result.getOrNull(), result.exceptionOrNull()?.let { it.message ?: "Restore failed" })
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun root(): DocumentBackupFolder? {
        val uri = prefs.backupFolder?.toUri() ?: return null
        val doc = DocumentFile.fromTreeUri(app, uri)?.takeIf { it.canWrite() } ?: return null
        return DocumentBackupFolder(app, doc)
    }

    private fun runBackup(force: Boolean) {
        if (prefs.backupFolder == null) return
        val root = root()
        if (root == null) {
            _state.update { it.copy(error = "The backup folder isn't available any more. Choose it again.") }
            return
        }
        _state.update { it.copy(running = true, error = null) }
        val fingerprints = if (force) JSONObject() else runCatching { JSONObject(prefs.backupFingerprints) }.getOrDefault(JSONObject())
        var failed: String? = null
        for (session in SessionRepository.sessions.value) {
            val print = fingerprint(session)
            if (fingerprints.optString(session.id) == print) continue
            try {
                BackupManager.backUp(root, session) { SessionRepository.audioFile(session.id, it) }
                fingerprints.put(session.id, print)
                prefs.backupFingerprints = fingerprints.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Backing up ${session.id} failed", e)
                failed = "Backup failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (failed == null) prefs.lastBackupAt = System.currentTimeMillis()
        _state.update { it.copy(running = false, error = failed, lastBackupAt = prefs.lastBackupAt, folderName = root.name) }
    }

    /** Changes whenever anything that gets backed up changes. */
    private fun fingerprint(session: Session): String {
        val sizes = session.recordings.joinToString(",") { SessionRepository.audioFile(session.id, it).length().toString() }
        return "${SessionJson.encode(session).hashCode()}:$sizes"
    }

    private fun releasePermission(uri: Uri) {
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}
