package nl.nbroers.reageren

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.nbroers.reageren.ui.*
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Summaries from the archive (data/aggregates/<group>.json → daily). */
private class Cijfers(val spotify: JSONObject, val buiten: JSONObject, val pc: JSONObject, val stappen: JSONObject)

// ================= Data (phase C, since 0.6.0) =================
@Composable
fun DataScherm() {
    val context = LocalContext.current
    val k = LocalNH.current
    val nu = minuut()
    val wakker = Wakker.tel
    var ververs by remember { mutableIntStateOf(0) }
    val archief = remember(wakker, ververs) { Archief.heeftToken(context) }

    var cijfers by remember { mutableStateOf<Cijfers?>(null) }
    var memory by remember { mutableStateOf<JSONObject?>(null) }
    var fout by remember { mutableStateOf<String?>(null) }
    var vandaagCheckins by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(archief, wakker, nu / (10 * 60000)) {
        if (!archief) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                Archief.verstuur(context)
                fun daily(g: String, key: String) = Archief.lees(context, "data/aggregates/$g.json")?.let { JSONObject(it).optJSONObject("daily")?.optJSONObject(key) } ?: JSONObject()
                cijfers = Cijfers(daily("spotify", "minutes"), daily("places", "away_min"), daily("computer", "pc_active_min"), daily("body", "steps"))
                val mm = "%02d".format(LocalDate.now().monthValue)
                memory = Archief.lees(context, "data/aggregates/memory/$mm.json")?.let { JSONObject(it).optJSONObject("days") }
                vandaagCheckins = Archief.checkinsVandaag(context)
                fout = null
            } catch (e: NeedLogin) {
                fout = "The archive token no longer works. Link again with your passphrase."
            } catch (e: Exception) {
                fout = "Archive not reachable, I'll try again shortly."
            }
        }
    }

    Scherm {
        Kop("this week", "Data")
        if (!archief) {
            Wachtzin(alleenArchief = true) { ververs++ }
            return@Scherm
        }
        fout?.let { Text(it, style = NHTekst.small, color = k.red) }

        CheckinVenster(vandaagCheckins) { vandaagCheckins = (vandaagCheckins ?: 0) + 1 }
        CijfersVenster(cijfers)
        MemoryVenster(memory)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Knop("All charts") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://nbroers05.github.io/Dashboard/#mydata"))) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- Check-in: the same four questions as on the PC (mood, energy, focus, stress) ----------
// No values or charts of earlier check-ins (agreed since 0.44.17: hypotheses first, only then look).
private val VRAGEN = listOf(
    Triple("mood", "How are you?", "bad" to "great"),
    Triple("energy", "Energy", "empty" to "full"),
    Triple("focus", "Focus", "gone" to "sharp"),
    Triple("stress", "Stress", "calm" to "a lot"),
)

@Composable
private fun CheckinVenster(vandaag: Int?, onOpgeslagen: () -> Unit) {
    val context = LocalContext.current
    val k = LocalNH.current
    val scope = rememberCoroutineScope()
    var keuzes by remember { mutableStateOf(mapOf<String, Int>()) }
    var notitie by remember { mutableStateOf("") }
    var melding by remember { mutableStateOf<String?>(null) }
    Venster("check-in.exe", "気分") {
        VRAGEN.forEach { (key, vraag, uiteinden) ->
            Text(vraag, style = NHTekst.bodyStrong, color = k.ink, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().border(1.dp, k.line)) {
                (1..5).forEach { n ->
                    val on = keuzes[key] == n
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp).background(if (on) k.eva else Color.Transparent)
                            .clickable(role = Role.RadioButton) { keuzes = keuzes + (key to n); melding = null },
                        contentAlignment = Alignment.Center,
                    ) { Text("$n", style = NHTekst.bodyStrong.copy(fontSize = 16.sp), color = if (on) k.onEva else k.ink) }
                    if (n < 5) Box(Modifier.width(1.dp).heightIn(min = 44.dp).background(k.lineSoft))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(uiteinden.first, style = NHTekst.small, color = k.muted, modifier = Modifier.weight(1f))
                Text(uiteinden.second, style = NHTekst.small, color = k.muted)
            }
        }
        Text("Note (optional)", style = NHTekst.label, color = k.muted, modifier = Modifier.padding(top = 4.dp))
        BasicTextField(
            notitie, { notitie = it.take(500) }, singleLine = true,
            textStyle = NHTekst.body.copy(color = k.ink), cursorBrush = SolidColor(k.ink),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).border(1.dp, k.line).padding(horizontal = 8.dp, vertical = 10.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(melding ?: vandaag?.let { "today ${it}×" } ?: "", style = NHTekst.small, color = k.muted, modifier = Modifier.weight(1f))
            Knop("Save", hoofd = true) {
                if (keuzes.size < VRAGEN.size) { melding = "Fill in all four."; return@Knop }
                val waarden = keuzes
                val tekst = notitie
                keuzes = emptyMap(); notitie = ""
                onOpgeslagen()
                scope.launch {
                    val gelukt = withContext(Dispatchers.IO) { Archief.checkin(context, waarden, tekst) }
                    melding = if (gelukt) "Saved." else "Kept; goes to the archive as soon as it can."
                }
            }
        }
    }
}

// ---------- Key numbers ----------
@Composable
private fun CijfersVenster(c: Cijfers?) {
    val context = LocalContext.current
    val k = LocalNH.current
    val vandaag = LocalDate.now()
    val laatste7 = (6 downTo 0).map { vandaag.minusDays(it.toLong()).toString() }
    fun som(o: JSONObject?, dagen: List<String>) = dagen.sumOf { o?.optDouble(it, 0.0)?.takeIf { v -> !v.isNaN() } ?: 0.0 }
    fun laatste(o: JSONObject?): Pair<String, Double>? = o?.keys()?.asSequence()?.toList()?.maxOrNull()?.let { it to o.optDouble(it) }
    fun minuten(m: Double) = if (m >= 60) "${(m / 60).toInt()}h ${"%02d".format((m % 60).toInt())}m" else "${m.toInt()} min"
    fun wanneer(dag: String): String {
        val d = LocalDate.parse(dag)
        return when (d) { vandaag -> "today"; vandaag.minusDays(1) -> "yesterday"; else -> Tijd.dagKort(Agenda.dagStart(d) + 12 * 3600000L) }
    }

    // Appie this month from the phone's calendar
    var ahUren by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        ahUren = withContext(Dispatchers.IO) {
            if (!Agenda.magLezen(context)) null else {
                val m0 = vandaag.withDayOfMonth(1)
                Agenda.tussen(context, Agenda.dagStart(m0), Agenda.dagStart(m0.plusMonths(1))).filter { it.isAH }.sumOf { it.eind - it.start } / 3600000.0
            }
        }
    }

    Venster("numbers.exe", "記録") {
        if (c == null) { Text("loading_", style = NHTekst.label, color = k.muted); return@Venster }
        val buiten = laatste(c.buiten)
        val stappen = laatste(c.stappen)
        Column(Modifier.fillMaxWidth().border(1.dp, k.lineSoft)) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Tegel("spotify", minuten(som(c.spotify, laatste7)), "this week", Modifier.weight(1f))
                Box(Modifier.width(1.dp).fillMaxHeight().background(k.lineSoft))
                Tegel("appie", ahUren?.let { "${"%.1f".format(NL, it).removeSuffix(".0")} h" } ?: "–", vandaag.month.getDisplayName(java.time.format.TextStyle.FULL, NL), Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.lineSoft))
            Row(Modifier.height(IntrinsicSize.Min)) {
                Tegel("outside", buiten?.let { minuten(it.second) } ?: "–", buiten?.let { wanneer(it.first) } ?: "", Modifier.weight(1f))
                Box(Modifier.width(1.dp).fillMaxHeight().background(k.lineSoft))
                Tegel("steps", stappen?.let { "%,d".format(NL, it.second.toLong()) } ?: "–", stappen?.let { wanneer(it.first) } ?: "", Modifier.weight(1f))
            }
        }
        // PC hours this week (Mon to Sun) as bars
        Text("PC hours this week", style = NHTekst.small, color = k.muted, modifier = Modifier.padding(top = 8.dp))
        val ma = vandaag.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val week = (0..6).map { ma.plusDays(it.toLong()) }
        val waarden = week.map { c.pc.optDouble(it.toString(), 0.0).takeIf { v -> !v.isNaN() } ?: 0.0 }
        val max = maxOf(60.0, waarden.max())
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            week.forEachIndexed { i, d ->
                Box(Modifier.weight(1f).fillMaxHeight((waarden[i] / max).toFloat().coerceIn(0.04f, 1f)).background(if (d == vandaag) k.eva else k.sky))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { Text(it, style = NHTekst.label.copy(fontSize = 11.sp), color = k.muted, modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Tegel(label: String, waarde: String, sub: String, modifier: Modifier) {
    val k = LocalNH.current
    Column(modifier.padding(10.dp)) {
        Text(label, style = NHTekst.label, color = k.muted)
        Text(waarde, style = NHTekst.getal.copy(fontSize = 22.sp, lineHeight = 26.sp), color = k.ink, maxLines = 1)
        Text(sub, style = NHTekst.small, color = k.muted, maxLines = 1)
    }
}

// ---------- Memory Lane: today in earlier years; tap = next year ----------
@Composable
private fun MemoryVenster(days: JSONObject?) {
    val k = LocalNH.current
    val vandaag = LocalDate.now()
    val md = "%02d-%02d".format(vandaag.monthValue, vandaag.dayOfMonth)
    val perJaar = days?.optJSONObject(md)?.let { o -> o.keys().asSequence().mapNotNull { y -> y.toIntOrNull()?.takeIf { it < vandaag.year }?.let { it to o.getJSONObject(y) } }.toList() } ?: emptyList()
    // Richest year first (like memory.js: films and calendar count more)
    fun score(d: JSONObject) = d.keys().asSequence().count { it != "w" } + (if (d.has("f")) 2 else 0) + (if (d.has("ag")) 1 else 0)
    val jaren = perJaar.filter { (_, d) -> d.keys().asSequence().any { it != "w" } }.sortedWith(compareByDescending<Pair<Int, JSONObject>> { score(it.second) }.thenByDescending { it.first })
    var keuze by remember(md) { mutableIntStateOf(0) }
    if (days == null) return
    Venster("memories.exe", "思い出", Modifier.clickable(role = Role.Button, enabled = jaren.size > 1) { keuze = (keuze + 1) % jaren.size }) {
        if (jaren.isEmpty()) { Text("Nothing from this day in earlier years yet.", style = NHTekst.small, color = k.muted); return@Venster }
        val (jaar, d) = jaren[keuze % jaren.size]
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("${vandaag.dayOfMonth} ${vandaag.month.getDisplayName(java.time.format.TextStyle.FULL, NL)}", style = NHTekst.label, color = k.muted)
                regels(d).take(3).forEach { Text(it, style = NHTekst.small, color = k.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text("$jaar", style = NHTekst.titelL, color = k.red)
        }
        if (jaren.size > 1) Text("tap for ${jaren.size - 1} other year${if (jaren.size == 2) "" else "s"}", style = NHTekst.label.copy(fontSize = 11.sp), color = k.muted)
    }
}

// Same parts as memory.js, without emoji
private fun regels(d: JSONObject): List<String> {
    val out = mutableListOf<String>()
    d.optJSONArray("ag")?.let { ag ->
        (0 until ag.length()).mapNotNull { ag.optJSONArray(it) }.forEach { a ->
            out += when (a.optString(0)) { "ah" -> a.optString(1).replace("AH-dienst", "Appie").replace("AH shift", "Appie"); "tentamen" -> "Exam: ${a.optString(1)}"; else -> a.optString(1) }
        }
    }
    d.optJSONArray("f")?.let { f ->
        if (f.length() == 1) f.optJSONArray(0)?.let { x -> out += "Film: ${x.optString(0)}${if (!x.isNull(2)) " " + "★".repeat(x.optDouble(2).toInt()) + (if (x.optDouble(2) % 1 >= 0.5) "½" else "") else ""}" }
        else if (f.length() > 1) out += "${f.length()} films"
    }
    d.optJSONArray("out")?.let { o -> if (!o.isNull(0)) out += "${"%.1f".format(NL, o.optDouble(0))} h outside" }
    d.optJSONArray("yt")?.let { y -> out += "${y.optInt(0)} videos${if (y.length() > 1 && !y.isNull(1)) " · mostly ${y.optString(1)}" else ""}" }
    if (d.has("st")) out += "%,d steps".format(NL, d.optLong("st"))
    if (d.has("ph")) d.optInt("ph").let { out += "$it photo${if (it == 1) "" else "s"}" }
    d.optJSONArray("w")?.let { w -> if (!w.isNull(0)) out += "${w.optDouble(0).toInt()}°${if (w.optDouble(1, 0.0) >= 1) ", rain" else ""}" }
    return out
}
