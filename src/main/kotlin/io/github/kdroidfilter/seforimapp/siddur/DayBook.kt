package io.github.kdroidfilter.seforimapp.siddur

import io.github.kdroidfilter.seforimapp.siddur.SiddurLine.Kind
import io.github.kdroidfilter.seforimapp.siddur.SiddurPart.*
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** The civil day's morning and afternoon followed by the Jewish date that begins at sunset. */
suspend fun buildDayBook(
    civil: LocalDate,
    prefs: SiddurPrefs,
    options: Set<String>,
    showAll: Boolean,
    source: SiddurSource,
): List<BookSection> {
    val outlines = mutableMapOf<String, Outline?>()
    suspend fun outline(title: String): Outline? {
        if (title !in outlines) outlines[title] = source.book(title)?.let(::Outline)
        return outlines[title]
    }

    val nusach = prefs.nusach
    val sourceBook = source.book(nusach.siddurTitle()) ?: return emptyList()
    val amud = AmudAnnotations(nusach, sourceBook.lines)
    val base = Outline(sourceBook)
    val tomorrow = civil.plus(1, DateTimeUnit.DAY)
    val dayFlags = flagsFor(civil, SHACHARIT, prefs)
    val nightFlags = flagsFor(tomorrow, MAARIV, prefs)
    val fast = "yom_kippur" in dayFlags
    val fastTonight = "yom_kippur" in nightFlags
    val dayParts = buildList {
        add(dayPartFor(civil, SHACHARIT, prefs))
        if (!fast && ("sat" in dayFlags || "festival" in dayFlags)) {
            add(if ("festival" in dayFlags) SEUDAT_YOM_TOV else SEUDAT_SHABBAT)
        }
        add(dayPartFor(civil, MINCHA, prefs))
        if (fast) add(NEILA)
        if (!fast) addAll(listOf(BIRKAT_HAMAZON, MEEIN_SHALOSH, TEFILAT_HADERECH))
    }
    val nightParts = buildList {
        if (nightFlags.any { it.startsWith("chanuka_") } || showAll) add(NEROT_CHANUKA)
        if ("sat" in nightFlags && "festival" !in nightFlags) add(KABBALAT_SHABBAT)
        add(dayPartFor(tomorrow, MAARIV, prefs))
        if ("havdala" in nightFlags) add(HAVDALA)
        if ("omer" in nightFlags || showAll) add(SFIRAT_HAOMER)
        if (!fastTonight && ("sat" in nightFlags || "festival" in nightFlags)) {
            add(if ("festival" in nightFlags) LEIL_YOM_TOV else LEIL_SHABBAT)
            add(BIRKAT_HAMAZON)
        }
        if ("levana" in nightFlags || showAll) add(KIDDUSH_LEVANA)
        add(KRIAT_SHEMA_AL_HAMITA)
    }

    suspend fun section(part: SiddurPart, date: LocalDate, night: Boolean): BookSection? {
        val selected = options.filter { it.startsWith("${part.name}:") }.map { it.substringAfter(':') }.toSet()
        val flags = flagsFor(date, part, prefs) + if ("zimun_10" in selected) selected + "zimun" else selected
        val machzor = if (part.festival) machzorTitle(flags, nusach) else null
        var lines = when {
            machzor != null -> {
                val keys = machzorKeys(part, flags).filter { holidayReading(it, flags) }
                outline(machzor)?.read(keys, flags, showAll).orEmpty()
            }
            part.festival && part.familyOf() != part -> festivalPrayer(base, part, flags, nusach, showAll)
            else -> base.read(baseKeys(part, nusach), flags, showAll)
        }
        if (part.familyOf() == SHACHARIT && machzor == null) {
            lines = morningAdditions(lines, base, flags, nusach, showAll)
        }
        if (part == SFIRAT_HAOMER && !showAll) lines = selectOmer(lines, flags, nusach)
        if (part == MEEIN_SHALOSH && !showAll) lines = selectFood(lines, flags)
        if (part == NEROT_CHANUKA && lines.isNotEmpty()) lines = lines.withSlot("chanuka_candles")
        if (part == KIDDUSH_LEVANA && lines.isNotEmpty()) lines = lines.withSlot("kiddush_levana")
        val annotated = annotateCorpus(lines, flags, showAll, amud.takeIf { machzor == null })
        return annotated.takeIf { it.any { line -> line.kind == Kind.TEXT } }?.let { BookSection(part, date, night, flags, it) }
    }

    return dayParts.mapNotNull { section(it, civil, false) } + nightParts.mapNotNull { section(it, tomorrow, true) }
}

private fun holidayReading(key: String, flags: Set<String>): Boolean =
    when (key) {
        "קריאה ליום ראשון", "הפטרה ליום א" -> "rh_second_day" !in flags
        "קריאה ליום שני", "הפטרה ליום שני" -> "rh_second_day" in flags
        else -> true
    }

private fun List<SiddurLine>.withSlot(name: String): List<SiddurLine> =
    take(1) + SiddurLine(name, Kind.SLOT) + drop(1)

private fun festivalPrayer(
    base: Outline,
    part: SiddurPart,
    flags: Set<String>,
    nusach: Nusach,
    showAll: Boolean,
): List<SiddurLine> {
    val fallback =
        when (part) {
            SHACHARIT_YOM_TOV -> SHACHARIT_SHABBAT
            MINCHA_YOM_TOV -> MINCHA_SHABBAT
            else -> MAARIV_SHABBAT
        }
    val ordinary = base.read(baseKeys(fallback, nusach), flags, showAll)
    val amidah =
        base.read(
            when (nusach) {
                Nusach.ASHKENAZ -> listOf("תפילות לשלוש רגלים/עמידה לערבית, שחרית, מנחה")
                Nusach.SEFARD -> listOf("לשלש רגלים/תפילת עמידה לשחרית, מנחה וערבית של ג רגלים")
                Nusach.EDOT_HAMIZRACH -> listOf("תפילה לשלש רגלים/עמידה")
            },
            flags,
            showAll,
        )
    val start = ordinary.indexOfFirst { it.kind == Kind.HEADING && plain(it.html) in amidahHeadings }
    if (start < 0 || amidah.isEmpty()) return amidah
    val level = ordinary[start].headingLevel
    val end = (start + 1 until ordinary.size).firstOrNull {
        ordinary[it].kind == Kind.HEADING && ordinary[it].headingLevel <= level
    } ?: ordinary.size
    return ordinary.take(start) + amidah.map { it.copy(special = true) } + ordinary.drop(end)
}

private val amidahHeadings = setOf("עמידה", "תפילת עמידה", "תפילת שבע", "תפלת שבע")

private fun morningAdditions(
    lines: List<SiddurLine>,
    base: Outline,
    flags: Set<String>,
    nusach: Nusach,
    showAll: Boolean,
): List<SiddurLine> {
    val fullHallel = "hallel_shalem" in flags
    val halfHallel = "chatzi_hallel" in flags
    val hallel = if (fullHallel || halfHallel || showAll) {
        val keys = when (nusach) {
            Nusach.ASHKENAZ -> listOf("ראש חודש/הלל")
            Nusach.SEFARD -> listOf("לראש חודש/סדר הלל")
            Nusach.EDOT_HAMIZRACH -> listOf("סדר ראש חודש/הלל לראש חודש ולמועדים")
        }
        base.read(keys, flags, showAll).mapNotNull { line ->
            val label = plain(line.html)
            val omitted = (halfHallel || showAll && !fullHallel) &&
                (label.startsWith("לא לנו") || label.startsWith("אהבתי") || "מדלגין" in label ||
                    nusach == Nusach.EDOT_HAMIZRACH && (label.startsWith("ברוך אתה") || label.startsWith("יהללוך")))
            if (omitted && !showAll) null else line.copy(special = true, skipped = omitted || !halfHallel && !fullHallel)
        }
    } else {
        emptyList()
    }
    val musafKeys = when {
        "festival" in flags || "chol_hamoed" in flags -> when (nusach) {
            Nusach.ASHKENAZ -> listOf("תפילות לשלוש רגלים/מוסף")
            Nusach.SEFARD -> listOf("לשלש רגלים/מוסף לג רגלים")
            Nusach.EDOT_HAMIZRACH -> listOf("תפילה לשלש רגלים/מוסף")
        }
        "sat" in flags -> listOf("מוסף לשבת", "מוסף של שבת")
        "rosh_chodesh" in flags -> listOf("מוסף עמידה לראש חודש", "מוסף לראש חודש", "סדר ראש חודש/מוסף")
        else -> emptyList()
    }
    val musaf = base.read(musafKeys, flags, showAll).map { it.copy(special = true) }
    val reading = lines.indexOfFirst {
        it.kind == Kind.HEADING && (plain(it.html).startsWith("קריאת התורה") || plain(it.html).startsWith("סדר קריאת התורה"))
    }.takeIf { it >= 0 } ?: lines.size
    val weeklyReading = if ("sat" in flags && "festival" !in flags && "torah_reading" in flags) {
        listOf(SiddurLine("weekly_reading", Kind.SLOT))
    } else {
        emptyList()
    }
    val readingStart = (reading + 1).coerceAtMost(lines.size)
    val endPrayers = if ("sat" in flags || "festival" in flags) {
        lines.size
    } else {
        (readingStart until lines.size).firstOrNull {
            lines[it].kind == Kind.HEADING && plain(lines[it].html) in setOf("עלינו", "עלינו לשבח")
        } ?: lines.size
    }
    return lines.take(reading) + hallel + lines.subList(reading, readingStart) + weeklyReading +
        lines.subList(readingStart, endPrayers) + musaf + lines.drop(endPrayers)
}

private val numberedOmer = Regex("^.*?([0-9]{1,2})[.]\\s*היום")
private val datedOmer = Regex("^[א-ת]{1,3} (ניסן|אייר|סיון|סיוון)$")

private fun selectOmer(
    lines: List<SiddurLine>,
    flags: Set<String>,
    nusach: Nusach,
): List<SiddurLine> {
    val count = flags.firstNotNullOfOrNull { it.removePrefix("omer_").toIntOrNull() } ?: return emptyList()
    if (nusach == Nusach.EDOT_HAMIZRACH) {
        val dates = lines.indices.filter { datedOmer.matches(plain(lines[it].html)) }.take(49)
        if (dates.size != 49) return lines
        val start = dates[count - 1]
        val end = dates.getOrNull(count) ?: start + 3
        return lines.take(dates.first()) + lines.subList(start, end).map { it.copy(special = true) } + lines.drop(dates.last() + 3)
    }
    return lines.mapNotNull { line ->
        val day = numberedOmer.find(plain(line.html))?.groupValues?.get(1)?.toIntOrNull()
        when (day) {
            null -> line
            count -> line.copy(special = true)
            else -> null
        }
    }
}
