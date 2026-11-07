package io.github.kdroidfilter.seforimapp.siddur

private data class Rubric(
    val start: Regex,
    val end: Regex,
    val flag: String,
    val maxRows: Int,
)

// Match both anchors in the same prayer. A changed edition leaves an unmatched block intact.
private val rubrics =
    listOf(
        Rubric(Regex("^בראש חו?דש ובחול המועד אומרים"), Regex("^(ותחזינה|ואתה ברחמיך)"), "yaale_veyavo", 10),
        Rubric(Regex("^בחנוכה ופורים אומרים"), Regex("^ועל ?כלם"), "al_hanisim", 12),
        Rubric(Regex("^מודים דרבנן$"), Regex("^(בחנוכה ופורים|ועל ?כלם)"), "minyan", 6),
        Rubric(Regex("^קדושה$"), Regex("^אתה קדוש"), "minyan", 12),
        Rubric(Regex("^ברכת כהנים$"), Regex("^שים שלום"), "minyan", 20),
        Rubric(Regex("^בתענית ציבור השליח ציבור אומר בחזרה"), Regex("^רפאנו"), "fast_repetition", 6),
        Rubric(
            Regex("^(אם המסובים שלושה או יותר אומרים|שלושה שאכלו כאחד חייבין בזימון)"),
            Regex("^ברוך אתה .{1,10} אלהינו מלך העולם"),
            "zimun",
            60,
        ),
    )

private val teshuvaInline = Regex("""<small>\s*<small>([^<>]+)</small>([^<>]*)</small>""")
private val variant = Regex("^([א-ת ]+):")
private val variants =
    mapOf(
        "בראש חדש" to "rosh_chodesh",
        "בראש חודש" to "rosh_chodesh",
        "לרח" to "rosh_chodesh",
        "בחוהמ פסח" to "pesach",
        "לפסח" to "pesach",
        "בחוהמ סוכות" to "sukkot",
        "לסכות" to "sukkot",
        "בחנוכה" to "chanuka",
        "בחנוכה אומרים" to "chanuka",
        "בפורים" to "purim",
        "בפורים אומרים" to "purim",
    )

private val tenRitual = Regex("""\(\s*(?:<small>)?בעשרה(?: או יותר| ויותר)?[: ]*(?:</small>)?\s*(<b>[^<]+</b>|[^<>()]+)\)""")
private val tenSmall = Regex("""<small>\(בעשרה(?: או יותר| ויותר)?[: ]*(<b>[^<]+</b>)\)</small>""")

internal fun annotateCorpus(
    lines: List<SiddurLine>,
    flags: Set<String>,
    showAll: Boolean,
    amud: AmudAnnotations? = null,
): List<SiddurLine> {
    val text = lines.map { plain(it.html) }
    val conditions = MutableList(lines.size) { mutableSetOf<String>() }
    for (index in lines.indices) {
        for (rule in rubrics) {
            if (!rule.start.containsMatchIn(text[index])) continue
            val end = (index + 1..minOf(index + rule.maxRows, lines.lastIndex))
                .firstOrNull { rule.end.containsMatchIn(text[it]) } ?: continue
            (index until end).forEach { conditions[it].add(rule.flag) }
        }
        val flag = variant.find(text[index])?.groupValues?.get(1)?.let(variants::get)
        if (flag != null && conditions[index].any { it == "yaale_veyavo" || it == "al_hanisim" }) {
            conditions[index].add(flag)
            if (text[index].endsWith(':') && index < lines.lastIndex) conditions[index + 1].add(flag)
        }
        val season = text[index].trimEnd(':', '׃')
        val next = text.getOrNull(index + 1).orEmpty()
        val seasonFlag = when {
            season == "בקיץ" && next.startsWith("מוריד הטל") -> "morid_hatal"
            season == "בחורף" && next.startsWith("משיב הרוח") -> "mashiv_haruach"
            season == "בקיץ" && next.startsWith("ברכנו ") -> "!tal_umatar"
            season == "בחורף" && next.startsWith("ברך עלינו ") -> "tal_umatar"
            else -> null
        }
        if (seasonFlag != null) {
            conditions[index].add(seasonFlag)
            conditions[index + 1].add(seasonFlag)
        }
    }
    return lines.mapIndexedNotNull { index, line ->
        val conditionsOnLine = conditions[index]
        val tagged = amud?.condition(line.html, flags)
        val applies = conditionsOnLine.all { flag -> if (flag.startsWith('!')) flag.drop(1) !in flags else flag in flags } &&
            tagged != false
        if (!showAll && !applies) return@mapIndexedNotNull null
        var html = if (showAll) line.html else teshuvaInline.replace(line.html) { match ->
            val instruction = plain(match.groupValues[1])
            if (!instruction.startsWith("בעשרת ימי תשובה")) {
                match.value
            } else if ("aseret_yemei_teshuva" in flags) {
                MARK_START + match.groupValues[2].trimStart() + MARK_END
            } else {
                ""
            }
        }
        if (!showAll && "zimun" in conditionsOnLine) {
            for (pattern in listOf(tenSmall, tenRitual)) {
                html = pattern.replace(html) { if ("zimun_10" in flags) it.groupValues[1] else "" }
            }
        }
        line.copy(
            html = html,
            special = line.special || applies && (html != line.html || conditionsOnLine.any { it != "minyan" }),
            skipped = line.skipped || !applies,
        )
    }
}

private val mealFoods = setOf("mezonot", "wine", "fruit")
private val lineBreak = Regex("<br\\s*/?>")

internal fun selectFood(
    lines: List<SiddurLine>,
    flags: Set<String>,
): List<SiddurLine> {
    val picked = flags.intersect(mealFoods)
    if (picked.isEmpty()) return lines
    return lines.mapNotNull { line ->
        val parts = line.html.split(lineBreak)
        val known = parts.map { foodVariant(plain(it)) }
        if (known.all { it.isEmpty() }) return@mapNotNull line
        val text = parts.filterIndexed { i, _ ->
            known[i].isEmpty() || known[i].size == 1 && known[i].all { it in picked } || known[i] == picked
        }.joinToString("<br>")
        if (text.isBlank()) null else line.copy(html = text)
    }
}

private fun foodVariant(text: String): Set<String> {
    val label = text.trimStart('(').substringBefore(':').substringBefore(" יאמר").trimEnd(')')
    return when (label) {
        "מזונות", "על מזונות", "על מזונות של ארץ ישראל", "אם אכל מזונות" -> setOf("mezonot")
        "יין", "על יין", "על היין", "על יין של ארץ ישראל", "אם שתה יין" -> setOf("wine")
        "פירות", "על הפירות", "על פירות משבעת המינים", "אם אכל פירות משבעת מינים",
        "אם אכל פרות משבעת המינים",
        -> setOf("fruit")
        "על מזונות ויין ביחד" -> mealFoods - "fruit"
        else -> emptySet()
    }
}
