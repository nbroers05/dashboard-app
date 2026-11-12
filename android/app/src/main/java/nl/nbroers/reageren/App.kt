package nl.nbroers.reageren

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.nbroers.reageren.ui.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

// Tabjes onderin (mockup "telefoon-app", tweede versie)
private const val VANDAAG = 0
private const val PLANNER = 1
private const val MEDIA = 2
private const val DATA = 3
private const val REAGEREN = 4

// ---------- De app: vijf tabjes, minispeler erboven (niet op Media), instellingen en to-do als eigen scherm ----------
@Composable
fun App() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(VANDAAG) }
    var instellingen by rememberSaveable { mutableStateOf(false) }
    var todo by rememberSaveable { mutableStateOf(false) }

    // Wat er speelt: één keer voor de hele app (minispeler, Vandaag, Media)
    var speelt by remember { mutableStateOf<Speelt?>(null) }
    var mediaZonderToegang by remember { mutableStateOf(false) }
    val wakker = Wakker.tel
    DisposableEffect(wakker) {
        val media = Media(context) { speelt = it }
        media.start()
        mediaZonderToegang = media.geenToegang
        onDispose { media.stop() }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                instellingen -> { BackHandler { instellingen = false }; InstellingenScherm(onTerug = { instellingen = false }) }
                todo -> { BackHandler { todo = false }; TodoScherm(onTerug = { todo = false }) }
                else -> when (tab) {
                    VANDAAG -> VandaagScherm(
                        naar = { tab = it }, naarTodo = { todo = true }, naarInstellingen = { instellingen = true },
                    )
                    PLANNER -> PlannerScherm()
                    MEDIA -> MediaScherm(speelt, mediaZonderToegang)
                    DATA -> DataScherm()
                    else -> ReagerenScherm(naarInstellingen = { instellingen = true })
                }
            }
        }
        if (!instellingen && !todo) {
            val s = speelt
            if (s != null && tab != MEDIA) Minispeler(s) { tab = MEDIA }
            Onderbalk(listOf("vandaag", "planner", "media", "data", "reageren"), tab) { tab = it }
        }
    }
}

// ---------- Minispeler ----------
@Composable
private fun Minispeler(s: Speelt, onTik: () -> Unit) {
    val k = LocalNH.current
    Column(Modifier.fillMaxWidth().background(k.paperRaised)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (k.donker) k.lineSoft else k.line))
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onTik).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Hoes(s, 40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.titel, style = NHTekst.bodyStrong.copy(fontFamily = Display, fontSize = 15.sp), color = k.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(s.wie.substringBefore(" · "), s.app.lowercase()).filter { it.isNotBlank() }.joinToString(" · "), style = NHTekst.small, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.size(44.dp).border(1.dp, k.line).clickable(role = Role.Button) {
                    if (s.speelt) s.controller.transportControls.pause() else s.controller.transportControls.play()
                },
                contentAlignment = Alignment.Center,
            ) { Text(if (s.speelt) "❚❚" else "▶", style = NHTekst.small.copy(fontWeight = FontWeight.Bold), color = k.ink) }
        }
    }
}

@Composable
internal fun Hoes(s: Speelt, maat: Int) {
    val k = LocalNH.current
    Box(Modifier.size(maat.dp).background(k.eva).border(1.dp, k.line)) {
        s.hoes?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

// ---------- Gedeeld met het dashboard (Supabase van het dashboard) ----------
@Composable
internal fun rememberGedeeld(nu: Long): Pair<Gedeeld?, (Gedeeld) -> Unit> {
    val context = LocalContext.current
    val wakker = Wakker.tel
    var g by remember { mutableStateOf<Gedeeld?>(null) }
    LaunchedEffect(wakker, nu / (2 * 60000)) {
        withContext(Dispatchers.IO) { try { Dash.haal(context)?.let { g = it } } catch (e: Exception) { } }
    }
    return g to { g = it }
}

private fun groet(): String {
    val h = LocalTime.now().hour
    return when { h < 6 -> "Goedenacht"; h < 12 -> "Goedemorgen"; h < 18 -> "Goedemiddag"; else -> "Goedenavond" }
}

// ================= Vandaag =================
@Composable
fun VandaagScherm(naar: (Int) -> Unit, naarTodo: () -> Unit, naarInstellingen: () -> Unit) {
    val context = LocalContext.current
    val k = LocalNH.current
    val nu = minuut()
    val wakker = Wakker.tel
    val vandaag = LocalDate.now()
    val scope = rememberCoroutineScope()

    // Agenda van de telefoon: 5 weken terug (AH-periode) tot 5 weken vooruit
    var magAgenda by remember { mutableStateOf(Agenda.magLezen(context)) }
    val vraag = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { magAgenda = it }
    var afspraken by remember { mutableStateOf<List<Afspraak>>(emptyList()) }
    LaunchedEffect(magAgenda, wakker, nu / (5 * 60000)) {
        if (!magAgenda) return@LaunchedEffect
        val van = Agenda.dagStart(vandaag.minusDays(35))
        afspraken = withContext(Dispatchers.IO) { Agenda.tussen(context, van, Agenda.dagStart(vandaag.plusDays(35))) }
    }
    var weer by remember { mutableStateOf<Weer?>(null) }
    LaunchedEffect(wakker, nu / (10 * 60000)) { weer = withContext(Dispatchers.IO) { WeerBron.haal() } }
    var wachten by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(wakker, nu / (5 * 60000)) {
        wachten = withContext(Dispatchers.IO) { try { Reageren.lijst(context)?.size } catch (e: Exception) { null } }
    }
    val (gedeeld, zetGedeeld) = rememberGedeeld(nu)
    val gekoppeld = remember(wakker) { Dash.gekoppeld(context) }

    val dagStart = Agenda.dagStart(vandaag)
    val morgen = dagStart + 24 * 3600000L
    val ah = afspraken.filter { it.isAH }
    val volgende = ah.firstOrNull { it.eind > nu }
    // Open Canvas-opdrachten: uit de stand van het dashboard (met id's), zonder wat al is afgevinkt
    val opdrachten = gedeeld?.opdrachten?.filter { it.id !in gedeeld.ingeleverd && it.due > nu - 7 * 24 * 3600000L }?.sortedBy { it.due } ?: emptyList()

    Scherm {
        // ---- Kop: datum, groet, klok ----
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("${vandaag.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, NL)} ${vandaag.dayOfMonth} ${maandKort(vandaag)} · week ${weekNummer(vandaag)}", style = NHTekst.label, color = k.muted)
                Text(groet(), style = NHTekst.titelL, color = k.ink, maxLines = 1)
            }
            Text(Tijd.uur(nu), style = NHTekst.label.copy(fontSize = 40.sp, lineHeight = 42.sp), color = k.ink)
        }

        // ---- Dagregel als chips ----
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (volgende != null && volgende.start < morgen) Chip(if (volgende.start <= nu) "appie tot ${Tijd.uur(volgende.eind)}" else "appie ${Tijd.uur(volgende.start)}", k.ah, k.onAh, k.ah)
            else if (volgende == null || volgende.start >= morgen) {
                val morgenEerste = afspraken.firstOrNull { !it.isCanvas && !it.heleDag && it.start >= morgen && it.start < morgen + 24 * 3600000L }
                if (LocalTime.now().hour >= 18 && morgenEerste != null) Chip("morgen: ${if (morgenEerste.isAH) "appie" else morgenEerste.titel.lowercase()} ${Tijd.uur(morgenEerste.start)}", Color.Transparent, k.ink, k.line)
            }
            weer?.let { w -> WeerBron.zin(w).takeIf { it.isNotBlank() }?.let { Chip(it.lowercase().replace(", komende 2 uur", ""), Color.Transparent, k.ink, k.line) } }
            opdrachten.firstOrNull { it.due > nu && it.due - nu < 3 * 24 * 3600000L }?.let {
                Chip("canvas: ${if (it.due < morgen) Tijd.uur(it.due) else Tijd.dagKort(it.due)}", Color.Transparent, k.red, k.red)
            }
        }

        // ---- weer.exe + appie.exe ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            Venster("weer.exe", "天気", Modifier.weight(1f).fillMaxHeight()) {
                val w = weer
                if (w == null) Text("weer ophalen_", style = NHTekst.label, color = k.muted)
                else {
                    Text(w.temp?.let { "%.0f°".format(it) } ?: "–", style = NHTekst.getal, color = k.ink)
                    Text(WeerBron.zin(w), style = NHTekst.small, color = k.ink)
                    RegenStaafjes(w)
                }
            }
            Venster("appie.exe", "仕事", Modifier.weight(1f).fillMaxHeight()) {
                if (!magAgenda) Text("Agenda nodig", style = NHTekst.small, color = k.muted)
                else {
                    val vandaagDienst = volgende != null && volgende.start < morgen
                    Text(if (vandaagDienst) "vandaag" else "volgende dienst", style = NHTekst.small, color = k.muted)
                    Text(
                        when {
                            volgende == null -> "geen gepland"
                            vandaagDienst -> "${Tijd.uur(volgende.start)} - ${Tijd.uur(volgende.eind)}"
                            else -> "${Tijd.dagKort(volgende.start)} ${Tijd.uur(volgende.start)}"
                        },
                        style = NHTekst.bodyStrong, color = k.ink,
                    )
                    val weekVan = Agenda.dagStart(Agenda.weekStart())
                    val uren = ah.filter { it.start >= weekVan && it.start < weekVan + 7 * 24 * 3600000L }.sumOf { it.eind - it.start } / 3600000.0
                    val loon = gedeeld?.loon?.let { Loon(it) }?.takeIf { it.bruikbaar }
                    val netto = loon?.let { l -> Loon.periode(afspraken, nu).let { (a, b) -> l.netto(ah, a, b) } }
                    Text("${"%.1f".format(NL, uren).removeSuffix(",0")} u${if (netto != null) " · ≈ € ${"%.0f".format(netto)}" else ""}", style = NHTekst.small, color = k.muted)
                }
            }
        }

        if (!magAgenda) Venster("agenda.exe", "予定") {
            Text("Laat de app de agenda van je telefoon lezen. Dan staan je diensten hier zonder iets te koppelen.", style = NHTekst.small, color = k.muted)
            Knop("Agenda van de telefoon gebruiken", hoofd = true) { vraag.launch(Manifest.permission.READ_CALENDAR) }
        }

        // ---- canvas.exe ----
        Venster("canvas.exe", "締切", zonderRand = true) {
            when {
                !gekoppeld -> Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Koppel het dashboard (je wachtzin, één keer) voor je Canvas-deadlines met vinkjes.", style = NHTekst.small, color = k.muted)
                    Knop("Naar instellingen", onClick = naarInstellingen)
                }
                gedeeld == null -> Text("ophalen_", style = NHTekst.label, color = k.muted, modifier = Modifier.padding(12.dp))
                opdrachten.isEmpty() -> Text("Niks meer in te leveren.", style = NHTekst.body, color = k.muted, modifier = Modifier.padding(12.dp))
                else -> opdrachten.take(4).forEachIndexed { i, o ->
                    if (i > 0) Scheiding()
                    CanvasRij(o, nu) {
                        // Meteen weg uit de lijst; daarna naar Supabase (zelfde vinkjes als op de pc)
                        val g = gedeeld
                        zetGedeeld(Gedeeld(g.stand, g.loon, g.todo, g.ingeleverd + o.id))
                        scope.launch(Dispatchers.IO) { try { Dash.zetIngeleverd(context, o.id, true) } catch (e: Exception) { } }
                    }
                }
            }
        }

        // ---- meldingen.exe ----
        Venster("meldingen.exe", "通知", zonderRand = true) {
            val regels = mutableListOf<@Composable () -> Unit>()
            wachten?.let { n -> regels += { Rij("$n nog reageren", "reageren", onClick = { naar(REAGEREN) }) } }
            gedeeld?.series?.filter { it.nieuw > 0 }?.takeIf { it.isNotEmpty() }?.let { nieuw ->
                val n = nieuw.sumOf { it.nieuw }
                regels += { Rij("Episodes Left", "media", sub = if (n == 1) "1 nieuwe aflevering" else "$n nieuwe afleveringen", onClick = { naar(MEDIA) }) }
            }
            gedeeld?.mail?.takeIf { it > 0 }?.let { n ->
                regels += { Rij("Mail", "gmail", sub = "$n ongelezen${gedeeld.mailVan?.let { " · $it" } ?: ""}", onClick = { openGmail(context) }) }
            }
            if (regels.isEmpty()) Text("Niks aan de hand.", style = NHTekst.body, color = k.muted, modifier = Modifier.padding(12.dp))
            regels.forEachIndexed { i, r -> if (i > 0) Scheiding(); r() }
        }

        // ---- to-do.exe + check-in.exe ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            Venster("to-do.exe", "用事", Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button, onClick = naarTodo)) {
                Text(if (gedeeld == null) "–" else "${gedeeld.todoOpen}", style = NHTekst.getal, color = k.ink)
                Text("open · tik om te zien", style = NHTekst.small, color = k.muted)
            }
            Venster("check-in.exe", "気分", Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Button) { naar(DATA) }) {
                Text(gedeeld?.checkinsVandaag?.let { "$it×" } ?: "–", style = NHTekst.getal, color = k.ink)
                Text("vandaag · nu invullen", style = NHTekst.small, color = k.muted)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Chip(tekst: String, bg: Color, fg: Color, rand: Color) {
    Box(
        Modifier.heightIn(min = 44.dp).background(bg, RoundedCornerShape(50)).border(1.dp, rand, RoundedCornerShape(50)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(tekst, style = NHTekst.label, color = fg, maxLines = 1) }
}

// Canvas-rij: vinkje van 28 dp, de hele rij is het raakvlak
@Composable
private fun CanvasRij(o: Gedeeld.Opdracht, nu: Long, onAf: () -> Unit) {
    val k = LocalNH.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Checkbox, onClick = onAf).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).border(1.5.dp, k.line))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(o.titel, style = NHTekst.body, color = k.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (o.vak.isNotBlank()) Text(o.vak, style = NHTekst.small, color = k.muted, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        val laat = o.due < nu
        Text(if (laat) "te laat" else "${Tijd.dagKort(o.due)} ${Tijd.uur(o.due)}", style = NHTekst.label, color = if (laat || o.due - nu < 24 * 3600000L) k.red else k.muted)
    }
}

private fun openGmail(context: Context) {
    val app = context.packageManager.getLaunchIntentForPackage("com.google.android.gm")
    context.startActivity(app ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com/")))
}

// ================= To-do (zelfde lijst als op de pc) =================
@Composable
fun TodoScherm(onTerug: () -> Unit) {
    val context = LocalContext.current
    val k = LocalNH.current
    val scope = rememberCoroutineScope()
    val nu = minuut()
    val (gedeeld, _) = rememberGedeeld(nu)
    var lijst by remember { mutableStateOf<JSONArray?>(null) }
    LaunchedEffect(gedeeld) { if (lijst == null && gedeeld != null) lijst = gedeeld.todo ?: JSONArray() }
    var nieuw by remember { mutableStateOf("") }
    fun bewaar(a: JSONArray) {
        lijst = a
        scope.launch(Dispatchers.IO) { try { Dash.zetTodo(context, a) } catch (e: Exception) { } }
    }

    Scherm {
        Kop("to-do", "Klusjes") {
            Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onTerug).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text("terug", style = NHTekst.label, color = k.muted)
            }
        }
        Venster("to-do.exe", "用事", zonderRand = true) {
            val l = lijst
            if (!Dash.gekoppeld(context)) Text("Koppel eerst het dashboard in de instellingen (je wachtzin).", style = NHTekst.small, color = k.muted, modifier = Modifier.padding(12.dp))
            else if (l == null) Text("ophalen_", style = NHTekst.label, color = k.muted, modifier = Modifier.padding(12.dp))
            else {
                if (l.length() == 0) Text("Niks open.", style = NHTekst.body, color = k.muted, modifier = Modifier.padding(12.dp))
                for (i in 0 until l.length()) {
                    val item = l.getJSONObject(i)
                    val af = item.optBoolean("done")
                    if (i > 0) Scheiding()
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Checkbox) {
                        val kopie = JSONArray(l.toString()); kopie.getJSONObject(i).put("done", !af); bewaar(kopie)
                    }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).border(1.5.dp, k.line).background(if (af) (if (k.donker) k.red else k.ink) else Color.Transparent), contentAlignment = Alignment.Center) {
                            if (af) Text("✓", style = NHTekst.small.copy(fontWeight = FontWeight.Bold), color = if (k.donker) k.onEva else k.paper)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(item.optString("text"), style = NHTekst.body.copy(textDecoration = if (af) TextDecoration.LineThrough else null), color = if (af) k.muted else k.ink, modifier = Modifier.weight(1f))
                        Box(Modifier.size(48.dp).clickable(role = Role.Button) {
                            val kopie = JSONArray(); for (j in 0 until l.length()) if (j != i) kopie.put(l.getJSONObject(j)); bewaar(kopie)
                        }, contentAlignment = Alignment.Center) { Text("×", style = NHTekst.titelM, color = k.muted) }
                    }
                }
                Scheiding()
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(
                        nieuw, { nieuw = it.take(200) }, singleLine = true,
                        textStyle = NHTekst.body.copy(color = k.ink), cursorBrush = SolidColor(k.ink),
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).border(1.dp, k.line).padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                    Knop("+", hoofd = true) {
                        val t = nieuw.trim()
                        if (t.isNotEmpty()) {
                            val kopie = JSONArray(l.toString())
                            kopie.put(JSONObject().put("id", java.lang.Long.toString(System.currentTimeMillis(), 36) + (1000..9999).random()).put("text", t).put("done", false))
                            bewaar(kopie); nieuw = ""
                        }
                    }
                }
            }
        }
    }
}

