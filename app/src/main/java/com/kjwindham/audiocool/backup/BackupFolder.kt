package com.kjwindham.audiocool.backup

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The few folder operations backups need, so the logic works on a user-picked folder and in tests. */
interface BackupFolder {
    val name: String
    fun folders(): List<BackupFolder>
    fun folder(name: String): BackupFolder?
    fun createFolder(name: String): BackupFolder
    fun fileNames(): List<String>
    /** Null when there's no such file. */
    fun fileSize(name: String): Long?
    /** Creates the file, or replaces it if it exists. */
    fun write(name: String, source: InputStream)
    fun read(name: String): InputStream?
    fun delete(name: String)
}

/** A folder the user picked with the system file picker (Storage Access Framework). */
class DocumentBackupFolder(private val context: Context, private val doc: DocumentFile) : BackupFolder {
    // Listing a folder is a provider query, so it's done once per instance until something changes.
    private var cached: List<DocumentFile>? = null

    private fun children(): List<DocumentFile> = cached ?: doc.listFiles().toList().also { cached = it }

    private fun file(name: String) = children().firstOrNull { it.isFile && it.name == name }

    override val name: String get() = doc.name.orEmpty()

    override fun folders() = children().filter { it.isDirectory }.map { DocumentBackupFolder(context, it) }

    override fun folder(name: String) =
        children().firstOrNull { it.isDirectory && it.name == name }?.let { DocumentBackupFolder(context, it) }

    override fun createFolder(name: String): BackupFolder {
        folder(name)?.let { return it }
        val created = doc.createDirectory(name) ?: throw IOException("Couldn't create the folder $name")
        cached = null
        return DocumentBackupFolder(context, created)
    }

    override fun fileNames() = children().filter { it.isFile }.mapNotNull { it.name }

    override fun fileSize(name: String) = file(name)?.length()

    override fun write(name: String, source: InputStream) {
        // Replace rather than overwrite in place: not every provider supports truncating ("wt") writes.
        file(name)?.delete()
        // application/octet-stream keeps the name exactly as given (no extension added).
        val target = doc.createFile("application/octet-stream", name) ?: throw IOException("Couldn't create $name")
        cached = null
        val out = context.contentResolver.openOutputStream(target.uri) ?: throw IOException("Couldn't write $name")
        out.use { source.copyTo(it) }
    }

    override fun read(name: String): InputStream? = file(name)?.let { context.contentResolver.openInputStream(it.uri) }

    override fun delete(name: String) {
        children().firstOrNull { it.name == name }?.delete()
        cached = null
    }
}

/** A plain directory; used in tests. */
class FileBackupFolder(private val dir: File) : BackupFolder {
    override val name: String get() = dir.name
    override fun folders() = dir.listFiles().orEmpty().filter { it.isDirectory }.map { FileBackupFolder(it) }
    override fun folder(name: String) = File(dir, name).takeIf { it.isDirectory }?.let { FileBackupFolder(it) }
    override fun createFolder(name: String) = FileBackupFolder(File(dir, name).apply { mkdirs() })
    override fun fileNames() = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }
    override fun fileSize(name: String) = File(dir, name).takeIf { it.isFile }?.length()
    override fun write(name: String, source: InputStream) {
        File(dir, name).outputStream().use { source.copyTo(it) }
    }
    override fun read(name: String): InputStream? = File(dir, name).takeIf { it.isFile }?.inputStream()
    override fun delete(name: String) {
        File(dir, name).delete()
    }
}
