package nl.nbroers.reageren

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * AH-loon, overgezet uit dashboard/widgets/ah.js (zelfde regels): per minuut het tarief met de toeslag die dan geldt
 * (dagen/tijdvak, NL-feestdagen, hoogste of optellen), onbetaalde pauze naar verhouding, uitbetaalde uren als % van
 * alle uren, vakantietoeslag en winstuitkering, netto = bruto × nettoVerhouding (schatting).
 * Het loon zelf (ahLoon) komt uit de Supabase van het dashboard (_telefoon.loon); verandert de formule in ah.js, dan hier ook.
 */
class Loon(l: JSONObject) {
    private class Regel(val naam: String, val procent: Double, val van: Int, val tot: Int, val dagen: Set<String>)
    private val wage = l.optDouble("brutoUurloon", 0.0)
    private val regels = l.optJSONArray("toeslagen")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("procent") && !it.isNull("procent") && it.has("van") && it.has("tot") }
            .map { r -> Regel(r.optString("naam"), r.getDouble("procent"), toMin(r.getString("van")), toMin(r.getString("tot")),
                r.optJSONArray("dagen")?.let { d -> (0 until d.length()).map { d.getString(it) }.toSet() } ?: emptySet()) }
    } ?: emptyList()
    private val optellen = l.optString("meerdereToeslagen") == "optellen"
    private val feestdagen = l.optJSONArray("feestdagen")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    private val pauzes = l.optJSONArray("pauzes")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("langerDanUren") && it.has("minuten") }
            .map { it.getDouble("langerDanUren") to it.getDouble("minuten") }.sortedByDescending { it.first }
    } ?: emptyList()
    private val vakantiePct: Double? = if (l.has("vakantiegeldProcent") && !l.isNull("vakantiegeldProcent")) l.getDouble("vakantiegeldProcent") else null
    private val vakantieInPeriode = l.optBoolean("vakantiegeldPerPeriode", false)
    private val winstPct: Double? = if (l.has("winstuitkeringProcent") && !l.isNull("winstuitkeringProcent")) l.getDouble("winstuitkeringProcent") else null
    private val extraUren = l.optJSONArray("uitbetaaldeUren")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("procent") && !it.isNull("procent") }.map { it.getDouble("procent") } } ?: emptyList()
    private val vast = l.optJSONArray("vasteToeslagen")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("bedrag") && !it.isNull("bedrag") }.sumOf { it.getDouble("bedrag") } } ?: 0.0
    private val ratio: Double? = if (l.has("nettoVerhouding") && !l.isNull("nettoVerhouding")) l.getDouble("nettoVerhouding") else null

    val bruikbaar get() = wage > 0

    private fun toMin(hhmm: String): Int { val p = hhmm.split(":"); return (p[0].toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0) }

    private val zone = ZoneId.systemDefault()
    private val feestCache = HashMap<Int, Set<LocalDate>>()
    private fun feest(y: Int): Set<LocalDate> = feestCache.getOrPut(y) {
        val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val hh = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - hh - k) % 7; val m = (a + 11 * hh + 22 * l) / 451
        val maand = (hh + l - 7 * m + 114) / 31; val dag = (hh + l - 7 * m + 114) % 31 + 1
        val pasen = LocalDate.of(y, maand, dag)
        var koning = LocalDate.of(y, 4, 27); if (koning.dayOfWeek == DayOfWeek.SUNDAY) koning = koning.minusDays(1)
        val alle = mapOf(
            "nieuwjaar" to LocalDate.of(y, 1, 1), "pasen1" to pasen, "pasen2" to pasen.plusDays(1), "koningsdag" to koning,
            "bevrijdingsdag" to LocalDate.of(y, 5, 5), "hemelvaart" to pasen.plusDays(39), "pinksteren1" to pasen.plusDays(49),
            "pinksteren2" to pasen.plusDays(50), "kerst1" to LocalDate.of(y, 12, 25), "kerst2" to LocalDate.of(y, 12, 26),
        )
        alle.filterKeys { it in feestdagen }.values.toSet()
    }
    private val DAG = listOf("ma", "di", "wo", "do", "vr", "za", "zo")

    /** Bruto voor het deel van een dienst binnen [van, tot). */
    private fun dienst(s: Afspraak, van: Long, tot: Long): Double {
        val a = maxOf(s.start, van); val b = minOf(s.eind, tot)
        if (b <= a) return 0.0
        var raw = 0.0; var min = 0
        var t = a
        while (t < b) {
            val dt = Instant.ofEpochMilli(t).atZone(zone)
            val minuut = dt.hour * 60 + dt.minute
            val isFeest = dt.toLocalDate() in feest(dt.year)
            val dag = DAG[dt.dayOfWeek.value - 1]
            val hits = regels.filter { (dag in it.dagen || (isFeest && "feestdag" in it.dagen)) && minuut >= it.van && minuut < it.tot }
            val pct = if (hits.isEmpty()) 0.0 else if (optellen) hits.sumOf { it.procent } else hits.maxOf { it.procent }
            raw += wage / 60 * (1 + pct / 100)
            min++
            t += 60000
        }
        val heel = (s.eind - s.start) / 3600000.0
        val regel = pauzes.firstOrNull { heel > it.first }
        val deel = min / ((s.eind - s.start) / 60000.0)
        val onbetaald = if (regel != null) minOf(min.toDouble(), regel.second * deel) else 0.0
        return if (min > 0) raw * (min - onbetaald) / min else 0.0
    }

    /** Netto (schatting) voor alle diensten in [van, tot); null als de verhouding ontbreekt. */
    fun netto(diensten: List<Afspraak>, van: Long, tot: Long): Double? {
        val r = ratio ?: return null
        val bruto = diensten.sumOf { dienst(it, van, tot) }
        val alleUren = if (wage > 0) bruto / wage else 0.0
        val extra = extraUren.sumOf { alleUren * (it / 100) * wage }
        val basis = bruto + extra
        val vakantie = vakantiePct?.let { basis * it / 100 }
        val winst = winstPct?.let { basis * it / 100 } ?: 0.0
        val totaal = basis + vast + (if (vakantieInPeriode && vakantie != null) vakantie else 0.0) + winst
        return totaal * r
    }

    companion object {
        /** AH-periode van 4 weken uit "Salaris" in de agenda (zoals ah.js); anders deze maand. */
        fun periode(afspraken: List<Afspraak>, nu: Long = System.currentTimeMillis()): Pair<Long, Long> {
            val zone = ZoneId.systemDefault()
            fun dagMs(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
            fun dagVan(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val betaal = afspraken.filter { it.titel.contains("salaris", true) }.map { dagVan(it.start) }.sorted()
            if (betaal.isEmpty()) {
                val m = LocalDate.now().withDayOfMonth(1)
                return dagMs(m) to dagMs(m.plusMonths(1))
            }
            // Eerste dag ná de periode = maandag van de week vóór de betaaldag
            val eindVan = { p: LocalDate -> p.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(7) }
            val anker = eindVan(betaal[0]).minusDays(28)
            val dagen = java.time.temporal.ChronoUnit.DAYS.between(anker, dagVan(nu))
            val k = Math.floorDiv(dagen, 28L)
            val start = anker.plusDays(28 * k)
            return dagMs(start) to dagMs(start.plusDays(28))
        }
    }
}
