package com.kjwindham.audiocool

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.kjwindham.audiocool.slides.SlideWatcher
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** A slide: a title and bullet points, [shown] of them so far. */
data class SceneSlide(val title: String, val bullets: List<String>, val shown: Int = bullets.size, val dark: Boolean = false)

/**
 * What a phone propped up in a lecture hall sees, as the camera's brightness plane: a dim room, the
 * projector screen a little tilted in it, a slide on it (projected, so its black is grey), someone
 * standing in front at [person] (their middle, in px), slightly soft focus, sensor noise ([noise]
 * times a well-lit room's), the exposure drifting by [gain] and banding flicker of [flicker] levels.
 */
class SlideCamera(private val seed: Int = 7, private val noise: Float = 1f) {
    private val random = Random(seed)
    private val clean = HashMap<Triple<SceneSlide?, Int?, Float>, FloatArray>()
    private var frames = 0

    fun look(slide: SceneSlide?, person: Int? = null, zoom: Float = 1f, gain: Float = 1f, flicker: Float = 0f): SlideWatcher.Grid? {
        val picture = clean.getOrPut(Triple(slide, person, zoom)) { blur(blur(render(slide, person, zoom))) }
        val phase = frames++ * 0.37
        val bytes = ByteArray(W * H)
        for (y in 0 until H) {
            val band = if (flicker > 0f) flicker * sin(2 * PI * (y / 37.0 + phase)).toFloat() else 0f
            for (x in 0 until W) {
                val v = picture[y * W + x] * gain + band + noise * (random.nextFloat() * 6f - 3f + random.nextFloat() * 6f - 3f)
                bytes[y * W + x] = v.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return SlideWatcher.grid(ByteBuffer.wrap(bytes), W, H, W)
    }

    /** Needs Robolectric's native graphics. */
    private fun render(slide: SceneSlide?, person: Int?, zoom: Float): FloatArray {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(gray(35))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        canvas.concat(
            Matrix().apply {
                // The slide's 1280 × 720 on a screen about 540 × 370 px, a little tilted, zoomed about the middle.
                setScale(540f / 1280, 370f / 720)
                postSkew(0.02f, -0.015f)
                postTranslate(50f, 55f)
                postScale(zoom, zoom, W / 2f, H / 2f)
            },
        )
        paint.color = when {
            slide == null -> gray(200)
            slide.dark -> gray(72)
            else -> gray(226)
        }
        canvas.drawRect(0f, 0f, 1280f, 720f, paint)
        if (slide != null) {
            paint.color = if (slide.dark) gray(230) else gray(95)
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = 68f
            canvas.drawText(slide.title, 80f, 130f, paint)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 44f
            slide.bullets.take(slide.shown).forEachIndexed { i, b -> canvas.drawText("\u2022  $b", 100f, 250f + i * 90, paint) }
        }
        canvas.restore()
        if (person != null) {
            paint.color = gray(68)
            canvas.drawOval(RectF(person - 40f, 230f, person + 40f, 325f), paint)
            canvas.drawRoundRect(RectF(person - 85f, 315f, person + 85f, 535f), 30f, 30f, paint)
        }
        val pixels = IntArray(W * H)
        bitmap.getPixels(pixels, 0, W, 0, 0, W, H)
        bitmap.recycle()
        return FloatArray(W * H) { (pixels[it] and 0xFF).toFloat() }
    }

    private fun gray(v: Int) = Color.rgb(v, v, v)

    private fun blur(p: FloatArray): FloatArray = FloatArray(p.size) { i ->
        val x = i % W
        val y = i / W
        var sum = 0f
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until W && ny in 0 until H) {
                sum += p[ny * W + nx]
                n++
            }
        }
        sum / n
    }

    companion object {
        const val W = 640
        const val H = 480
    }
}
