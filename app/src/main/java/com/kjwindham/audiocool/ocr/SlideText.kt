package com.kjwindham.audiocool.ocr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.util.defaultSessionTitle
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * Reads the text in each photo, on the phone (ML Kit). The text is kept with the photo, so search
 * finds slides by what's on them, and a session still named after its start time is renamed after
 * the title on its first photo that has one. A name you gave a session is never changed.
 */
object SlideText {
    private const val TAG = "SlideText"

    /** Stands in for ML Kit in tests. */
    @VisibleForTesting
    var readerForTest: ((File) -> List<TextLine>)? = null

    // The application context, which lives as long as the process, so holding it isn't a leak.
    @SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "slide-text").apply { priority = Thread.MIN_PRIORITY } }
    private val recognizer: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun init(context: Context) {
        app = context.applicationContext
        // Photos not read yet: added before this version, or the app closed before it got to them.
        worker.execute {
            for (session in SessionRepository.sessions.value) {
                for (note in session.notes) if (note.photo != null && note.photoText == null) read(session.id, note.id)
            }
        }
    }

    /** Reads a photo just added to a session. */
    fun photoAdded(sessionId: String, noteId: String) = worker.execute { read(sessionId, noteId) }

    /** Runs after everything handed over so far has been read; for tests. */
    @VisibleForTesting
    fun afterQueued(block: () -> Unit) = worker.execute(block)

    private fun read(sessionId: String, noteId: String) {
        val note = SessionRepository.get(sessionId)?.notes?.firstOrNull { it.id == noteId } ?: return
        val file = SessionRepository.photoFile(sessionId, note.photo ?: return)
        val lines = try {
            (readerForTest ?: ::recognize)(file)
        } catch (e: Throwable) {
            // Left unread, so it's tried again next time the app starts. (Throwable: a reader that
            // can't load on this phone shouldn't take the app down with it.)
            Log.w(TAG, "Couldn't read the text in ${file.name}", e)
            return
        }
        SessionRepository.setPhotoText(sessionId, noteId, lines.joinToString("\n") { it.text })
        val session = SessionRepository.get(sessionId) ?: return
        if (session.title == defaultSessionTitle(session.createdAt)) {
            SlideTitle.pick(lines)?.let { SessionRepository.rename(sessionId, it) }
        }
    }

    private fun recognize(file: File): List<TextLine> {
        val text = Tasks.await(recognizer.process(InputImage.fromFilePath(app, Uri.fromFile(file))), 30, TimeUnit.SECONDS)
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            TextLine(line.text, box.left, box.top, box.right, box.bottom, letterHeight(line.cornerPoints) ?: box.height(), line.confidence)
        }
    }

    /** The height of tilted text, from its corners (top-left, top-right, bottom-right, bottom-left). */
    private fun letterHeight(corners: Array<Point>?): Int? {
        if (corners == null || corners.size != 4) return null
        fun distance(a: Point, b: Point) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        return ((distance(corners[0], corners[3]) + distance(corners[1], corners[2])) / 2).toInt()
    }
}
