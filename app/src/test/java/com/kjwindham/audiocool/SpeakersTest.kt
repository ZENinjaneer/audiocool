package com.kjwindham.audiocool

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionJson
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.SpeakerTurn
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.data.Voice
import com.kjwindham.audiocool.data.paragraphs
import com.kjwindham.audiocool.speakers.KnownVoice
import com.kjwindham.audiocool.speakers.VoicePrints
import com.kjwindham.audiocool.speakers.Voices
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Who said what: turns and voices kept with the session, phrases cut by speaker, voices matched and named. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SpeakersTest {
    private val priya = floatArrayOf(1f, 0.1f, 0f)
    private val sam = floatArrayOf(0f, 1f, 0.2f)

    @Test
    fun voicesAndTurnsAreSavedWithTheSessionButVoiceprintsStayOnThePhone() {
        val rec = Recording("r1", "a.m4a", 0, 60_000, speakers = listOf(SpeakerTurn(0, 4_000, 0), SpeakerTurn(4_000, 9_000, 1)))
        val session = Session("s", "Standup", 1, 1, recordings = listOf(rec), voices = listOf(Voice(0, "Priya"), Voice(1)))
        val json = SessionJson.encode(session)
        val back = SessionJson.decode(json)
        assertEquals(rec.speakers, back.recordings.single().speakers)
        assertEquals(session.voices, back.voices)
        assertNull(back.voices[1].name)
        // Backups and the desktop get session.json: no voiceprints in it.
        VoicePrints.set("s", mapOf(0 to priya, 1 to sam))
        assertTrue("print" !in SessionJson.encode(session))
        assertArrayEquals(priya, VoicePrints.of("s")[0], 0f)
        VoicePrints.remove("s")
        assertTrue(VoicePrints.of("s").isEmpty())
    }

    @Test
    fun aPhraseTwoPeopleSpokeInIsCutWhereTheSpeakerChanges() {
        // "Do we need it? Yes we do." — Sam asks, Priya answers, in one phrase.
        val phrase = TranscriptSegment(10_000, 13_000, "Do we need it? Yes we do.", words = listOf(0, 300, 500, 700, 1_500, 1_800, 2_100))
        val turns = listOf(SpeakerTurn(9_800, 11_200, 1), SpeakerTurn(11_400, 13_200, 0))
        val parts = Voices.splitBySpeaker(phrase, turns)
        assertEquals(listOf("Do we need it?", "Yes we do."), parts.map { it.text })
        assertEquals(listOf(10_000L, 11_500L), parts.map { it.startMs })
        assertEquals(listOf(11_500L, 13_000L), parts.map { it.endMs })
        assertEquals(listOf(0, 300, 600), parts[1].words)
        // A phrase without word times stays whole.
        assertEquals(listOf(phrase.copy(words = null)), Voices.splitBySpeaker(phrase.copy(words = null), turns))
    }

    @Test
    fun paragraphsBreakWhereTheSpeakerChanges() {
        val rec = Recording(
            "r1", "a.m4a", 0, 60_000,
            transcript = listOf(
                TranscriptSegment(0, 2_000, "Morning all.", listOf(0, 800)),
                TranscriptSegment(2_200, 4_000, "Where are we on it?", listOf(0, 400, 700, 1_000, 1_300)),
                TranscriptSegment(4_300, 6_000, "Nearly done.", listOf(0, 700)),
            ),
            speakers = listOf(SpeakerTurn(0, 4_100, 0), SpeakerTurn(4_100, 6_000, 1)),
        )
        val ps = paragraphs(rec, emptyList())
        assertEquals(listOf("Morning all. Where are we on it?", "Nearly done."), ps.map { it.text })
        assertEquals(listOf(0, 1), ps.map { it.voice })
    }

    @Test
    fun voicesAreMatchedToTheSessionsAndToNamesFromBefore() {
        val known = listOf(KnownVoice("Sam", sam))
        val stranger = floatArrayOf(0f, 0f, 1f)
        val m = Voices.match(listOf(floatArrayOf(0f, 0.95f, 0.25f), floatArrayOf(0.98f, 0.1f, 0f), stranger), listOf(Voice(0, "Priya")), mapOf(0 to priya), known, same = 0.8f, knownAt = 0.8f)
        // Priya again; Sam, known from before; and someone new.
        assertEquals(listOf(1, 0, 2), m.ids)
        assertEquals(listOf("Priya", "Sam", null), m.voices.map { it.name })
        assertEquals(setOf(0, 1, 2), m.prints.keys)
    }

    @Test
    fun namingAVoiceAfterAnotherMakesThemOne() {
        val session = SessionRepository.create("Standup")
        SessionRepository.addRecording(session.id, Recording("r1", "a.m4a", 0, 60_000))
        SessionRepository.setSpeakers(
            session.id, "r1",
            listOf(SpeakerTurn(0, 4_000, 0), SpeakerTurn(4_000, 6_000, 2), SpeakerTurn(6_000, 9_000, 1)),
            listOf(Voice(0, "Priya"), Voice(1), Voice(2), Voice(3)),
        )
        // Voice 3 spoke nowhere, so it's gone.
        assertEquals(listOf(0, 1, 2), SessionRepository.get(session.id)!!.voices.map { it.id })
        SessionRepository.nameVoice(session.id, 2, "priya")
        val after = SessionRepository.get(session.id)!!
        assertEquals(listOf(0, 1), after.voices.map { it.id })
        assertEquals(listOf(SpeakerTurn(0, 6_000, 0), SpeakerTurn(6_000, 9_000, 1)), after.recordings.single().speakers)
        SessionRepository.nameVoice(session.id, 1, "Sam")
        assertEquals("Sam", SessionRepository.get(session.id)!!.voices.single { it.id == 1 }.name)
    }
}
