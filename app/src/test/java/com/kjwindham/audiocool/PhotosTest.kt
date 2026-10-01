package com.kjwindham.audiocool

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Photos
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.SessionRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotosTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val resolver get() = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver

    /** A [width] x [height] JPEG, red on the left half and blue on the right. */
    private fun jpeg(width: Int, height: Int, orientation: Int = ExifInterface.ORIENTATION_NORMAL, takenAt: String? = null): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.RED })
        }
        val file = tmp.newFile()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            takenAt?.let { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
            saveAttributes()
        }
        return file
    }

    @Test
    fun photosAreSavedUprightAndSmaller() {
        // A camera photo held in portrait: stored sideways, with EXIF saying to turn it a quarter clockwise.
        val original = jpeg(4000, 3000, ExifInterface.ORIENTATION_ROTATE_90)
        val saved = File(tmp.root, "photo.jpg")
        assertTrue(Photos.save(resolver, Uri.fromFile(original), saved))

        val image = BitmapFactory.decodeFile(saved.path)
        assertEquals(1920, image.width)
        assertEquals(2560, image.height)
        // Turned clockwise, the left (red) half is now on top.
        assertEquals(Color.RED, nearest(image.getPixel(image.width / 2, image.height / 4)))
        assertEquals(Color.BLUE, nearest(image.getPixel(image.width / 2, image.height * 3 / 4)))
        assertTrue(saved.length() < original.length())
    }

    @Test
    fun aFileThatIsntAPictureIsRefused() {
        val text = tmp.newFile().apply { writeText("not a picture") }
        assertTrue(!Photos.save(resolver, Uri.fromFile(text), File(tmp.root, "photo.jpg")))
    }

    @Test
    fun photosFromTheGalleryLandWhereTheyWereTakenDuringARecording() {
        val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val start = format.parse("2026:10:01 14:00:00")!!.time
        val session = SessionRepository.create("Keynote")
        SessionRepository.addRecording(session.id, Recording("r1", "recording-1.m4a", start, durationMs = 3_600_000))

        assertEquals("r1" to 600_000L, Photos.linkFor(SessionRepository.get(session.id)!!, start + 600_000))
        assertNull(Photos.linkFor(SessionRepository.get(session.id)!!, start - 1))
        assertEquals(start + 30_000, Photos.exifTime("2026:10:01 14:00:30", null))
        assertEquals(SimpleDateFormat("yyyy-MM-dd HH:mm:ssXXX", Locale.US).parse("2026-10-01 14:00:30-07:00")!!.time, Photos.exifTime("2026:10:01 14:00:30", "-07:00"))

        val during = jpeg(800, 600, takenAt = "2026:10:01 14:12:00")
        val before = jpeg(800, 600, takenAt = "2026:09:30 09:00:00")
        var failed = -1
        Photos.addPicked(ApplicationProvider.getApplicationContext(), session.id, listOf(Uri.fromFile(during), Uri.fromFile(before))) { failed = it }
        val deadline = System.currentTimeMillis() + 10_000
        while (failed < 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(0, failed)
        val photos = SessionRepository.get(session.id)!!.notes.filter { it.photo != null }.sortedBy { it.createdAt }
        assertEquals(2, photos.size)
        // Taken before the talk: added, but not linked to the recording.
        assertNull(photos[0].recId)
        assertEquals("r1", photos[1].recId)
        assertEquals(12 * 60_000L, photos[1].offsetMs)
        assertTrue(photos.all { SessionRepository.photoFile(session.id, it.photo!!).length() > 0 })
    }

    private fun nearest(pixel: Int): Int = if (Color.red(pixel) > Color.blue(pixel)) Color.RED else Color.BLUE
}
