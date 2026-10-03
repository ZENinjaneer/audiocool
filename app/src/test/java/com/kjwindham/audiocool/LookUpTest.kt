package com.kjwindham.audiocool

import com.kjwindham.audiocool.lookup.Explain
import com.kjwindham.audiocool.lookup.LookUp
import com.kjwindham.audiocool.lookup.Wikipedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Looking words up: the meaning that fits the talk picked from Wikipedia's, sending only the word. */
class LookUpTest {
    /** Wikipedia as it answers about REM and SCN (abridged), and what was asked of it. */
    private val asked = ArrayList<String>()
    private val wiki: (String) -> String? = { url ->
        asked += url
        when {
            url.endsWith("/page/summary/REM") -> """{"type": "disambiguation", "title": "Rem", "extract": "Rem or REM may refer to:"}"""
            url.contains("titles=REM") -> links(
                "Rapid eye movement sleep" to "Phase of sleep characterized by random and rapid eye movements",
                "Dream Hunter Rem" to "Japanese original video animation (OVA) series",
                "R.E.M." to "American rock band",
                "Roentgen equivalent man" to "Radiation unit",
                "Rem" to "Topics referred to by the same term",
            )
            url.endsWith("/page/summary/Rapid%20eye%20movement%20sleep") -> summary("Rapid eye movement sleep", "A phase of sleep with quick, random eye movements and vivid dreams.")
            url.endsWith("/page/summary/SCN") -> """{"type": "disambiguation", "title": "SCN"}"""
            url.contains("titles=SCN") -> links("Solid cell nests" to "Specific groups of cells found in the thyroid gland of babies", "Suprachiasmatic nucleus" to "Part of the brain's hypothalamus")
            url.endsWith("/page/summary/Suprachiasmatic%20nucleus") -> summary("Suprachiasmatic nucleus", "The suprachiasmatic nucleus is the brain's master clock.")
            url.endsWith("/page/summary/Solid%20cell%20nests") -> summary("Solid cell nests", "Solid cell nests are groups of cells in the thyroid.")
            url.endsWith("/page/summary/Melatonin") -> summary("Melatonin", "Melatonin is a hormone that regulates sleep.")
            url.endsWith("/page/summary/Zeitgeber") -> null
            url.contains("gsrsearch=Zeitgeber") -> links("Circadian rhythm" to "Natural internal process that regulates the sleep–wake cycle")
            url.endsWith("/page/summary/Circadian%20rhythm") -> summary("Circadian rhythm", "A circadian rhythm is a roughly 24-hour cycle.")
            else -> null
        }
    }

    @Test
    fun aWordWithSeveralMeaningsGetsTheOneThatFitsTheTalk() {
        val found = Wikipedia.lookUp("REM", listOf("Each cycle moves from light sleep into deep sleep, and then up into REM, where most dreaming happens."), fetch = wiki)
        assertEquals("Rapid eye movement sleep", found.article!!.title)
        assertTrue(found.article!!.extract.startsWith("A phase of sleep"))
        // Other meanings: what else the letters stand for, and namesakes.
        assertEquals(listOf("R.E.M. (American rock band)", "Roentgen equivalent man (Radiation unit)"), found.others)
        // Only the word went out, and then the article picked.
        assertTrue(asked.all { it.contains("REM") || it.contains("Rapid%20eye") })
    }

    @Test
    fun whatTheTalkTookItToMeanDecidesWhenTheWordsAroundItDont() {
        val around = listOf("A small cluster of cells called the SCN keeps a 24-hour rhythm.")
        assertEquals("Solid cell nests", Wikipedia.lookUp("SCN", around, fetch = wiki).article!!.title)
        assertEquals("Suprachiasmatic nucleus", Wikipedia.lookUp("SCN", around, "The SCN is the suprachiasmatic nucleus, the brain's clock.", wiki).article!!.title)
    }

    @Test
    fun aPlainWordAndOneWithNoPageOfItsOwn() {
        assertEquals("Melatonin", Wikipedia.lookUp("Melatonin", listOf("Screens delay melatonin."), fetch = wiki).article!!.title)
        // No page by that name: found by searching.
        assertEquals("Circadian rhythm", Wikipedia.lookUp("Zeitgeber", listOf("Light is the strongest zeitgeber for the sleep cycle."), fetch = wiki).article!!.title)
        assertNull(Wikipedia.lookUp("Xyzzy", listOf("nothing"), fetch = wiki).article)
    }

    @Test
    fun aSlidesKeyTermsAreItsAcronymsThenItsLongerWords() {
        val terms = Explain.keyTerms("Your circadian clock\nThe SCN sets it\nMorning light resets it\nMelatonin rises at night", "the circadian clock and melatonin")
        assertEquals(listOf("SCN", "circadian", "Melatonin"), terms)
    }

    @Test
    fun theModelIsAskedWhatTheWordMeansHere() {
        val prompt = LookUp.meaningPrompt("REM", listOf("Then up into REM, where most dreaming happens."))
        assertTrue(prompt.contains("- Then up into REM, where most dreaming happens."))
        assertTrue(prompt.endsWith("start with what it stands for."))
        assertEquals("Rapid eye movement sleep, the dreaming stage.", LookUp.cleanMeaning("\"Rapid eye movement sleep, the dreaming stage.\"\n"))
    }

    private fun links(vararg pages: Pair<String, String>) =
        """{"query": {"pages": [${pages.joinToString(",") { (t, d) -> """{"title": "$t", "description": "$d"}""" }}]}}"""

    private fun summary(title: String, extract: String) =
        """{"type": "standard", "title": "$title", "extract": "$extract", "content_urls": {"mobile": {"page": "https://en.m.wikipedia.org/wiki/${title.replace(' ', '_')}"}}}"""
}
