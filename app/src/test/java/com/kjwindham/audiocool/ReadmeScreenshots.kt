package com.kjwindham.audiocool

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Looper
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.audio.Waveform
import com.kjwindham.audiocool.data.ChapterSummary
import com.kjwindham.audiocool.data.FolderRepository
import com.kjwindham.audiocool.data.MARK_TEXT
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.SessionSummary
import com.kjwindham.audiocool.data.TimelineMode
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.timelineRows
import com.kjwindham.audiocool.summarize.SummaryController
import com.kjwindham.audiocool.summarize.chapters
import com.kjwindham.audiocool.transcribe.LiveTranscription
import com.kjwindham.audiocool.transcribe.SpeechModel
import com.kjwindham.audiocool.transcribe.TranscriptionController
import com.kjwindham.audiocool.util.Prefs
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Duration
import java.util.Calendar
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The screenshots in the README, made from a demo library: a lecture on sleep with its slides, notes,
 * transcript and summaries, and a few other sessions around it. Skipped unless asked for:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*ReadmeScreenshots*' -PreadmeScreenshots=docs/screenshots
 *
 * Drawn at twice the density of a phone's dp, with a status bar and rounded corners added, so they
 * look like a phone's screen on the page.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w384dp-h854dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadmeScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val out: File? = System.getProperty("readme.screenshots")?.let(::File)

    @Before
    fun setUp() {
        assumeTrue("Pass -PreadmeScreenshots=<dir> to make the README's screenshots", out != null)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA)
        Prefs(app).timelineMode = "EVERYTHING"
        Prefs(app).summaryOfferDismissed = true
        // As on a phone that's set up: the speech model downloaded (empty files of the right size will do).
        val dir = SpeechModel.dir(app).apply { mkdirs() }
        SpeechModel.files.forEach { RandomAccessFile(File(dir, it.name), "rw").use { f -> f.setLength(it.size) } }
        TranscriptionController.init(app)
    }

    @After
    fun tearDown() {
        LiveTranscription.pretendForTest(null, null)
        RecorderController.stop()
        PlayerController.release()
    }

    @Test
    fun sessionsAndGallery() {
        library()
        settle()
        screenshot("sessions")
        compose.onNodeWithContentDescription("Show as a gallery").performClick()
        screenshot("gallery")
    }

    @Test
    fun timelineWhilePlaying() {
        val talk = sleepTalk()
        open(talk)
        play(talk, "21:05")
        scrollTo(talk, "note:s4", TimelineMode.EVERYTHING)
        screenshot("timeline")
    }

    @Test
    fun summary() {
        open(sleepTalk())
        compose.onNodeWithText("Key points and 3 action items").performClick()
        screenshot("summary")
    }

    @Test
    fun notesOnly() {
        val talk = sleepTalk()
        open(talk)
        compose.onNodeWithText("Notes only").performClick()
        scrollTo(talk, "note:s3", TimelineMode.NOTES)
        screenshot("notes-only")
    }

    @Test
    fun previewFromTheScrubber() {
        open(sleepTalk())
        compose.onNodeWithContentDescription("Marked moment at 23:20", substring = true).performClick()
        screenshot("preview")
    }

    @Test
    fun searchEverything() {
        library()
        settle()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("caffeine")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("results", substring = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("search")
    }

    @Test
    fun recordingWithLiveTranscript() {
        val id = "demo-live"
        val at = on(Calendar.OCTOBER, 2, 9, 30)
        SessionRepository.importSession(Session(id, "The Science of Sleep", at, at))
        // Not the real speech engine: what it would have written so far is added below.
        Prefs(app).autoTranscribe = false
        RecorderController.start(id)
        Prefs(app).autoTranscribe = true
        val recId = RecorderController.state.value.recId!!
        val live = Demo(id, recId, at)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5 * 60 + 52))
        LiveTranscription.pretendForTest(id, recId, speaking = true)
        sleepTalkSaid.filter { it.endMs < t("05:40") }.forEach { SessionRepository.appendTranscriptSegment(id, recId, it, SpeechModel.ID) }
        sleepSlides(live, upTo = t("05:40"))
        sleepNotes(live, upTo = t("05:40"))
        SessionRepository.addNote(id, Note("live1", "90-minute cycles, 4 to 6 a night", at + t("05:05"), recId, t("05:05")))
        compose.onNodeWithText("The Science of Sleep").performClick()
        settle()
        scrollTo(live, "note:s2", TimelineMode.EVERYTHING)
        compose.onNode(hasSetTextAction()).performTextInput("Ask: do naps reset adenosine?")
        screenshot("recording")
    }

    @Test
    @Config(qualifiers = "+night")
    fun darkMode() {
        val talk = sleepTalk()
        open(talk)
        play(talk, "13:56")
        screenshot("dark")
    }

    @Test
    fun appIcon() {
        val icon = ContextCompat.getDrawable(app, R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val size = 192
        // Each layer is 108 dp, of which the middle 72 show.
        val pad = (size * 18 / 72f).roundToInt()
        val layers = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(layers).apply { listOf(icon.background, icon.foreground).forEach { it.setBounds(-pad, -pad, size + pad, size + pad); it.draw(this) } }
        val rounded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(rounded).drawRoundRect(
            RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.23f, size * 0.23f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(layers, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) },
        )
        FileOutputStream(File(out!!.apply { mkdirs() }, "icon.png")).use { rounded.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- The demo library ----

    private class Demo(val id: String, val recId: String, val at: Long)

    /** The lecture on sleep, and the other sessions in the list. */
    private fun library() {
        // The list puts what changed last first, so oldest first, a moment apart.
        listOf(
            { plain("demo-standup", "Team Standup", on(Calendar.SEPTEMBER, 26, 9, 15), 712_000, 3) },
            { plain("demo-book", "Book Club: Project Hail Mary", on(Calendar.SEPTEMBER, 27, 19, 30), 2_850_000, 4) },
            ::machineLearning,
            ::designReview,
            ::chemistry,
            { sleepTalk() },
        ).forEach {
            it()
            Thread.sleep(5)
        }
        // Filed in folders, as a semester's sessions would be.
        mapOf("demo-sleep" to "Neuroscience 214", "demo-chem" to "Organic Chem", "demo-design" to "Work", "demo-standup" to "Work", "demo-ml" to "Machine Learning")
            .forEach { (id, folder) -> SessionRepository.moveToFolder(id, FolderRepository.create(folder)) }
    }

    private val sleepTalkSaid = listOf(
        said("00:02", "00:20", "Good morning, everyone. Let's get started: today is all about sleep."),
        said("00:21", "00:38", "Most of you got less of it than you needed last night, so this should feel relevant."),
        said("00:44", "01:20", "We spend about a third of our lives asleep, and for a long time scientists assumed the brain simply switched off at night."),
        said("01:23", "02:00", "It turns out the sleeping brain is incredibly busy. It sorts memories, clears out waste, and resets the body for the next day."),
        said("02:04", "02:38", "So why do we get sleepy at all? A big part of the answer is a molecule called adenosine."),
        said("02:41", "03:28", "Adenosine builds up in your brain the whole time you're awake. The longer you're up, the more there is, and the stronger the pressure to sleep."),
        said("03:31", "04:04", "Caffeine works by blocking adenosine receptors. It doesn't remove the pressure; it just hides it for a while."),
        said("04:10", "04:44", "Once you're asleep, you don't just sink into one state. Sleep comes in cycles of about ninety minutes."),
        said("04:47", "05:30", "Each cycle moves from light sleep into deep, slow-wave sleep, and then up into REM, where most vivid dreaming happens."),
        said("05:33", "06:18", "Early in the night the cycles are heavy on deep sleep. Toward morning REM takes over, which is why you often wake up from a dream."),
        said("06:22", "06:58", "It's also why an alarm in the middle of deep sleep feels so brutal."),
        said("07:02", "07:48", "If you've ever slept a full eight hours and still felt groggy, you probably woke up at the wrong point in a cycle."),
        said("07:52", "08:36", "Some sleep trackers try to time your alarm to a lighter stage. The evidence is mixed, but the idea is sound."),
        said("08:40", "09:30", "Across a night you get four to six of these cycles, and each one matters."),
        said("09:33", "10:20", "Cut your sleep short by ninety minutes and you mostly lose REM, because it's packed into the last cycles of the night."),
        said("10:24", "11:10", "That's a bigger deal than it sounds, as we'll see in a moment."),
        said("12:40", "13:02", "Let's talk about memory, because this is where sleep gets really interesting."),
        said("13:05", "13:52", "During deep sleep, the hippocampus replays what you learned during the day and hands it to the cortex for long-term storage."),
        said("13:56", "14:38", "In rats you can actually watch it happen: the neurons that fired while running a maze fire again, in the same order, while they sleep."),
        said("14:42", "15:26", "REM seems to do something different. It links new memories with old ones, which may be why sleep helps with creative problem-solving."),
        said("15:30", "16:16", "Students who sleep after studying remember far more the next day than students who stay up, even with the same amount of study time."),
        said("16:20", "16:52", "So pulling an all-nighter before an exam is close to the worst possible strategy."),
        said("21:05", "21:42", "The second system that controls sleep is your circadian clock."),
        said("21:46", "22:28", "Deep in the brain, a tiny cluster of cells keeps a roughly twenty-four-hour rhythm, and morning light keeps it in sync."),
        said("22:32", "23:08", "Bright light in the evening, especially from screens, pushes that clock later, so you feel awake when you should be winding down."),
        said("23:12", "23:52", "Teenagers' clocks naturally run later, which is why early school start times are such a struggle."),
        said("27:12", "27:48", "A quick word on caffeine: its half-life is about five to six hours."),
        said("27:51", "28:28", "That means half of your afternoon coffee is still in your system at bedtime."),
        said("33:31", "34:08", "Let's finish with what you can actually do about all this."),
        said("34:11", "34:48", "The most effective habit is waking up at the same time every day, even on weekends."),
        said("34:52", "35:30", "Get daylight in your eyes in the morning, keep caffeine before noon, and keep your bedroom cool and dark."),
        said("35:33", "36:08", "And give yourself half an hour to wind down without screens before bed."),
        said("41:30", "42:08", "That's all for today. Next week: sleep disorders. Thanks, everyone."),
    )

    private fun sleepTalk(): Demo {
        val talk = session("demo-sleep", "The Science of Sleep", on(Calendar.OCTOBER, 2, 9, 30), 2_538_000, sleepTalkSaid)
        sleepSlides(talk)
        sleepNotes(talk)
        val session = SessionRepository.get(talk.id)!!
        val parts = chapters(session) { null }.filterNot { it.slight }
        val summaries = listOf(
            "Sleep isn't the brain switching off: it sorts memories, clears out waste and resets the body. Sleepiness comes largely from adenosine, which builds up while you're awake; caffeine only blocks it.",
            "Sleep runs in cycles of about 90 minutes, from light to deep sleep and up into REM. Deep sleep fills the early night and REM the morning, so cutting sleep short mostly costs REM.",
            "Deep sleep replays the day's learning and moves it from the hippocampus to the cortex; REM links new memories with old ones. Sleeping after studying beats staying up, so all-nighters backfire.",
            "A clock in the brain keeps a roughly 24-hour rhythm, set by morning light and pushed later by evening screens. Caffeine's five-to-six-hour half-life keeps afternoon coffee active at bedtime.",
            "Five habits help most: the same wake time every day, morning daylight, caffeine before noon, a cool and dark bedroom, and half an hour without screens before bed.",
        )
        val keys = parts.map { it.key }.toSet()
        parts.zip(summaries).forEach { (part, text) ->
            SessionRepository.setChapterSummary(talk.id, ChapterSummary(part.key, text, "demo", SummaryController.MODEL), keys)
        }
        SessionRepository.setSessionSummary(
            talk.id,
            SessionSummary(
                text = "Why we sleep and how to sleep better: adenosine builds sleep pressure while you're awake, sleep runs in 90-minute " +
                    "cycles whose deep and REM stages lock in memories, and a light-driven body clock sets the timing.",
                keyPoints = listOf(
                    "Adenosine builds up while you're awake; caffeine only masks it.",
                    "Sleep runs in ~90-minute cycles: deep sleep early, REM toward morning.",
                    "Deep sleep replays the day's learning; REM links it to what you already know.",
                    "Morning light keeps the body clock on time; evening screens push it later.",
                ),
                actionItems = listOf(
                    "Look up the maze replay study.",
                    "Keep the same wake time every day, even on weekends.",
                    "No caffeine after noon.",
                ),
                title = "The Science of Sleep",
                basis = "demo",
                model = SummaryController.MODEL,
                createdAt = talk.at,
            ),
        )
        return talk
    }

    private fun sleepSlides(talk: Demo, upTo: Long = Long.MAX_VALUE) {
        val slides = listOf(
            Triple("s1", "00:40", arrayOf("The Science of Sleep", "What happens after lights out", "Neuroscience 214 · Week 3")),
            Triple("s2", "04:08", arrayOf("Sleep comes in cycles", "About 90 minutes each", "4 to 6 cycles a night")),
            Triple("s3", "12:38", arrayOf("REM sleep and memory", "Deep sleep replays the day", "REM links new to old")),
            Triple("s4", "21:02", arrayOf("Your circadian clock", "Morning light resets it", "Evening screens delay it")),
            Triple("s5", "33:28", arrayOf("Five habits for better sleep", "1   Same wake time, every day", "2   Daylight in the morning", "3   Caffeine before noon", "4   A cool, dark bedroom", "5   30 minutes screen-free")),
        )
        val art = mapOf("s1" to Art.MOON, "s2" to Art.HYPNOGRAM, "s3" to Art.MEMORY, "s4" to Art.CLOCK, "s5" to Art.NONE)
        for ((id, at, lines) in slides) {
            if (t(at) < upTo) slide(talk, id, at, Look.NIGHT, art.getValue(id), *lines, centered = id == "s1")
        }
    }

    private fun sleepNotes(talk: Demo, upTo: Long = Long.MAX_VALUE) {
        listOf(
            Note("n1", "Adenosine builds up while awake → sleep pressure", 0, talk.recId, t("02:55")),
            Note("n2", "Ask: do naps reset adenosine?", 0, talk.recId, t("06:30")),
            Note("m1", MARK_TEXT, 0, talk.recId, t("09:50")),
            Note("n3", "Look up the maze replay study", 0, talk.recId, t("14:20"), spoken = true),
            Note("n4", "All-nighters = worst strategy for exams!", 0, talk.recId, t("16:45")),
            Note("m2", MARK_TEXT, 0, talk.recId, t("23:20")),
            Note("n5", "Caffeine half-life is 5–6 h → none after noon", 0, talk.recId, t("28:00")),
            Note("n6", "Try: same wake time every day, even weekends", 0, talk.recId, t("35:00")),
        ).filter { it.offsetMs!! < upTo }.forEach { SessionRepository.addNote(talk.id, it.copy(createdAt = talk.at + it.offsetMs!!)) }
    }

    private fun chemistry() {
        val lecture = session(
            "demo-chem", "Organic Chemistry: Lecture 7", on(Calendar.OCTOBER, 1, 14, 0), 4_445_000,
            listOf(
                said("00:05", "00:40", "Okay, today we're on nucleophilic substitution, SN1 and SN2."),
                said("12:10", "12:55", "The leaving group matters as much as the nucleophile."),
                said("31:20", "32:10", "Caffeine is a nice example to finish on: a purine ring system with three methyl groups."),
            ),
        )
        slide(lecture, "c1", "00:30", Look.PAPER, Art.RING, "Reaction Mechanisms", "Lecture 7 · Nucleophilic substitution", centered = true)
        slide(lecture, "c2", "08:15", Look.PAPER, Art.NONE, "SN1 vs SN2", "Two steps vs one", "Carbocation vs backside attack")
        slide(lecture, "c3", "19:40", Look.PAPER, Art.NONE, "Good leaving groups", "Weak bases leave easily", "I⁻ > Br⁻ > Cl⁻")
        slide(lecture, "c4", "31:05", Look.PAPER, Art.RING, "Caffeine, up close", "A purine with three methyls")
        notes(lecture, "SN2 = one step, inverts", "Tertiary → SN1", "Practice set due Friday", "Polar aprotic favors SN2", "Ask about E1 vs SN1", "Draw the mechanism for #4")
    }

    private fun designReview() {
        val review = session(
            "demo-design", "Design Review: Onboarding", on(Calendar.SEPTEMBER, 30, 11, 0), 1_720_000,
            listOf(said("00:10", "00:45", "Let's walk through the new sign-up flow, screen by screen.")),
        )
        slide(review, "d1", "00:20", Look.MINT, Art.PHONE, "Onboarding v3", "Three steps, not five", centered = true)
        slide(review, "d2", "14:30", Look.MINT, Art.NONE, "Where people drop off", "Step 3: permissions", "41% leave here")
        notes(review, "Move permissions after the first win", "Skip button on step 2", "Test with 5 new users", "Copy: shorter headline", "Follow up with Priya on analytics")
    }

    private fun machineLearning() {
        val week = session(
            "demo-ml", "Intro to Machine Learning: Week 2", on(Calendar.SEPTEMBER, 29, 10, 0), 3_312_000,
            listOf(said("00:15", "00:50", "Last week was models; this week is how they learn: gradient descent.")),
        )
        slide(week, "l1", "00:40", Look.CHARCOAL, Art.CURVE, "Gradient descent", "Week 2 · Walk downhill on the loss", centered = true)
        slide(week, "l2", "16:00", Look.CHARCOAL, Art.NONE, "Learning rate", "Too big: you overshoot", "Too small: you crawl")
        slide(week, "l3", "35:20", Look.CHARCOAL, Art.NONE, "Overfitting", "Great on training data", "Bad on anything new")
        notes(week, "Loss = how wrong we are", "Learning rate ~ step size", "Plot loss every epoch", "Homework 2: implement SGD", "Read chapter 4", "Ask about momentum", "Validation set ≠ test set", "Office hours Thu 3pm")
    }

    private fun plain(id: String, title: String, at: Long, lengthMs: Long, noteCount: Int) {
        val demo = session(id, title, at, lengthMs, emptyList())
        notes(demo, *Array(noteCount) { "Note ${it + 1}" })
    }

    private fun session(id: String, title: String, at: Long, lengthMs: Long, said: List<TranscriptSegment>): Demo {
        val recId = "$id-rec"
        val dir = SessionRepository.sessionDir(id).apply { mkdirs() }
        val audio = File(dir, "recording-1.m4a").apply { writeBytes(ByteArray(16)) }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(audio.absolutePath), ShadowMediaPlayer.MediaInfo(lengthMs.toInt(), 0))
        File(dir, "levels-$recId.bin").writeBytes(levels(lengthMs, id.hashCode().toLong()))
        val transcript = said.takeIf { it.isNotEmpty() }
        SessionRepository.importSession(
            Session(id, title, at, at + lengthMs, recordings = listOf(Recording(recId, audio.name, at, lengthMs, transcript, SpeechModel.ID.takeIf { transcript != null }))),
        )
        return Demo(id, recId, at)
    }

    private fun notes(demo: Demo, vararg texts: String) {
        val length = SessionRepository.get(demo.id)!!.recordings.single().durationMs
        texts.forEachIndexed { i, text ->
            val at = length * (i + 1) / (texts.size + 1) + 7_000
            SessionRepository.addNote(demo.id, Note("${demo.id}-n$i", text, demo.at + at, demo.recId, at))
        }
    }

    /** Loudness every 100 ms, as the recorder's meter saves it: phrases of speech with short pauses. */
    private fun levels(lengthMs: Long, seed: Long): ByteArray {
        val rnd = Random(seed)
        val out = ByteArray((lengthMs / 100).toInt())
        var i = 0
        while (i < out.size) {
            val phrase = 18 + rnd.nextInt(45)
            val loud = 120 + rnd.nextInt(70)
            for (k in 0 until phrase) {
                if (i + k >= out.size) break
                val syllable = 0.55 + 0.45 * abs(sin((i + k) / 1.7))
                out[i + k] = (loud * syllable + rnd.nextInt(25)).roundToInt().coerceIn(0, 255).toByte()
            }
            i += phrase
            val pause = 3 + rnd.nextInt(9)
            for (k in 0 until pause) if (i + k < out.size) out[i + k] = (14 + rnd.nextInt(14)).toByte()
            i += pause
        }
        return out
    }

    // ---- Slides ----

    private enum class Look(val top: Int, val bottom: Int, val title: Int, val body: Int, val accent: Int) {
        NIGHT(0xFF131A33.toInt(), 0xFF26305C.toInt(), Color.WHITE, 0xFFC9CDE6.toInt(), 0xFFA78BFA.toInt()),
        PAPER(0xFFFFF8F1.toInt(), 0xFFFFE4CC.toInt(), 0xFF1F2430.toInt(), 0xFF4A5060.toInt(), 0xFFF97316.toInt()),
        MINT(0xFFF0FAF6.toInt(), 0xFFDDF3EA.toInt(), 0xFF0F3D33.toInt(), 0xFF35594F.toInt(), 0xFF10B981.toInt()),
        CHARCOAL(0xFF1B1E23.toInt(), 0xFF2B3038.toInt(), Color.WHITE, 0xFFC4C9D2.toInt(), 0xFF38BDF8.toInt()),
    }

    private enum class Art { NONE, MOON, HYPNOGRAM, MEMORY, CLOCK, RING, PHONE, CURVE }

    /** A photo of a slide, taken [at] into the talk, with the text on it read as the app would. */
    private fun slide(demo: Demo, id: String, at: String, look: Look, art: Art, vararg lines: String, centered: Boolean = false) {
        val bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawRect(0f, 0f, 1600f, 900f, Paint().apply { shader = LinearGradient(0f, 0f, 1600f, 900f, look.top, look.bottom, Shader.TileMode.CLAMP) })
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val plain = Typeface.create("sans-serif", Typeface.NORMAL)
        if (centered) {
            // A title slide: the picture above, the title under it, all around the middle.
            draw(c, art, look, RectF(560f, 70f, 1040f, 450f))
            c.drawText(lines[0], 800f, 600f, text(look.title, 88f, bold).apply { textAlign = Paint.Align.CENTER })
            lines.drop(1).forEachIndexed { i, line -> c.drawText(line, 800f, 690f + i * 70f, text(look.body, 46f, plain).apply { textAlign = Paint.Align.CENTER }) }
        } else {
            c.drawRoundRect(RectF(110f, 150f, 230f, 162f), 6f, 6f, fill(look.accent))
            c.drawText(lines[0], 110f, 270f, text(look.title, 84f, bold))
            val bodySize = if (lines.size > 4) 44f else 50f
            lines.drop(1).forEachIndexed { i, line -> c.drawText(line, 110f, 380f + i * (bodySize * 1.55f), text(look.body, bodySize, plain)) }
            draw(c, art, look, RectF(930f, 330f, 1490f, 800f))
        }
        val name = "photo-$id.jpg"
        FileOutputStream(SessionRepository.photoFile(demo.id, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        SessionRepository.addNote(demo.id, Note(id, "", demo.at + t(at), demo.recId, t(at), photo = name))
        SessionRepository.setPhotoText(demo.id, id, lines.joinToString("\n"))
    }

    private fun draw(c: Canvas, art: Art, look: Look, r: RectF) {
        val accent = look.accent
        val line = stroke(accent, 10f)
        val faint = stroke((look.body and 0x00FFFFFF) or 0x55000000, 4f)
        when (art) {
            Art.NONE -> {}
            Art.MOON -> {
                // A crescent moon and stars.
                val cx = r.centerX() + 40
                val cy = r.centerY() - 30
                c.drawCircle(cx, cy, 170f, fill(0xFFF5E6A8.toInt()))
                c.drawCircle(cx + 80, cy - 60, 160f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 1600f, 900f, look.top, look.bottom, Shader.TileMode.CLAMP) })
                listOf(-210f to -150f, -150f to 140f, 210f to 170f, 60f to -230f, -260f to 40f).forEach { (dx, dy) -> c.drawCircle(cx + dx, cy + dy, 7f, fill(Color.WHITE)) }
            }
            Art.HYPNOGRAM -> {
                // Stages through a night: awake, REM, light, deep; REM in the accent colour.
                val labels = listOf("Awake", "REM", "Light", "Deep")
                val rowY = labels.indices.map { r.top + 40 + it * (r.height() - 80) / 3 }
                labels.forEachIndexed { i, l -> c.drawText(l, r.left, rowY[i] + 12, text(look.body, 30f, Typeface.DEFAULT)); c.drawLine(r.left + 120, rowY[i], r.right, rowY[i], faint) }
                val stages = listOf(0, 2, 3, 3, 2, 1, 2, 3, 3, 2, 1, 1, 2, 3, 2, 1, 1, 1, 2, 1, 1, 0)
                val x0 = r.left + 130
                val step = (r.right - x0) / stages.size
                val path = Path().apply { moveTo(x0, rowY[stages[0]]) }
                stages.forEachIndexed { i, s ->
                    path.lineTo(x0 + i * step, rowY[s])
                    path.lineTo(x0 + (i + 1) * step, rowY[s])
                }
                c.drawPath(path, stroke(look.body, 6f))
                stages.forEachIndexed { i, s -> if (s == 1) c.drawLine(x0 + i * step, rowY[1], x0 + (i + 1) * step, rowY[1], line) }
            }
            Art.MEMORY -> {
                // Hippocampus hands the day's memories to the cortex.
                val boxPaint = stroke(accent, 6f)
                val a = RectF(r.left, r.top + 60, r.left + 240, r.top + 200)
                val b = RectF(r.right - 240, r.bottom - 200, r.right, r.bottom - 60)
                c.drawRoundRect(a, 28f, 28f, boxPaint)
                c.drawRoundRect(b, 28f, 28f, boxPaint)
                c.drawText("Hippocampus", a.left + 22, a.centerY() + 12, text(look.title, 34f, Typeface.DEFAULT_BOLD))
                c.drawText("Cortex", b.left + 70, b.centerY() + 12, text(look.title, 34f, Typeface.DEFAULT_BOLD))
                val arrow = Path().apply { moveTo(a.centerX(), a.bottom + 20); cubicTo(a.centerX(), r.centerY() + 60, b.left - 120, b.centerY(), b.left - 24, b.centerY()) }
                c.drawPath(arrow, stroke(look.body, 6f))
                c.drawPath(Path().apply { moveTo(b.left - 50, b.centerY() - 22); lineTo(b.left - 20, b.centerY()); lineTo(b.left - 50, b.centerY() + 22) }, stroke(look.body, 6f))
                c.drawText("replay", r.centerX() - 120, r.centerY() + 10, text(accent, 34f, Typeface.create("sans-serif", Typeface.ITALIC)))
            }
            Art.CLOCK -> {
                // 24 hours: night from 22:00 to 6:00 in the accent colour.
                val cx = r.centerX()
                val cy = r.centerY()
                val rad = 200f
                val oval = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
                c.drawCircle(cx, cy, rad, stroke(look.body, 5f))
                c.drawArc(RectF(oval).apply { inset(-18f, -18f) }, -90f + 22 * 15f, 8 * 15f, false, stroke(accent, 16f))
                c.drawArc(RectF(oval).apply { inset(-18f, -18f) }, -90f + 6 * 15f, 16 * 15f, false, stroke(0xFFF5C451.toInt(), 16f))
                for (h in 0 until 24) {
                    val a = Math.toRadians(h * 15.0 - 90)
                    val inner = if (h % 6 == 0) rad - 34 else rad - 18
                    c.drawLine(cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(), cx + rad * cos(a).toFloat(), cy + rad * sin(a).toFloat(), stroke(look.body, if (h % 6 == 0) 6f else 3f))
                }
                c.drawText("24 h", cx - 52, cy + 16, text(look.title, 46f, Typeface.DEFAULT_BOLD))
            }
            Art.RING -> {
                // A six-membered ring with alternating double bonds.
                val cx = r.centerX()
                val cy = r.centerY()
                val rad = 130f
                val pts = (0 until 6).map { val a = Math.toRadians(60.0 * it - 90); (cx + rad * cos(a).toFloat()) to (cy + rad * sin(a).toFloat()) }
                val bond = stroke(look.title, 8f)
                for (i in 0 until 6) {
                    val (x1, y1) = pts[i]
                    val (x2, y2) = pts[(i + 1) % 6]
                    c.drawLine(x1, y1, x2, y2, bond)
                    if (i % 2 == 0) c.drawLine(x1 + (cx - x1) * 0.18f, y1 + (cy - y1) * 0.18f, x2 + (cx - x2) * 0.18f, y2 + (cy - y2) * 0.18f, stroke(accent, 8f))
                }
                val (mx, my) = pts[2]
                c.drawLine(mx, my, mx + 70, my + 40, bond)
                c.drawText("CH₃", mx + 78, my + 58, text(look.title, 40f, Typeface.DEFAULT_BOLD))
            }
            Art.PHONE -> {
                // A phone's screen: header, two fields and a button.
                val phone = RectF(r.centerX() - 110, r.top, r.centerX() + 110, r.bottom)
                c.drawRoundRect(phone, 40f, 40f, fill(Color.WHITE))
                c.drawRoundRect(phone, 40f, 40f, stroke(look.title, 6f))
                c.drawRoundRect(RectF(phone.left + 30, phone.top + 70, phone.right - 30, phone.top + 110), 10f, 10f, fill(look.title))
                for (k in 0 until 2) c.drawRoundRect(RectF(phone.left + 30, phone.top + 170 + k * 80, phone.right - 30, phone.top + 220 + k * 80), 12f, 12f, stroke(look.body, 4f))
                c.drawRoundRect(RectF(phone.left + 30, phone.bottom - 120, phone.right - 30, phone.bottom - 60), 30f, 30f, fill(accent))
            }
            Art.CURVE -> {
                // Loss falling as training goes on, with the steps of gradient descent.
                c.drawLine(r.left, r.top, r.left, r.bottom, stroke(look.body, 4f))
                c.drawLine(r.left, r.bottom, r.right, r.bottom, stroke(look.body, 4f))
                val curve = Path()
                val n = 60
                for (k in 0..n) {
                    val x = r.left + 20 + (r.width() - 40) * k / n
                    val y = r.bottom - 30 - (r.height() - 80) * (1 - kotlin.math.exp(-4.0 * (n - k) / n).toFloat()) * 0.95f
                    if (k == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
                }
                c.drawPath(curve, stroke(look.body, 5f))
                for (k in listOf(2, 9, 17, 27, 40, 55)) {
                    val x = r.left + 20 + (r.width() - 40) * k / n
                    val y = r.bottom - 30 - (r.height() - 80) * (1 - kotlin.math.exp(-4.0 * (n - k) / n).toFloat()) * 0.95f
                    c.drawCircle(x, y, 14f, fill(accent))
                }
                c.drawText("loss", r.left + 20, r.top + 30, text(look.body, 32f, Typeface.DEFAULT))
            }
        }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun text(color: Int, size: Float, face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = face
    }

    // ---- Driving the app ----

    private fun open(demo: Demo) {
        settle()
        compose.onNodeWithText(SessionRepository.get(demo.id)!!.title).performClick()
        settle()
    }

    /** Scrolls the timeline so the row [key] is at the top. (The header above the rows is item 0.) */
    private fun scrollTo(demo: Demo, key: String, mode: TimelineMode) {
        val row = timelineRows(SessionRepository.get(demo.id)!!, mode).indexOfFirst { it.key == key }
        compose.onNode(hasScrollToNodeAction()).performScrollToIndex(row + 1)
        settle()
    }

    private fun play(demo: Demo, at: String) {
        PlayerController.playFrom(demo.id, SessionRepository.get(demo.id)!!.recordings.single(), t(at) - 300)
        settle()
    }

    /** Lets photos, waveforms and saves finish, and the screen catch up. */
    private fun settle() {
        SessionRepository.awaitIo()
        val done = CountDownLatch(1)
        Waveform.afterQueued { done.countDown() }
        done.await(5, TimeUnit.SECONDS)
        repeat(12) {
            Thread.sleep(40)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun render(): Bitmap {
        val view = compose.activity.window.decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    /**
     * The screen once it has stopped changing for a second and a half: photos decode in the background
     * and arrive one by one. (Ten seconds at most, since a blinking cursor never stops.)
     */
    private fun steadyScreen(): Bitmap {
        settle()
        var last = render()
        var unchanged = 0
        repeat(100) {
            Thread.sleep(100)
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            val now = render()
            unchanged = if (now.sameAs(last)) unchanged + 1 else 0
            last = now
            if (unchanged >= 15) return now
        }
        return last
    }

    /** The screen as a phone shows it: a status bar above, the gesture bar below, and rounded corners. */
    private fun screenshot(name: String) {
        val screen = steadyScreen()
        val d = app.resources.displayMetrics.density
        val statusH = (28 * d).roundToInt()
        val navH = (22 * d).roundToInt()
        val topColor = screen.getPixel(screen.width / 2, 2)
        val bottomColor = screen.getPixel(screen.width / 2, screen.height - 2)
        val phone = Bitmap.createBitmap(screen.width, statusH + screen.height + navH, Bitmap.Config.ARGB_8888)
        Canvas(phone).apply {
            drawRect(0f, 0f, width.toFloat(), statusH.toFloat(), fill(topColor))
            drawRect(0f, (statusH + screen.height).toFloat(), width.toFloat(), height.toFloat(), fill(bottomColor))
            drawBitmap(screen, 0f, statusH.toFloat(), null)
            statusBar(this, statusH.toFloat(), ink(topColor), d)
            val pill = RectF(width / 2f - 54 * d, height - navH / 2f - 2 * d, width / 2f + 54 * d, height - navH / 2f + 2 * d)
            drawRoundRect(pill, 2 * d, 2 * d, fill(ink(bottomColor)).apply { alpha = 150 })
        }
        val framed = Bitmap.createBitmap(phone.width, phone.height, Bitmap.Config.ARGB_8888)
        Canvas(framed).apply {
            val radius = 30 * d
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(phone, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) })
            // A hairline edge, so a light screen still stands out on a white page.
            rect.inset(d / 2, d / 2)
            drawRoundRect(rect, radius, radius, stroke(if (dark(topColor)) 0xFF3C4043.toInt() else 0xFFD3D7DD.toInt(), d).apply { strokeCap = Paint.Cap.BUTT })
        }
        val dir = out!!.apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun statusBar(c: Canvas, h: Float, ink: Int, d: Float) {
        val mid = h / 2
        c.drawText("9:41", 26 * d, mid + 5 * d, text(ink, 14 * d, Typeface.create("sans-serif-medium", Typeface.NORMAL)))
        var x = c.width - 24 * d
        // Battery, upright as Android draws it.
        val battery = RectF(x - 8 * d, mid - 7 * d, x, mid + 7 * d)
        c.drawRoundRect(battery, 1.5f * d, 1.5f * d, fill(ink).apply { alpha = 90 })
        c.drawRoundRect(RectF(battery.left, battery.top + 3 * d, battery.right, battery.bottom), 1.5f * d, 1.5f * d, fill(ink))
        c.drawRect(battery.centerX() - 2 * d, battery.top - 1.5f * d, battery.centerX() + 2 * d, battery.top, fill(ink))
        x -= 16 * d
        // Signal: four bars.
        for (k in 0 until 4) {
            val bx = x - (3 - k) * 4 * d
            c.drawRoundRect(RectF(bx - 3 * d, mid + 6 * d - (k + 1) * 3 * d, bx, mid + 6 * d), 0.8f * d, 0.8f * d, fill(ink))
        }
        x -= 22 * d
        // Wi-Fi: a full fan.
        c.drawArc(RectF(x - 9 * d, mid - 6 * d, x + 9 * d, mid + 12 * d), 225f, 90f, true, fill(ink))
    }

    private fun dark(color: Int) = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) < 128

    private fun ink(background: Int) = if (dark(background)) Color.WHITE else 0xFF1F1F1F.toInt()

    // ---- Times ----

    private fun t(mmss: String): Long = mmss.split(":").let { (m, s) -> (m.toLong() * 60 + s.toLong()) * 1000 }

    private fun said(from: String, to: String, text: String) = TranscriptSegment(t(from), t(to), text)

    private fun on(month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply { set(2026, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
}
