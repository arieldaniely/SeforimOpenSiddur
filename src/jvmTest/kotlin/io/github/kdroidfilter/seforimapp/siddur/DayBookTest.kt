package io.github.kdroidfilter.seforimapp.siddur

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DayBookTest {
    @Test
    fun minchaOnFestivalEvesOmitsTachanun() {
        val eves = listOf(LocalDate(2026, 9, 11), LocalDate(2026, 9, 20), LocalDate(2026, 9, 25), LocalDate(2027, 4, 21))
        for (nusach in Nusach.entries) {
            val prefs = SiddurPrefs(nusach, inIsrael = true, minyan = true, beitAvel = false)
            for (date in eves) {
                assertFalse("tachanun" in flagsFor(date, SiddurPart.MINCHA, prefs), "$nusach $date")
            }
            assertTrue("erev_yom_kippur" in flagsFor(LocalDate(2026, 9, 20), SiddurPart.MINCHA, prefs))
        }
    }

    @Test
    fun nightUsesTheNextHebrewDay() {
        val friday = LocalDate(2026, 11, 6)
        val saturday = LocalDate(2026, 11, 7)
        val sunset = Instant.parse("2026-11-06T15:00:00Z")
        val (date, part) = siddurOfTheHour(sunset, friday, null, null, sunset)
        val prefs = SiddurPrefs(Nusach.ASHKENAZ, inIsrael = true, minyan = true, beitAvel = false)

        assertEquals(saturday, date)
        assertEquals(SiddurPart.MAARIV_SHABBAT, dayPartFor(date, part, prefs))
        assertEquals(friday, civilDayOf(date, part))
    }

    @Test
    fun showAllRetainsTheSourceOrderAndMarksAnInactiveBlock() {
        val lines = listOf(
            "רצה יהוה אלהינו בעמך ישראל",
            "<small>בראש חדש ובחול המועד אומרים זה:</small>",
            "אלהינו ואלהי אבותינו יעלה ויבא",
            "ותחזינה עינינו בשובך לציון",
        ).map(::SiddurLine)
        assertEquals(listOf(lines.first(), lines.last()), annotateCorpus(lines, emptySet(), showAll = false))
        val all = annotateCorpus(lines, emptySet(), showAll = true)
        assertEquals(lines.map(SiddurLine::html), all.map(SiddurLine::html))
        assertEquals(listOf(false, true, true, false), all.map(SiddurLine::skipped))
    }

    @Test
    fun weekdayAndShabbatUseDifferentSectionsFromTheSameSource() = runBlocking {
        val source = SiddurSource {
            SourceBook(
                listOf("סידור אשכנז", "ימי חול", "תפילת שחרית", "חול", "שבת", "מעריב", "לילה"),
                listOf(
                    SourceHeading(0, 1, "סידור אשכנז"),
                    SourceHeading(1, 2, "ימי חול"),
                    SourceHeading(2, 3, "תפילת שחרית"),
                    SourceHeading(4, 2, "שבת"),
                    SourceHeading(5, 3, "מעריב"),
                ),
            )
        }
        val prefs = SiddurPrefs(Nusach.ASHKENAZ, inIsrael = true, minyan = true, beitAvel = false)
        val sections = buildDayBook(LocalDate(2026, 11, 6), prefs, emptySet(), false, source)
        assertEquals(listOf(SiddurPart.SHACHARIT, SiddurPart.MAARIV_SHABBAT), sections.map(BookSection::part))
        assertFalse(sections.first().night)
        assertTrue(sections.last().night)
    }
}
