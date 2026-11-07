package io.github.kdroidfilter.seforimapp.siddur

import java.security.MessageDigest
import java.util.HexFormat

private val nonHebrew = Regex("[^א-ת]")
private val markup = Regex("<[^>]+>")
private val hebrewEntity = Regex("&#(?:([0-9]+)|[xX]([a-fA-F0-9]+));")

/** Conditions from Amud, keyed by the normalized text of an unambiguous paragraph in the local edition. */
internal class AmudAnnotations(
    nusach: Nusach,
    lines: List<String>,
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val rules = amudRules[nusach].orEmpty()
    private val matched =
        lines.mapNotNull { html -> fingerprint(html)?.takeIf(rules::containsKey)?.let { it to html } }
            .groupBy({ it.first }, { it.second })
            .mapNotNull { (key, originals) -> originals.singleOrNull()?.let { it to rules.getValue(key) } }
            .toMap()

    fun condition(
        html: String,
        flags: Set<String>,
    ): Boolean? =
        matched[html]?.any { terms ->
            terms.all { term -> if (term.startsWith('!')) term.drop(1) !in flags else term in flags }
        }

    private fun fingerprint(html: String): String? {
        val text = hebrewEntity.replace(html.replace(markup, "")) { entity ->
            val decimal = entity.groupValues[1].toIntOrNull()
            val code = decimal ?: entity.groupValues[2].toIntOrNull(16)
            code?.takeIf { it in 0x05D0..0x05EA }?.toChar()?.toString().orEmpty()
        }.replace(nonHebrew, "")
        if (text.length < 24) return null
        return HexFormat.of().formatHex(digest.digest(text.toByteArray()))
    }
}

private val amudRules by lazy {
    val stream = checkNotNull(AmudAnnotations::class.java.getResourceAsStream("/open-siddur/amud-annotations.tsv"))
    stream.bufferedReader().useLines { rows ->
        rows.map { row ->
            val (nusach, key, condition) = row.split('\t')
            Triple(Nusach.valueOf(nusach), key, condition.split('|').map { it.split('&') })
        }.groupBy { it.first }.mapValues { (_, rules) -> rules.associate { it.second to it.third } }
    }
}
