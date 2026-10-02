package com.kjwindham.audiocool.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.kjwindham.audiocool.ocr.SlideText
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photos in a session (of the slides, say), each a note linked to its moment. They're saved as JPEGs
 * in the session's folder, upright and at most [MAX_EDGE] px on the longer side: plenty for reading
 * slide text, at a fraction of a camera photo's size.
 */
object Photos {
    private const val TAG = "Photos"
    const val MAX_EDGE = 2560
    private const val QUALITY = 85

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A file for the camera app to save a new photo in, and the address to give it. */
    fun newCapture(context: Context): Pair<File, Uri> {
        val file = File(File(context.cacheDir, "capture").apply { mkdirs() }, "capture-${newId()}.jpg")
        return file to FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /**
     * Adds the photo the camera saved in [file] as a note linked to [recId] at [offsetMs] (both null
     * for an unlinked one), then deletes [file]. [onDone] gets false if it couldn't be read.
     */
    fun addTaken(context: Context, sessionId: String, file: File, recId: String?, offsetMs: Long?, onDone: (Boolean) -> Unit = {}) {
        val resolver = context.applicationContext.contentResolver
        scope.launch {
            val ok = add(resolver, sessionId, Uri.fromFile(file), System.currentTimeMillis(), recId, offsetMs) != null
            file.delete()
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }

    /** Adds the photo in [file] as [addTaken] does, but here and now; the new note's id, or null if it couldn't be read. */
    fun addNow(context: Context, sessionId: String, file: File, recId: String?, offsetMs: Long?): String? = try {
        add(context.applicationContext.contentResolver, sessionId, Uri.fromFile(file), System.currentTimeMillis(), recId, offsetMs)
    } finally {
        file.delete()
    }

    /**
     * Puts the picture in [file] in place of photo note [noteId]'s: a better picture of the same slide,
     * keeping its place. Its text is read again. False if the note's gone or [file] isn't a picture.
     */
    fun replaceNow(context: Context, sessionId: String, noteId: String, file: File): Boolean {
        // A new name, so nothing shows the old picture from a cache.
        val name = "photo-${newId()}.jpg"
        val target = SessionRepository.photoFile(sessionId, name)
        val saved = try {
            runCatching { save(context.applicationContext.contentResolver, Uri.fromFile(file), target) }
                .onFailure { Log.e(TAG, "Couldn't save the better picture for $noteId", it) }
                .getOrDefault(false)
        } finally {
            file.delete()
        }
        if (!saved) return false
        val old = SessionRepository.replacePhoto(sessionId, noteId, name)
        if (old == null) {
            target.delete()
            return false
        }
        Log.i(TAG, "Photo $noteId retaken as $name")
        SessionRepository.photoFile(sessionId, old).delete()
        SlideText.photoAdded(sessionId, noteId)
        return true
    }

    /**
     * Adds photos picked from the gallery. Each one taken during one of the session's recordings is
     * linked to that moment, so photos taken with the camera app during a talk land where they belong.
     * [onDone] gets how many couldn't be read.
     */
    fun addPicked(context: Context, sessionId: String, uris: List<Uri>, onDone: (failed: Int) -> Unit = {}) {
        val resolver = context.applicationContext.contentResolver
        scope.launch {
            var failed = 0
            for (uri in uris) {
                val takenAt = takenAt(resolver, uri)
                val link = SessionRepository.get(sessionId)?.let { s -> takenAt?.let { linkFor(s, it) } }
                if (add(resolver, sessionId, uri, takenAt ?: System.currentTimeMillis(), link?.first, link?.second) == null) failed++
            }
            withContext(Dispatchers.Main) { onDone(failed) }
        }
    }

    /** Where in [session]'s recordings a photo taken at [takenAt] belongs, if it was taken during one. */
    fun linkFor(session: Session, takenAt: Long, now: Long = System.currentTimeMillis()): Pair<String, Long>? {
        for (rec in session.recordings) {
            // A recording still going has no duration yet. (Time spent paused isn't subtracted.)
            val end = rec.createdAt + if (rec.durationMs > 0) rec.durationMs else now - rec.createdAt
            if (takenAt in rec.createdAt..end) return rec.id to (takenAt - rec.createdAt)
        }
        return null
    }

    /** Saves the picture at [source] as a new photo note; its id, or null if it isn't a picture. */
    private fun add(resolver: ContentResolver, sessionId: String, source: Uri, createdAt: Long, recId: String?, offsetMs: Long?): String? {
        val id = newId()
        val name = "photo-$id.jpg"
        val result = runCatching { save(resolver, source, SessionRepository.photoFile(sessionId, name)) }
        result.exceptionOrNull()?.let { Log.e(TAG, "Couldn't save the photo from $source", it) }
        val saved = result.getOrDefault(false)
        Log.i(TAG, "Photo from $source: ${if (saved) "saved as $name at $offsetMs ms of $recId" else "not saved"}")
        if (!saved) return null
        SessionRepository.addNote(sessionId, Note(id, "", createdAt, recId, offsetMs, photo = name))
        SlideText.photoAdded(sessionId, id)
        return id
    }

    /** Writes the picture at [source] to [target], upright and at most [MAX_EDGE] px. False if it isn't a picture. */
    fun save(resolver: ContentResolver, source: Uri, target: File): Boolean {
        val orientation = resolver.openInputStream(source)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        // Decode at the smallest power-of-two reduction that's still at least MAX_EDGE, then scale exactly.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
            }
        }
        val upright = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            part.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        } finally {
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
        return part.renameTo(target)
    }

    /** When the photo was taken: from its EXIF data, else from the media library; null if unknown. */
    private fun takenAt(resolver: ContentResolver, uri: Uri): Long? {
        val exif = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        exif?.let { e -> exifTime(e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), e.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)) }
            ?.let { return it }
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
            }
        }.getOrNull()
    }

    /** EXIF's "2026:10:01 14:23:05", in the given UTC offset (e.g. "-07:00") or else the phone's time zone. */
    fun exifTime(dateTime: String?, offset: String?): Long? {
        if (dateTime.isNullOrBlank()) return null
        return try {
            if (offset != null && Regex("[+-]\\d{2}:\\d{2}").matches(offset)) {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.US).parse(dateTime.trim() + offset)?.time
            } else {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dateTime.trim())?.time
            }
        } catch (e: ParseException) {
            null
        }
    }
}
