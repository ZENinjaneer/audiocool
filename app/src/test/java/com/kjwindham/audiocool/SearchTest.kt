package com.kjwindham.audiocool

import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.TranscriptSegment
import com.kjwindham.audiocool.search.HitKind
import com.kjwindham.audiocool.search.findTerms
import com.kjwindham.audiocool.search.fold
import com.kjwindham.audiocool.search.searchAll
import com.kjwindham.audiocool.search.searchSession
import com.kjwindham.audiocool.search.searchTerms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val rec = Recording(
        "r1", "recording-1.m4a", createdAt = 1_000_000, durationMs = 600_000,
        transcript = listOf(
            TranscriptSegment(2_000, 6_000, "Mitochondria are the powerhouse of the cell."),
            TranscriptSegment(9_000, 14_000, "The Krebs cycle happens in the matrix."),
        ),
    )
    private val bio = Session(
        "s1", "Bio", createdAt = 1_000, updatedAt = 0, recordings = listOf(rec),
        notes = listOf(
            Note("n1", "Powerhouse!", 1_005_000, "r1", 5_000),
            Note("n2", "Ask about the café exam", 900_000),
        ),
    )

    @Test
    fun foldIgnoresCaseAndAccentsAndKeepsPositions() {
        assertEquals("cafe creme", fold("Café Crème"))
        assertEquals("Café Crème".length, fold("Café Crème").length)
        assertEquals(listOf("krebs", "cafe"), searchTerms("  Krebs   CAFÉ krebs "))
    }

    @Test
    fun everyWordMustMatch() {
        val text = "The Krebs cycle happens in the matrix."
        assertEquals(listOf(4..8), findTerms(text, searchTerms("krebs")))
        assertEquals(listOf(4..8, 31..36), findTerms(text, searchTerms("matrix krebs")))
        assertNull(findTerms(text, searchTerms("krebs mitochondria")))
        // Overlapping matches merge into one highlight; separate words stay separate.
        assertEquals(listOf(4..8), findTerms(text, searchTerms("krebs ebs")))
        assertEquals(listOf(4..8, 10..14), findTerms(text, searchTerms("cycle krebs")))
    }

    @Test
    fun findsNotesAndSpeechInTimelineOrder() {
        val hits = searchSession(bio, "powerhouse")
        assertEquals(listOf(HitKind.SPEECH, HitKind.NOTE), hits.map { it.kind })
        assertEquals(listOf(2_000L, 5_000L), hits.map { it.atMs })
        assertEquals("r1", hits[0].recId)
        assertEquals("n1", hits[1].noteId)

        val cafe = searchSession(bio, "cafe").single()
        assertEquals(HitKind.NOTE, cafe.kind)
        assertNull(cafe.atMs) // not linked to the audio
        assertEquals(listOf(14..17), cafe.matches)

        assertTrue(searchSession(bio, "").isEmpty())
        assertTrue(searchSession(bio, "photosynthesis").isEmpty())
    }

    @Test
    fun searchesNewestSessionsFirst() {
        val older = bio.copy(id = "old", createdAt = 1)
        val newer = bio.copy(id = "new", createdAt = 2)
        assertEquals(listOf("new", "new", "old", "old"), searchAll(listOf(older, newer), "powerhouse").map { it.sessionId })
        assertEquals(3, searchAll(listOf(older, newer), "powerhouse", limit = 3).size)
    }
}
