package com.kjwindham.audiocool

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.slides.SlideWatcher
import com.kjwindham.audiocool.slides.SlideWatcher.Decision.Better
import com.kjwindham.audiocool.slides.SlideWatcher.Decision.New
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer

/** Hands-free slides: when the watcher saves a slide, from what a phone propped up in a lecture hall sees ([SlideCamera]). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlideWatcherTest {
    private val sleep = SceneSlide("Why we sleep", listOf("Memory is replayed", "Toxins are cleared", "Hormones reset"))
    private val stages = SceneSlide("Stages of sleep", listOf("N1 and N2: light", "N3: deep", "REM: dreams"))
    private val dreams = SceneSlide("Why we dream", listOf("Memory is sorted", "Emotions settle", "Nobody knows"))
    private val caffeine = SceneSlide("Caffeine", listOf("Half-life 5-6 h", "Blocks adenosine"), dark = true)

    /** The watcher, given the camera's view four times a second as the capture screen does. */
    private class Lecture(noise: Float = 1f) {
        val watcher = SlideWatcher()
        val camera = SlideCamera(noise = noise)
        var now = 0L
        val decisions = ArrayList<Pair<Long, SlideWatcher.Decision>>()
        val made get() = decisions.map { it.second }

        /** Shows [slide] for [seconds]; [person] is where someone in front is, by ms since this began. */
        fun show(seconds: Double, slide: SceneSlide?, person: ((Long) -> Int?)? = null, zoom: Float = 1f, gain: ((Long) -> Float)? = null, flicker: Float = 0f) {
            val start = now
            val end = now + (seconds * 1000).toLong()
            while (now < end) {
                val grid = camera.look(slide, person?.invoke(now - start), zoom, gain?.invoke(now) ?: 1f, flicker)
                watcher.frame(grid, now)?.let { decisions += now to it }
                now += 250
            }
        }
    }

    @Test
    fun aSlideThatHoldsStillIsSavedOnce() {
        val lecture = Lecture()
        lecture.show(20.0, sleep)
        assertEquals(listOf(New(0, 0)), lecture.made)
        // The first waits 3 s: time to prop the phone up.
        assertEquals(3_000L, lecture.decisions.single().first)
    }

    @Test
    fun eachNewSlideIsSavedFromWhenItWentUpAndGoingBackToOneIsnt() {
        val lecture = Lecture()
        lecture.show(5.0, sleep)
        lecture.show(4.0, stages)
        lecture.show(4.0, sleep)
        // Same layout, a word different in the title and a bullet: still a new slide.
        lecture.show(4.0, dreams)
        assertEquals(listOf(New(0, 0), New(1, 5_000), New(2, 13_000)), lecture.made)
        // Saved once it's held still 1.5 s.
        assertEquals(6_750L, lecture.decisions[1].first)
    }

    @Test
    fun flippingPastSlidesSavesOnlyTheOneThatStays() {
        val lecture = Lecture()
        lecture.show(5.0, sleep)
        lecture.show(0.75, stages)
        lecture.show(0.5, dreams)
        lecture.show(4.0, caffeine)
        assertEquals(listOf(New(0, 0), New(1, 6_250)), lecture.made)
    }

    @Test
    fun theCamerasNoiseExposureAndFlickeringLightsArentSlides() {
        val lecture = Lecture()
        lecture.show(40.0, sleep, gain = { t -> 1f - 0.3f * t / 40_000f }, flicker = 10f)
        lecture.show(4.0, stages, gain = { 0.7f }, flicker = 10f)
        assertEquals(listOf(New(0, 0), New(1, 40_000)), lecture.made)
    }

    @Test
    fun inADimRoomsNoisierPictureToo() {
        val lecture = Lecture(noise = 3f)
        val built = sleep.copy(shown = 1)
        lecture.show(30.0, built)
        lecture.show(3.0, built.copy(shown = 2))
        lecture.show(4.0, stages)
        lecture.show(4.0, caffeine)
        lecture.show(4.0, built)
        assertEquals(listOf(New(0, 0), Better(0), New(1, 33_000), New(2, 37_000)), lecture.made)
    }

    @Test
    fun bulletsAppearingRetakeTheSlideInsteadOfAddingOnes() {
        val lecture = Lecture()
        val built = sleep.copy(shown = 1)
        lecture.show(5.0, built)
        lecture.show(3.0, built.copy(shown = 2))
        lecture.show(3.0, built.copy(shown = 3))
        lecture.show(4.0, stages)
        // Going back to it starts its bullet points over: it's the slide saved already.
        lecture.show(4.0, built)
        assertEquals(listOf(New(0, 0), Better(0), Better(0), New(1, 11_000)), lecture.made)
    }

    @Test
    fun onADarkSlideToo() {
        val lecture = Lecture()
        val built = caffeine.copy(shown = 1)
        lecture.show(5.0, built)
        lecture.show(3.0, built.copy(shown = 2))
        lecture.show(4.0, sleep)
        assertEquals(listOf(New(0, 0), Better(0), New(1, 8_000)), lecture.made)
    }

    @Test
    fun someoneStandingInFrontNeitherAddsNorRetakesASlide() {
        val lecture = Lecture()
        lecture.show(5.0, sleep)
        // Walks in from the left over 3 s, stands in front for 8 s, then walks off to the right.
        lecture.show(3.0, sleep, person = { t -> -100 + (t * 300 / 3_000).toInt() })
        lecture.show(8.0, sleep, person = { 200 })
        lecture.show(3.0, sleep, person = { t -> 200 + (t * 600 / 3_000).toInt() })
        lecture.show(5.0, sleep)
        assertEquals(listOf(New(0, 0)), lecture.made)
    }

    @Test
    fun zoomingInRetakesTheSlide() {
        val lecture = Lecture()
        lecture.show(5.0, sleep)
        lecture.watcher.reframed(lecture.now)
        lecture.show(4.0, sleep, zoom = 1.25f)
        assertEquals(listOf(New(0, 0), Better(0)), lecture.made)
    }

    @Test
    fun aCoveredLensIsNothingAndThenTheSlideIsSaved() {
        assertNull(SlideWatcher.grid(ByteBuffer.wrap(ByteArray(640 * 480) { 12 }), 640, 480, 640))
        val lecture = Lecture()
        repeat(40) {
            assertNull(lecture.watcher.frame(null, lecture.now))
            lecture.now += 250
        }
        lecture.show(5.0, sleep)
        assertEquals(listOf(New(0, 10_000)), lecture.made)
    }

    @Test
    fun afterAPauseWhatsUpIsLookedAtAfresh() {
        val lecture = Lecture()
        lecture.show(5.0, sleep)
        lecture.watcher.resume()
        lecture.now += 60_000
        lecture.show(4.0, sleep)
        lecture.watcher.resume()
        lecture.now += 60_000
        lecture.show(4.0, stages)
        assertEquals(listOf(New(0, 0), New(1, 129_000)), lecture.made)
    }

    @Test
    fun aSlideWhosePhotoCouldntBeTakenIsSeenAsNewAgain() {
        val lecture = Lecture()
        lecture.show(5.0, sleep)
        lecture.watcher.forget(0)
        lecture.show(1.0, stages)
        lecture.show(4.0, sleep)
        assertEquals(listOf(New(0, 0), New(1, 6_000)), lecture.made)
        assertTrue(lecture.watcher.count == 1)
    }
}
