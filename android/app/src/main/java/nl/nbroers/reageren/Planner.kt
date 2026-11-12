package nl.nbroers.reageren

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.nbroers.reageren.ui.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private const val DAG_MS = 24 * 3600000L

/** Eén regel in de planner: een afspraak uit de agenda van de telefoon, een taak/blok uit de planner of een deadline. */
private data class Item(
    val titel: String, val start: Long?, val eind: Long?, val soort: String,   // ah | college | deadline | taak | blok | afspraak
    val sub: String? = null, val taak: PlannerData.Taak? = null, val kleur: Color? = null, val auto: Boolean = false,
)

// ================= Planner (fase D, sinds 0.7.0) =================
@Composable
fun PlannerScherm() {
    val context = LocalContext.current
    val k = LocalNH.current
    val nu = minuut()
    val wakker = Wakker.tel
    val scope = rememberCoroutineScope()
    val vandaag = LocalDate.now()
    var weergave by rememberSaveable { mutableStateOf("lijst") }
    var gekozen by rememberSaveable { mutableStateOf(vandaag.toString()) }
    val dag = LocalDate.parse(gekozen)

    // Bronnen
    var planner by remember { mutableStateOf<PlannerData.Alles?>(null) }
    var nietIngelogd by remember { mutableStateOf(false) }
    var ververs by remember { mutableIntStateOf(0) }
    LaunchedEffect(wakker, ververs, nu / (2 * 60000)) {
        withContext(Dispatchers.IO) {
            try { val a = PlannerData.haal(context); nietIngelogd = a == null; if (a != null) planner = a } catch (e: Exception) { }
        }
    }
    var afspraken by remember { mutableStateOf<List<Afspraak>>(emptyList()) }
    LaunchedEffect(wakker, nu / (5 * 60000)) {
        if (!Agenda.magLezen(context)) return@LaunchedEffect
        afspraken = withContext(Dispatchers.IO) { Agenda.tussen(context, Agenda.dagStart(vandaag.minusDays(7)), Agenda.dagStart(vandaag.plusDays(22))) }
    }
    var weer by remember { mutableStateOf<Weer?>(null) }
    LaunchedEffect(wakker, nu / (15 * 60000)) { weer = withContext(Dispatchers.IO) { WeerBron.haal() } }
    val (gedeeld, _) = rememberGedeeld(nu)

    // Alles per dag samenvoegen
    fun itemsOp(d: LocalDate): List<Item> {
        val van = Agenda.dagStart(d); val tot = van + DAG_MS
        val out = mutableListOf<Item>()
        afspraken.filter { !it.isCanvas && it.start < tot && it.eind > van }.forEach { a ->
            out += when {
                a.heleDag -> Item(a.titel, null, null, "afspraak", "hele dag")
                a.isAH -> Item("Appie", a.start, a.eind, "ah", vertrekZin(a, weer))
                a.isCollege -> Item(a.titel, a.start, a.eind, "college", a.plek.ifBlank { null })
                else -> Item(a.titel, a.start, a.eind, "afspraak", a.plek.ifBlank { null })
            }
        }
        planner?.taken?.filter { it.dag == d }?.forEach { t ->
            val s = t.start()
            out += Item(t.titel, s, s?.plus(t.duurMin * 60000L), if (t.blok) "blok" else "taak", null, t,
                t.vakKleur?.let { Color(it) }, t.auto)
        }
        gedeeld?.opdrachten?.filter { it.id !in gedeeld.ingeleverd && it.due >= van && it.due < tot }?.forEach { o ->
            val over = o.due - System.currentTimeMillis()
            out += Item("${o.titel} inleveren", o.due, null, "deadline",
                if (over > 0) "deadline · nog ${if (over >= DAG_MS) "${over / DAG_MS}d " else ""}${(over % DAG_MS) / 3600000}u" else "deadline · te laat")
        }
        return out.sortedWith(compareBy({ it.start ?: -1 }, { it.titel }))
    }

    Scherm {
        // ---- Kop met lijst / dag / week ----
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("week ${weekNummer(dag)}", style = NHTekst.label, color = k.muted)
                Text("Planner", style = NHTekst.titelL, color = k.ink)
            }
            listOf("lijst", "dag", "week").forEach { w ->
                val on = w == weergave
                Box(
                    Modifier.padding(start = 6.dp).size(48.dp).background(if (on) k.eva else Color.Transparent, CircleShape)
                        .border(1.dp, if (on) k.eva else k.line, CircleShape).clickable(role = Role.Tab) { weergave = w },
                    contentAlignment = Alignment.Center,
                ) { Text(w, style = NHTekst.label.copy(fontSize = 12.sp), color = if (on) k.onEva else k.ink) }
            }
        }

        // ---- Dagstrip ma t/m zo met puntjes per soort ----
        val ma = dag.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Row(Modifier.fillMaxWidth().border(1.dp, k.line).background(k.paperRaised)) {
            (0..6).forEach { i ->
                val d = ma.plusDays(i.toLong())
                val on = d == dag
                val soorten = itemsOp(d).map { it.soort }.toSet()
                Column(
                    Modifier.weight(1f).heightIn(min = 56.dp).background(if (on) k.eva else Color.Transparent).clickable(role = Role.Tab) { gekozen = d.toString() }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(listOf("ma", "di", "wo", "do", "vr", "za", "zo")[i], style = NHTekst.label.copy(fontSize = 11.sp), color = if (on) k.onEva else k.muted)
                    Text("${d.dayOfMonth}", style = NHTekst.bodyStrong.copy(fontWeight = if (d == vandaag) FontWeight.Black else FontWeight.Bold), color = if (on) k.onEva else k.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
                        listOf("ah" to k.ah, "college" to (if (on) k.onEva else k.eva), "deadline" to k.red, "taak" to (if (on) k.onEva else k.ink))
                            .filter { it.first in soorten }.forEach { (_, c) -> Box(Modifier.size(5.dp).background(c)) }
                    }
                }
                if (i < 6) Box(Modifier.width(1.dp).heightIn(min = 56.dp).background(k.lineSoft))
            }
        }

        if (nietIngelogd) Venster("planner.exe", "予定") {
            Text("Log in met je planner-account (Reageren › instellingen) voor je taken, blokken en gewoontes.", style = NHTekst.small, color = k.muted)
        }

        val zetAf: (PlannerData.Taak) -> Unit = { t ->
            planner = planner?.let { p -> PlannerData.Alles(p.taken.map { if (it.id == t.id) it.copy(af = !t.af) else it }, p.gewoontes) }
            scope.launch(Dispatchers.IO) { try { PlannerData.zetAf(context, t.id, !t.af) } catch (e: Exception) { } }
        }

        when (weergave) {
            "lijst" -> Venster("agenda.exe", "予定", zonderRand = true) {
                // Twee weken vanaf de gekozen dag, één blok per dag
                var eerste = true
                (0 until 14).forEach { i ->
                    val d = dag.plusDays(i.toLong())
                    val lijst = itemsOp(d).filter { d != vandaag || it.eind == null || it.eind > System.currentTimeMillis() || it.taak?.af == false }
                    if (lijst.isEmpty() && i > 0) return@forEach
                    if (!eerste) Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
                    eerste = false
                    Text(
                        (if (d == vandaag) "vandaag · " else "") + "${listOf("ma", "di", "wo", "do", "vr", "za", "zo")[d.dayOfWeek.value - 1]} ${d.dayOfMonth}",
                        style = NHTekst.label, color = k.muted, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp),
                    )
                    if (lijst.isEmpty()) Text("Niks gepland.", style = NHTekst.small, color = k.muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    lijst.forEachIndexed { j, it -> if (j > 0) Scheiding(); LijstRij(it, zetAf) }
                }
            }
            "dag" -> Venster("dag.exe", "一日", zonderRand = true) { Tijdlijn(itemsOp(dag), dag, zetAf) }
            else -> Venster("week.exe", "予定", zonderRand = true) { Week(ma) { d -> itemsOp(d) } }
        }

        // ---- Gewoontes ----
        planner?.gewoontes?.takeIf { it.isNotEmpty() }?.let { lijst ->
            Venster("gewoontes.exe", "習慣", zonderRand = true) {
                lijst.forEachIndexed { i, g ->
                    if (i > 0) Scheiding()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Checkbox) {
                            planner = planner?.let { p -> PlannerData.Alles(p.taken, p.gewoontes.map { if (it.id == g.id) it.copy(vandaag = !g.vandaag, reeks = if (g.vandaag) maxOf(0, g.reeks - 1) else g.reeks + 1) else it }) }
                            scope.launch(Dispatchers.IO) { try { PlannerData.zetGewoonte(context, g.id, !g.vandaag) } catch (e: Exception) { } }
                        }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Vakje(g.vandaag)
                        Spacer(Modifier.width(12.dp))
                        Text(g.naam, style = NHTekst.body.copy(textDecoration = if (g.vandaag) TextDecoration.LineThrough else null), color = if (g.vandaag) k.muted else k.ink, modifier = Modifier.weight(1f))
                        if (g.reeks >= 2) Text("${g.reeks} dagen", style = NHTekst.label, color = k.muted)
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Knop("Planner op de website") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://nbroers05.github.io/Dashboard/planner/"))) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// Regencheck onder een appie-dienst: vertrek (25 min ervoor), halverwege en einde
private fun vertrekZin(a: Afspraak, w: Weer?): String {
    val vertrek = a.start - 25 * 60000
    val midden = a.start + (a.eind - a.start) / 2
    val delen = listOf(vertrek, midden, a.eind).mapIndexedNotNull { i, t ->
        WeerBron.opMoment(w, t)?.let { "${if (i == 0) "vertrek " else ""}${Tijd.uur(t)} $it" }
    }
    return if (delen.isEmpty()) "${Tijd.uur(a.start)} - ${Tijd.uur(a.eind)}" else delen.joinToString(" · ")
}

@Composable
private fun Vakje(aan: Boolean) {
    val k = LocalNH.current
    Box(Modifier.size(22.dp).border(1.5.dp, k.line).background(if (aan) (if (k.donker) k.red else k.ink) else Color.Transparent), contentAlignment = Alignment.Center) {
        if (aan) Text("✓", style = NHTekst.small.copy(fontWeight = FontWeight.Bold), color = if (k.donker) k.onEva else k.paper)
    }
}

private fun kleur(it: Item, k: NHKleuren): Color = when (it.soort) {
    "ah" -> k.ah
    "college" -> k.eva
    "deadline" -> k.red
    else -> it.kleur ?: k.lineSoft
}

@Composable
private fun LijstRij(it: Item, zetAf: (PlannerData.Taak) -> Unit) {
    val k = LocalNH.current
    val t = it.taak
    val tijd = when {
        it.start == null -> ""
        it.eind != null && it.soort in setOf("ah", "afspraak") -> "${Tijd.uur(it.start)} - ${Tijd.uur(it.eind)}"
        else -> Tijd.uur(it.start)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).let { m -> if (t != null && !t.blok) m.clickable(role = Role.Checkbox) { zetAf(t) } else m }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t != null && !t.blok) { Vakje(t.af); Spacer(Modifier.width(10.dp)) }
        else if (it.soort != "deadline") {
            Box(Modifier.size(10.dp).background(kleur(it, k)).let { m -> if (it.auto) m.border(1.dp, k.line) else m }); Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(it.titel, style = NHTekst.body.copy(textDecoration = if (t?.af == true) TextDecoration.LineThrough else null),
                color = if (t?.af == true) k.muted else k.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            it.sub?.let { s -> Text(s, style = NHTekst.small, color = if (it.soort == "deadline") k.red else k.muted, maxLines = 2) }
        }
        Spacer(Modifier.width(8.dp))
        Text(tijd, style = NHTekst.label, color = if (it.soort == "deadline") k.red else k.muted)
    }
}

// ---------- Dag: tijdlijn (Standaardweek-blokken gestippeld, nu-lijn rood) ----------
@Composable
private fun Tijdlijn(items: List<Item>, dag: LocalDate, zetAf: (PlannerData.Taak) -> Unit) {
    val k = LocalNH.current
    val uur: Dp = 52.dp
    val vandaag = dag == LocalDate.now()
    val nuUur = if (vandaag) (System.currentTimeMillis() - Agenda.dagStart(dag)) / 3600000.0 else null
    val eerste = if (vandaag) (nuUur!!.toInt() - 1).coerceIn(6, 15) else 7
    val laatste = 24
    val van = Agenda.dagStart(dag)
    val metTijd = items.filter { it.start != null && (it.eind ?: (it.start + 30 * 60000)) > van + eerste * 3600000L }
    val zonderTijd = items.filter { it.start == null }
    zonderTijd.forEach { LijstRij(it, zetAf); Scheiding() }
    Box(Modifier.fillMaxWidth().height(uur * (laatste - eerste))) {
        (eerste until laatste).forEach { h ->
            Box(Modifier.offset(y = uur * (h - eerste)).fillMaxWidth().height(1.dp).background(k.lineSoft))
            Text("%02d".format(h), style = NHTekst.label, color = k.muted, modifier = Modifier.offset(x = 10.dp, y = uur * (h - eerste) + 3.dp))
        }
        metTijd.forEach { it ->
            val s = maxOf(it.start!!, van + eerste * 3600000L)
            val e = it.eind ?: (s + 30 * 60000)
            val top = (s - van) / 3600000f - eerste
            val hoog = ((e - s) / 3600000f).coerceAtLeast(0.4f)
            val (bg, fg) = when (it.soort) {
                "ah" -> k.ah to k.onAh
                "college" -> k.eva to k.onEva
                "deadline" -> Color.Transparent to k.red
                else -> k.paperRaised to k.ink
            }
            Column(
                Modifier.offset(x = 52.dp, y = uur * top).padding(end = 64.dp).fillMaxWidth().height(uur * hoog - 3.dp)
                    .background(bg)
                    .let { m ->
                        if (it.auto) m.drawBehind { drawRect(k.line, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))) }
                        else m.border(1.dp, if (it.soort == "deadline") k.red else if (it.soort in setOf("ah", "college")) bg else k.line)
                    }
                    .let { m -> it.taak?.takeIf { t -> !t.blok }?.let { t -> m.clickable { zetAf(t) } } ?: m }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(it.titel, style = NHTekst.bodyStrong.copy(fontSize = 14.sp, textDecoration = if (it.taak?.af == true) TextDecoration.LineThrough else null), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hoog >= 0.9f) Text(it.sub ?: "${Tijd.uur(s)}${it.eind?.let { e2 -> " - ${Tijd.uur(e2)}" } ?: ""}", style = NHTekst.small, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (nuUur != null && nuUur >= eerste) {
            Box(Modifier.offset(x = 46.dp, y = uur * (nuUur - eerste).toFloat()).fillMaxWidth().height(2.dp).background(k.red))
            Box(Modifier.offset(x = 42.dp, y = uur * (nuUur - eerste).toFloat() - 4.dp).size(10.dp).background(k.red, CircleShape))
        }
    }
}

// ---------- Week: zeven kolommen 08–24, alleen namen ----------
@Composable
private fun Week(ma: LocalDate, itemsOp: (LocalDate) -> List<Item>) {
    val k = LocalNH.current
    val uur: Dp = 30.dp
    val eerste = 8; val laatste = 24
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(28.dp)) {
            Spacer(Modifier.height(28.dp))
            (eerste until laatste).forEach { h -> Box(Modifier.height(uur)) { Text("%02d".format(h), style = NHTekst.label.copy(fontSize = 10.sp), color = k.muted, modifier = Modifier.padding(start = 4.dp)) } }
        }
        (0..6).forEach { i ->
            val d = ma.plusDays(i.toLong())
            val van = Agenda.dagStart(d)
            val isVandaag = d == LocalDate.now()
            Column(Modifier.weight(1f).border(0.5.dp, k.lineSoft)) {
                Box(Modifier.fillMaxWidth().height(28.dp).background(if (isVandaag) k.eva else Color.Transparent), contentAlignment = Alignment.Center) {
                    Text("${listOf("ma", "di", "wo", "do", "vr", "za", "zo")[i]} ${d.dayOfMonth}", style = NHTekst.label.copy(fontSize = 10.sp), color = if (isVandaag) k.onEva else k.muted)
                }
                Box(Modifier.fillMaxWidth().height(uur * (laatste - eerste)).background(if (isVandaag) k.sky.copy(alpha = 0.18f) else Color.Transparent)) {
                    itemsOp(d).filter { it.start != null }.forEach { it ->
                        val s = maxOf(it.start!!, van + eerste * 3600000L)
                        val e = it.eind ?: (s + 30 * 60000)
                        if (e <= s) return@forEach
                        val bg = when (it.soort) { "ah" -> k.ah; "college" -> k.eva; "deadline" -> k.red; else -> k.paperRaised }
                        val fg = when (it.soort) { "ah" -> k.onAh; "college", "deadline" -> k.onEva; else -> k.ink }
                        Box(
                            Modifier.offset(y = uur * ((s - van) / 3600000f - eerste)).padding(horizontal = 1.dp).fillMaxWidth()
                                .height(uur * ((e - s) / 3600000f).coerceAtLeast(0.5f) - 1.dp).background(bg).border(0.5.dp, k.line),
                        ) { Text(it.titel, style = NHTekst.label.copy(fontSize = 9.sp, lineHeight = 10.sp), color = fg, maxLines = 3, modifier = Modifier.padding(2.dp)) }
                    }
                }
            }
        }
    }
}
