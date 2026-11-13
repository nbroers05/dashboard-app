package nl.nbroers.reageren

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * AH wage, ported from dashboard/widgets/ah.js (same rules): per minute the rate with the allowance that applies then
 * (days/time slot, Dutch public holidays, highest or added up), unpaid break proportionally, paid-out hours as % of
 * all hours, holiday pay and profit share, net = gross × netRatio (estimate).
 * The wage settings come from the dashboard's Supabase: `_phone.wage` (English fields, since app 0.8.0), or the old
 * `_telefoon.loon` (Dutch fields), which [normalize] converts the same way as normalize() in ah.js.
 * If the formula in ah.js changes, change it here too.
 */
class Loon(raw: JSONObject) {
    private class Rule(val name: String, val percent: Double, val from: Int, val to: Int, val days: Set<String>)
    private val l = normalize(raw)
    private val wage = l.optDouble("hourlyWage", 0.0)
    private val rules = l.optJSONArray("allowances")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("percent") && !it.isNull("percent") && it.has("from") && it.has("to") }
            .map { r -> Rule(r.optString("name"), r.getDouble("percent"), toMin(r.getString("from")), toMin(r.getString("to")),
                r.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.getString(it) }.toSet() } ?: emptySet()) }
    } ?: emptyList()
    private val addUp = l.optString("multipleAllowances") == "add"
    private val holidays = l.optJSONArray("holidays")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    private val breaks = l.optJSONArray("breaks")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("longerThanHours") && it.has("minutes") }
            .map { it.getDouble("longerThanHours") to it.getDouble("minutes") }.sortedByDescending { it.first }
    } ?: emptyList()
    private val holidayPct: Double? = number(l, "holidayPayPercent")
    private val holidayInPeriod = l.optBoolean("holidayPayPerPeriod", false)
    private val profitPct: Double? = number(l, "profitSharePercent")
    private val extraHours = l.optJSONArray("paidHours")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("percent") && !it.isNull("percent") }.map { it.getDouble("percent") } } ?: emptyList()
    private val fixed = l.optJSONArray("fixedAllowances")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.has("amount") && !it.isNull("amount") }.sumOf { it.getDouble("amount") } } ?: 0.0
    private val ratio: Double? = number(l, "netRatio")

    val bruikbaar get() = wage > 0

    private fun toMin(hhmm: String): Int { val p = hhmm.split(":"); return (p[0].toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0) }

    private val zone = ZoneId.systemDefault()
    private val holidayCache = HashMap<Int, Set<LocalDate>>()
    private fun holidaysOf(y: Int): Set<LocalDate> = holidayCache.getOrPut(y) {
        val a = y % 19; val b = y / 100; val c = y % 100; val d = b / 4; val e = b % 4
        val f = (b + 8) / 25; val g = (b - f + 1) / 3; val hh = (19 * a + b - d - g + 15) % 30
        val i = c / 4; val k = c % 4; val l = (32 + 2 * e + 2 * i - hh - k) % 7; val m = (a + 11 * hh + 22 * l) / 451
        val month = (hh + l - 7 * m + 114) / 31; val day = (hh + l - 7 * m + 114) % 31 + 1
        val easter = LocalDate.of(y, month, day)
        var king = LocalDate.of(y, 4, 27); if (king.dayOfWeek == DayOfWeek.SUNDAY) king = king.minusDays(1)
        val all = mapOf(
            "newyear" to LocalDate.of(y, 1, 1), "easter1" to easter, "easter2" to easter.plusDays(1), "kingsday" to king,
            "liberationday" to LocalDate.of(y, 5, 5), "ascension" to easter.plusDays(39), "whit1" to easter.plusDays(49),
            "whit2" to easter.plusDays(50), "christmas1" to LocalDate.of(y, 12, 25), "christmas2" to LocalDate.of(y, 12, 26),
        )
        all.filterKeys { it in holidays }.values.toSet()
    }
    private val DAY = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** Gross for the part of a shift within [from, to). */
    private fun shift(s: Afspraak, from: Long, to: Long): Double {
        val a = maxOf(s.start, from); val b = minOf(s.eind, to)
        if (b <= a) return 0.0
        var raw = 0.0; var min = 0
        var t = a
        while (t < b) {
            val dt = Instant.ofEpochMilli(t).atZone(zone)
            val minute = dt.hour * 60 + dt.minute
            val isHoliday = dt.toLocalDate() in holidaysOf(dt.year)
            val day = DAY[dt.dayOfWeek.value - 1]
            val hits = rules.filter { (day in it.days || (isHoliday && "holiday" in it.days)) && minute >= it.from && minute < it.to }
            val pct = if (hits.isEmpty()) 0.0 else if (addUp) hits.sumOf { it.percent } else hits.maxOf { it.percent }
            raw += wage / 60 * (1 + pct / 100)
            min++
            t += 60000
        }
        val full = (s.eind - s.start) / 3600000.0
        val rule = breaks.firstOrNull { full > it.first }
        val share = min / ((s.eind - s.start) / 60000.0)
        val unpaid = if (rule != null) minOf(min.toDouble(), rule.second * share) else 0.0
        return if (min > 0) raw * (min - unpaid) / min else 0.0
    }

    /** Net (estimate) for all shifts in [from, to); null when the ratio is missing. */
    fun netto(shifts: List<Afspraak>, from: Long, to: Long): Double? {
        val r = ratio ?: return null
        val gross = shifts.sumOf { shift(it, from, to) }
        val allHours = if (wage > 0) gross / wage else 0.0
        val extra = extraHours.sumOf { allHours * (it / 100) * wage }
        val base = gross + extra
        val holidayPay = holidayPct?.let { base * it / 100 }
        val profit = profitPct?.let { base * it / 100 } ?: 0.0
        val total = base + fixed + (if (holidayInPeriod && holidayPay != null) holidayPay else 0.0) + profit
        return total * r
    }

    companion object {
        private fun number(o: JSONObject, key: String): Double? = if (o.has(key) && !o.isNull(key)) o.getDouble(key) else null

        private val OLD_DAY = mapOf("ma" to "mon", "di" to "tue", "wo" to "wed", "do" to "thu", "vr" to "fri", "za" to "sat", "zo" to "sun", "feestdag" to "holiday")
        private val OLD_HOLIDAY = mapOf("nieuwjaar" to "newyear", "pasen1" to "easter1", "pasen2" to "easter2", "koningsdag" to "kingsday",
            "bevrijdingsdag" to "liberationday", "hemelvaart" to "ascension", "pinksteren1" to "whit1", "pinksteren2" to "whit2",
            "kerst1" to "christmas1", "kerst2" to "christmas2")

        /** Old Dutch field names (ahLoon / _telefoon.loon, before dashboard 1.7.0) → English, like normalize() in ah.js. */
        fun normalize(l: JSONObject): JSONObject {
            if (l.has("hourlyWage") || !l.has("brutoUurloon")) return l
            fun objects(key: String): List<JSONObject> = l.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
            fun strings(a: JSONArray?, map: Map<String, String>) = JSONArray().apply { if (a != null) for (i in 0 until a.length()) put(map[a.optString(i)] ?: a.optString(i)) }
            fun copy(to: JSONObject, toKey: String, from: JSONObject, fromKey: String) { if (from.has(fromKey)) to.put(toKey, from.get(fromKey)) }
            val out = JSONObject()
            copy(out, "hourlyWage", l, "brutoUurloon")
            out.put("allowances", JSONArray(objects("toeslagen").map { r ->
                JSONObject().also { n -> copy(n, "name", r, "naam"); n.put("days", strings(r.optJSONArray("dagen"), OLD_DAY)); copy(n, "from", r, "van"); copy(n, "to", r, "tot"); copy(n, "percent", r, "procent") }
            }))
            out.put("multipleAllowances", if (l.optString("meerdereToeslagen") == "optellen") "add" else "highest")
            out.put("holidays", strings(l.optJSONArray("feestdagen"), OLD_HOLIDAY))
            out.put("breaks", JSONArray(objects("pauzes").map { p -> JSONObject().also { n -> copy(n, "longerThanHours", p, "langerDanUren"); copy(n, "minutes", p, "minuten") } }))
            copy(out, "holidayPayPercent", l, "vakantiegeldProcent")
            copy(out, "holidayPayPerPeriod", l, "vakantiegeldPerPeriode")
            copy(out, "profitSharePercent", l, "winstuitkeringProcent")
            out.put("paidHours", JSONArray(objects("uitbetaaldeUren").map { x -> JSONObject().also { n -> copy(n, "name", x, "naam"); copy(n, "percent", x, "procent") } }))
            out.put("fixedAllowances", JSONArray(objects("vasteToeslagen").map { f -> JSONObject().also { n -> copy(n, "name", f, "naam"); copy(n, "amount", f, "bedrag") } }))
            copy(out, "netRatio", l, "nettoVerhouding")
            return out
        }

        /** AH period of 4 weeks from the pay day ("Salaris" or "salary") in the calendar (like ah.js); otherwise this month. */
        fun periode(afspraken: List<Afspraak>, nu: Long = System.currentTimeMillis()): Pair<Long, Long> {
            val zone = ZoneId.systemDefault()
            fun dayMs(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
            fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val pay = afspraken.filter { it.titel.contains("salaris", true) || it.titel.contains("salary", true) }.map { dayOf(it.start) }.sorted()
            if (pay.isEmpty()) {
                val m = LocalDate.now().withDayOfMonth(1)
                return dayMs(m) to dayMs(m.plusMonths(1))
            }
            // First day after the period = Monday of the week before the pay day
            val endOf = { p: LocalDate -> p.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(7) }
            val anchor = endOf(pay[0]).minusDays(28)
            val days = java.time.temporal.ChronoUnit.DAYS.between(anchor, dayOf(nu))
            val k = Math.floorDiv(days, 28L)
            val start = anchor.plusDays(28 * k)
            return dayMs(start) to dayMs(start.plusDays(28))
        }
    }
}
