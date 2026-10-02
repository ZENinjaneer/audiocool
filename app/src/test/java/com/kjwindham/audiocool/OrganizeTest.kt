package com.kjwindham.audiocool

import com.kjwindham.audiocool.data.FolderSummary
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionSummary
import com.kjwindham.audiocool.summarize.CalendarFiling
import com.kjwindham.audiocool.summarize.OrganizePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** What the summary model is asked when organizing, and how its replies are read. */
class OrganizeTest {
    private fun at(day: Int, hour: Int) = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, day, hour, 30, 0) }.timeInMillis

    private fun session(id: String, title: String, created: Long, summary: String) =
        Session(id, title, created, created, summary = SessionSummary(summary, basis = "", model = "", createdAt = 0))

    private val sessions = listOf(
        session("a", "Sleep cycles", at(29, 9), "Sleep runs in 90-minute cycles. Deep sleep comes first."),
        session("b", "Organic reactions", at(30, 14), "How substitution reactions work."),
        session("c", "The circadian clock", at(1, 9), "Light sets the body clock."),
    )
    private val folders = listOf(FolderSummary("Work", 4, "Design reviews and standups"))

    @Test
    fun theSuggestionPromptListsEachSessionWithWhenAndWhatItWasAbout() {
        val prompt = OrganizePrompts.suggest(sessions, folders)
        assertTrue(prompt, prompt.contains("Folders there are already: Work: Design reviews and standups"))
        // Numbered in order; the day and time show a weekly course; only the summary's first sentence.
        assertTrue(prompt, prompt.contains("1. \"Sleep cycles\" (Tue 9:30 AM): Sleep runs in 90-minute cycles."))
        assertTrue(prompt, !prompt.contains("Deep sleep comes first"))
        assertTrue(prompt, prompt.contains("3. \"The circadian clock\""))
        assertTrue(prompt, prompt.contains("Reply as JSON"))
    }

    @Test
    fun suggestionsAreReadFromTheReplyWithSomeCare() {
        val ids = sessions.map { it.id }
        val reply = "Here you go:\n```json\n{\"folders\": [" +
            "{\"name\": \"Sleep science\", \"description\": \"Lectures on sleep\", \"recordings\": [1, 3, 3, 9]}," +
            "{\"name\": \"Chemistry\", \"description\": \"Just one\", \"recordings\": [2]}," +
            "{\"name\": \"work\", \"recordings\": [2]}" +
            "]}\n```"
        val parsed = OrganizePrompts.parseSuggestions(reply, ids, folders)!!
        // A new folder needs two sessions; numbers out of range or repeated are dropped; an existing folder keeps its own name.
        assertEquals(listOf("Sleep science", "Work"), parsed.map { it.name })
        assertEquals(listOf("a", "c"), parsed[0].sessionIds)
        assertEquals("Lectures on sleep", parsed[0].description)
        assertEquals(listOf("b"), parsed[1].sessionIds)
        assertTrue(parsed[1].existing)
        assertNull(OrganizePrompts.parseSuggestions("I can't help with that.", ids, folders))
    }

    @Test
    fun aNewSessionIsFiledByTheFolderNumberInTheReply() {
        val prompt = OrganizePrompts.file(sessions[2], folders, mapOf("Work" to listOf("Standup", "Design review", "Planning", "Retro")))
        assertTrue(prompt, prompt.contains("1. Work: Design reviews and standups (for example: Standup; Design review; Planning)"))
        assertTrue(prompt, prompt.contains("A new recording: \"The circadian clock\""))
        assertEquals(1, OrganizePrompts.parseFile("1", 1))
        assertEquals(1, OrganizePrompts.parseFile("Folder 1.", 1))
        assertEquals(0, OrganizePrompts.parseFile("0", 1))
        assertEquals(0, OrganizePrompts.parseFile("None of them fit.", 1))
        assertNull(OrganizePrompts.parseFile("7", 1))
        assertNull(OrganizePrompts.parseFile("", 1))
    }

    @Test
    fun theCalendarEventIsTheOneGoingOnThatStartedLast() {
        val now = at(29, 9)
        val events = listOf(
            CalendarFiling.Event("Whole day", now - 3_600_000, now + 3_600_000, allDay = true),
            CalendarFiling.Event("Morning block", now - 7_200_000, now + 3_600_000, allDay = false),
            CalendarFiling.Event("NEURO 214 Lecture", now - 600_000, now + 3_000_000, allDay = false),
            CalendarFiling.Event("Later", now + 600_000, now + 1_200_000, allDay = false),
        )
        assertEquals("NEURO 214 Lecture", CalendarFiling.eventAt(events, now)?.title)
        assertNull(CalendarFiling.eventAt(events.take(1), now))
    }
}
