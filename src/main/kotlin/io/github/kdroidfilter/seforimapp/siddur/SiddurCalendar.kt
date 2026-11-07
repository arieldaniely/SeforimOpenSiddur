package io.github.kdroidfilter.seforimapp.siddur

import io.github.kdroidfilter.kosherkotlin.hebrewcalendar.JewishCalendar
import io.github.kdroidfilter.kosherkotlin.hebrewcalendar.HebrewMonth
import io.github.kdroidfilter.kosherkotlin.hebrewcalendar.TefilaRules
import io.github.kdroidfilter.seforimapp.siddur.SiddurPart.*
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

fun dayPartFor(
    date: LocalDate,
    part: SiddurPart,
    prefs: SiddurPrefs,
): SiddurPart {
    val calendar = JewishCalendar(date, prefs.inIsrael)
    val shabbat = date.dayOfWeek == DayOfWeek.SATURDAY
    val festival = calendar.isYomTovAssurBemelacha
    return when (part.familyOf()) {
        SHACHARIT -> if (festival) SHACHARIT_YOM_TOV else if (shabbat) SHACHARIT_SHABBAT else SHACHARIT
        MINCHA -> if (festival) MINCHA_YOM_TOV else if (shabbat) MINCHA_SHABBAT else MINCHA
        MAARIV -> if (festival) MAARIV_YOM_TOV else if (shabbat) MAARIV_SHABBAT else MAARIV
        else -> part
    }
}

internal fun flagsFor(
    date: LocalDate,
    part: SiddurPart,
    prefs: SiddurPrefs,
): Set<String> {
    val day = JewishCalendar(date, prefs.inIsrael)
    val rules = TefilaRules()
    val family = part.familyOf()
    val shabbat = date.dayOfWeek == DayOfWeek.SATURDAY
    val festival = day.isYomTovAssurBemelacha
    val previous = JewishCalendar(date.plus(-1, DateTimeUnit.DAY), prefs.inIsrael)
    val minchaBeforeFestival = family == MINCHA &&
        JewishCalendar(date.plus(1, DateTimeUnit.DAY), prefs.inIsrael).isYomTovAssurBemelacha
    return buildSet {
        if (prefs.inIsrael) add("israel")
        if (prefs.minyan) add("minyan")
        if (prefs.nusach == Nusach.ASHKENAZ) add("ashkenaz")
        if (shabbat) add("sat")
        if (festival) add("festival")
        if (!shabbat && !festival) add("weekday")
        if (family == MINCHA && day.isErevYomKippur) add("erev_yom_kippur")
        if (family == MINCHA && day.isErevRoshHashana) add("erev_rosh_hashana")
        if (day.isRoshChodesh) add("rosh_chodesh")
        if (day.isCholHamoed) add("chol_hamoed")
        if (day.isTaanis && day.yomTovIndex != JewishCalendar.YOM_KIPPUR) add("taanit")
        if (!prefs.beitAvel && when (family) {
                SHACHARIT -> rules.isTachanunRecitedShacharis(day)
                MINCHA -> !minchaBeforeFestival && rules.isTachanunRecitedMincha(day)
                else -> false
            }
        ) {
            add("tachanun")
        }
        if (rules.isYaalehVeyavoRecited(day)) add("yaale_veyavo")
        val purim = day.yomTovIndex == if (prefs.mukafChoma) JewishCalendar.SHUSHAN_PURIM else JewishCalendar.PURIM
        if (day.isChanukah || purim) {
            add("al_hanisim")
        }
        when {
            rules.isHallelShalemRecited(day) -> add("hallel_shalem")
            rules.isHallelRecited(day) -> add("chatzi_hallel")
        }
        if (shabbat || festival || day.isRoshChodesh || day.isCholHamoed) add("musaf")
        if (when {
                rules.isMashivHaruachStartDate(day) -> family == MINCHA
                rules.isMashivHaruachEndDate(day) -> family != MINCHA && !part.night
                else -> rules.isMashivHaruachRecited(day)
            }
        ) {
            add("mashiv_haruach")
        }
        if (rules.isVeseinTalUmatarRecited(day)) add("tal_umatar")
        if ("mashiv_haruach" !in this && (prefs.inIsrael || prefs.nusach != Nusach.ASHKENAZ)) add("morid_hatal")
        if (prefs.minyan && "taanit" in this && family != MAARIV) add("fast_repetition")
        if (!shabbat && !festival && day.yomTovIndex !in setOf(
                JewishCalendar.EREV_YOM_KIPPUR, JewishCalendar.EREV_PESACH, JewishCalendar.CHOL_HAMOED_PESACH,
            )
        ) {
            add("mizmor_letoda")
        }
        if ("tachanun" in this) add("lamenatzeach")
        if (day.dayOfOmer > 0) {
            add("omer")
            add("omer_${day.dayOfOmer}")
        }
        if (day.dayOfChanukah > 0) add("chanuka_${day.dayOfChanukah}")
        if (day.dayOfChanukah > 0) add("chanuka")
        if (purim) add("purim")
        if (day.jewishDayOfMonth in (if (prefs.nusach == Nusach.EDOT_HAMIZRACH) 7 else 3)..15) add("levana")
        if (previous.isAssurBemelacha && !day.isAssurBemelacha) add("havdala")
        if (date.dayOfWeek == DayOfWeek.SUNDAY) add("motzaei_shabbat")
        if (date.dayOfWeek == DayOfWeek.SUNDAY && part == MAARIV_YOM_TOV) add("vatodieinu")
        if (date.dayOfWeek == DayOfWeek.MONDAY || date.dayOfWeek == DayOfWeek.THURSDAY) add("mon_thu")
        if (day.jewishMonth == HebrewMonth.TISHREI && day.jewishDayOfMonth <= 10) add("aseret_yemei_teshuva")
        if (day.jewishMonth == HebrewMonth.ELUL || day.jewishMonth == HebrewMonth.TISHREI && day.jewishDayOfMonth <= 21) {
            add("ledavid_ori")
        }
        if (!minchaBeforeFestival && ("aseret_yemei_teshuva" in this && !shabbat && !festival || "taanit" in this)) {
            add("avinu_malkenu")
        }
        when (day.yomTovIndex) {
            JewishCalendar.ROSH_HASHANA -> add("rosh_hashana")
            JewishCalendar.YOM_KIPPUR -> add("yom_kippur")
            JewishCalendar.PESACH, JewishCalendar.CHOL_HAMOED_PESACH ->
                add(if (day.jewishDayOfMonth >= 21) "shvii_shel_pesach" else "pesach")
            JewishCalendar.SHAVUOS -> add("shavuot")
            JewishCalendar.SUCCOS, JewishCalendar.CHOL_HAMOED_SUCCOS, JewishCalendar.HOSHANA_RABBA -> add("sukkot")
            JewishCalendar.SHEMINI_ATZERES -> add(if (prefs.inIsrael) "simchat_torah" else "shmini_atzeret")
            JewishCalendar.SIMCHAS_TORAH -> add("simchat_torah")
        }
        if (day.jewishDayOfMonth == 2 && "rosh_hashana" in this) add("rh_second_day")
    }
}
