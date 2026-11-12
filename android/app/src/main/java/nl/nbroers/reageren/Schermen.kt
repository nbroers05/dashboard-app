package nl.nbroers.reageren

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.nbroers.reageren.ui.*
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val NL = Locale("nl", "NL")

/** Wordt opgehoogd als de app weer in beeld komt (MainActivity.onResume): schermen halen dan opnieuw op. */
object Wakker { var tel by mutableIntStateOf(0) }

// ---------- De app: drie tabjes onderin, instellingen als eigen scherm ----------
@Composable
fun App() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var instellingen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (instellingen) {
                BackHandler { instellingen = false }
                InstellingenScherm(onTerug = { instellingen = false })
            } else when (tab) {
                0 -> VandaagScherm(naarReageren = { tab = 2 })
                1 -> PlannerScherm()
                else -> ReagerenScherm(naarInstellingen = { instellingen = true })
            }
        }
        if (!instellingen) Onderbalk(listOf("vandaag", "planner", "reageren"), tab) { tab = it }
    }
}

@Composable
private fun Scherm(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Tikt elke minuut, zodat tijden en "nu" kloppen. */
@Composable
private fun minuut(): Long {
    var nu by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60000 - nu % 60000); nu = System.currentTimeMillis() } }
    return nu
}

private fun weekNummer(d: LocalDate) = d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
private fun dagNaam(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, NL).replaceFirstChar { it.uppercase() }
private fun maandKort(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, NL).trimEnd('.')

// ================= Vandaag =================
@Composable
fun VandaagScherm(naarReageren: () -> Unit) {
    val context = LocalContext.current
    val k = LocalNH.current
    val nu = minuut()
    val wakker = Wakker.tel
    val vandaag = LocalDate.now()

    // Wat er speelt
    var speelt by remember { mutableStateOf<Speelt?>(null) }
    var mediaZonderToegang by remember { mutableStateOf(false) }
    DisposableEffect(wakker) {
        val media = Media(context) { speelt = it }
        media.start()
        mediaZonderToegang = media.geenToegang
        onDispose { media.stop() }
    }

    // Agenda
    var magAgenda by remember { mutableStateOf(Agenda.magLezen(context)) }
    val vraag = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { magAgenda = it }
    var afspraken by remember { mutableStateOf<List<Afspraak>>(emptyList()) }
    LaunchedEffect(magAgenda, wakker, nu / (5 * 60000)) {
        if (!magAgenda) return@LaunchedEffect
        val van = Agenda.dagStart(Agenda.weekStart())
        afspraken = withContext(Dispatchers.IO) { Agenda.tussen(context, van, van + 14L * 24 * 3600000) }
    }

    // Weer
    var weer by remember { mutableStateOf<Weer?>(null) }
    LaunchedEffect(wakker, nu / (10 * 60000)) { weer = withContext(Dispatchers.IO) { WeerBron.haal() } }

    // Nog reageren (alleen het aantal, voor de regel onderaan)
    var wachten by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(wakker, nu / (5 * 60000)) {
        wachten = withContext(Dispatchers.IO) { try { Reageren.lijst(context)?.size } catch (e: Exception) { null } }
    }

    val dagStart = Agenda.dagStart(vandaag)
    val morgen = dagStart + 24 * 3600000L
    val ah = afspraken.filter { it.isAH }
    val volgendeDienst = ah.firstOrNull { it.eind > nu }
    val rechts = when {
        volgendeDienst != null && volgendeDienst.start > nu && volgendeDienst.start < morgen -> "appie over ${Tijd.over(volgendeDienst.start)}"
        weer?.zonOnder != null && weer!!.zonOnder!! > nu -> "zon onder ${Tijd.uur(weer!!.zonOnder!!)}"
        else -> null
    }

    Scherm {
        Kop("week ${weekNummer(vandaag)} · ${vandaag.dayOfMonth} ${maandKort(vandaag)}", dagNaam(vandaag), rechts)

        // ---- speelt.exe ----
        Venster("speelt.exe", "音楽") {
            val s = speelt
            when {
                mediaZonderToegang -> Text("Geef de app toegang tot meldingen (Instellingen), dan zie je hier wat er speelt.", style = NHTekst.small, color = k.muted)
                s == null -> Text("Er speelt niks.", style = NHTekst.body, color = k.muted)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).background(k.eva).border(1.dp, k.line)) {
                            s.hoes?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.titel, style = NHTekst.titelM, color = k.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(s.wie, "via ${s.app}").filter { it.isNotBlank() }.joinToString(" · "), style = NHTekst.small, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Knop("Vorige", modifier = Modifier.weight(1f)) { s.controller.transportControls.skipToPrevious() }
                        Knop(if (s.speelt) "Pauze" else "Spelen", hoofd = true, modifier = Modifier.weight(1f)) {
                            if (s.speelt) s.controller.transportControls.pause() else s.controller.transportControls.play()
                        }
                        Knop("Volgende", modifier = Modifier.weight(1f)) { s.controller.transportControls.skipToNext() }
                    }
                }
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
                    val weekVan = Agenda.dagStart(Agenda.weekStart())
                    val weekTot = weekVan + 7 * 24 * 3600000L
                    val uren = ah.filter { it.start >= weekVan && it.start < weekTot }.sumOf { it.eind - it.start } / 3600000.0
                    Text("${"%.1f".format(NL, uren).removeSuffix(",0")} u", style = NHTekst.getal, color = k.ink)
                    Text("deze week", style = NHTekst.small, color = k.muted)
                    volgendeDienst?.let {
                        Text("volgende: ${if (it.start < morgen) "vandaag" else Tijd.dagKort(it.start)} ${Tijd.uur(it.start)}", style = NHTekst.small, color = k.muted)
                    }
                }
            }
        }

        // ---- vandaag.exe ----
        Venster("vandaag.exe", "予定", zonderRand = true) {
            if (!magAgenda) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Laat de app de agenda van je telefoon lezen. Dan staan je diensten, colleges en Canvas hier zonder iets te koppelen.", style = NHTekst.small, color = k.muted)
                    Knop("Agenda van de telefoon gebruiken", hoofd = true) { vraag.launch(Manifest.permission.READ_CALENDAR) }
                }
            } else {
                val rest = afspraken.filter { !it.isCanvas && !it.heleDag && it.eind > nu && it.start < morgen }
                val heleDag = afspraken.filter { !it.isCanvas && it.heleDag && it.start <= dagStart && it.eind > dagStart }
                heleDag.forEach { Rij(it.titel, "hele dag") ; Scheiding() }
                if (rest.isEmpty()) {
                    val eerstvolgende = afspraken.firstOrNull { !it.isCanvas && !it.heleDag && it.start >= morgen }
                    if (eerstvolgende != null) {
                        Rij("${if (eerstvolgende.start < morgen + 24 * 3600000L) "Morgen" else dagNaam(java.time.Instant.ofEpochMilli(eerstvolgende.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate())}: ${eerstvolgende.titel}",
                            "${Tijd.dagKort(eerstvolgende.start)} ${Tijd.uur(eerstvolgende.start)}", sub = eerstvolgende.plek.ifBlank { null },
                            vierkantje = kleurVan(eerstvolgende, k))
                    } else Lijntekening(Tekening.Klaar, "Niks meer gepland. Verdacht.", breedte = 120.dp)
                } else rest.forEachIndexed { i, a ->
                    if (i > 0) Scheiding()
                    val sub = when {
                        a.isAH -> fietsZin(a, weer)
                        a.start <= nu -> "nu, tot ${Tijd.uur(a.eind)}"
                        else -> a.plek.ifBlank { null }
                    }
                    Rij(if (a.isAH) "Appie" else a.titel, Tijd.uur(a.start), sub = sub, vierkantje = kleurVan(a, k))
                }
            }
        }

        // ---- canvas.exe ----
        if (magAgenda) {
            val deadlines = afspraken.filter { it.isCanvas && it.eind > nu && it.eind < nu + 7 * 24 * 3600000L }.take(3)
            Venster("canvas.exe", "締切", zonderRand = true) {
                if (deadlines.isEmpty()) Text("Niks open de komende week.", style = NHTekst.body, color = k.muted, modifier = Modifier.padding(12.dp))
                deadlines.forEachIndexed { i, d ->
                    if (i > 0) Scheiding()
                    val code = Regex("""\[([^\]]+)]\s*$""").find(d.titel)?.groupValues?.get(1)
                    Rij(d.titel.replace(Regex("""\s*\[[^\]]+]\s*$"""), ""), "${Tijd.dagKort(d.eind)} ${Tijd.uur(d.eind)}", sub = code, let = d.eind - nu < 24 * 3600000L)
                }
            }
        }

        // ---- onderaan: wat er nog wacht ----
        wachten?.takeIf { it > 0 }?.let { n ->
            Text(
                "$n nog reageren", style = NHTekst.small, color = k.muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = naarReageren).wrapContentHeight(),
            )
        }
    }
}

private fun kleurVan(a: Afspraak, k: NHKleuren): Color = when {
    a.isAH -> k.ah
    a.isCollege -> k.eva
    else -> k.lineSoft
}

// Fietsen naar de dienst (25 min ervoor): droog of regen, alleen als het binnen het buienradarvenster valt
private fun fietsZin(a: Afspraak, w: Weer?): String {
    val tijden = "${Tijd.uur(a.start)} - ${Tijd.uur(a.eind)}"
    val vertrek = a.start - 25 * 60000
    val slot = w?.regen?.firstOrNull { vertrek >= it.first && vertrek < it.first + 5 * 60000 } ?: return tijden
    return "$tijden · fiets: ${if (slot.second >= WeerBron.REGEN_MIN) "regen" else "droog"}"
}

@Composable
private fun RegenStaafjes(w: Weer) {
    val k = LocalNH.current
    val now = System.currentTimeMillis()
    val slots = w.regen.filter { it.first + 5 * 60000 > now }.take(24)
    if (slots.isEmpty()) return
    val max = maxOf(1.0, slots.maxOf { it.second })
    Row(Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        slots.forEach { (_, mm) ->
            val nat = mm >= WeerBron.REGEN_MIN
            Box(
                Modifier.weight(1f).fillMaxHeight(if (nat) (0.15f + 0.85f * (mm / max).toFloat()).coerceAtMost(1f) else 0.08f)
                    .background(if (nat) k.eva else k.sky)
            )
        }
    }
}

// ================= Planner (fase 2) =================
@Composable
fun PlannerScherm() {
    val k = LocalNH.current
    val context = LocalContext.current
    val vandaag = LocalDate.now()
    val kanji = listOf("月", "火", "水", "木", "金", "土", "日")[vandaag.dayOfWeek.value - 1]
    Scherm {
        // Titelkaart: de dag als één groot woord, het Japanse teken van de dag in rood over de rand
        Box(Modifier.fillMaxWidth().background(if (k.donker) Color.Black else k.paperRaised).border(1.dp, if (k.donker) k.lineSoft else k.line)) {
            Text(kanji, style = NHTekst.titelXl.copy(fontSize = 110.sp, lineHeight = 110.sp), color = k.red, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
            Column(Modifier.padding(16.dp)) {
                Text("week ${weekNummer(vandaag)} · ${vandaag.dayOfMonth} ${vandaag.month.getDisplayName(TextStyle.FULL, NL)}", style = NHTekst.label, color = k.muted)
                Text(dagNaam(vandaag), style = NHTekst.titelXl.copy(fontSize = 44.sp, lineHeight = 46.sp), color = k.ink)
            }
        }
        Venster("planner.exe", "予定") {
            Text("Het Planner-tabje komt in de volgende fase: de Standaardweek vanaf nu, gewoontes om af te vinken en je taken.", style = NHTekst.body, color = k.ink)
            Text("Tot die tijd staat alles op de website.", style = NHTekst.small, color = k.muted)
            Spacer(Modifier.height(4.dp))
            Knop("Planner openen", hoofd = true) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://nbroers05.github.io/Dashboard/planner/")))
            }
        }
    }
}

// ================= Reageren =================
@Composable
fun ReagerenScherm(naarInstellingen: () -> Unit) {
    val context = LocalContext.current
    val k = LocalNH.current
    val nu = minuut()
    val wakker = Wakker.tel
    var lijst by remember { mutableStateOf<List<Wacht>?>(null) }
    var fout by remember { mutableStateOf<String?>(null) }
    var nietIngelogd by remember { mutableStateOf(false) }
    var opnieuw by remember { mutableIntStateOf(0) }
    LaunchedEffect(wakker, opnieuw, nu / 60000) {
        withContext(Dispatchers.IO) {
            try {
                val l = Reageren.lijst(context)
                nietIngelogd = l == null
                if (l != null) lijst = l
                fout = null
            } catch (e: Exception) {
                fout = "Geen verbinding, ik probeer het zo opnieuw."
            }
        }
    }
    var tab by rememberSaveable { mutableStateOf<String?>(null) }

    Scherm {
        val alle = lijst ?: emptyList()
        Kop("wacht op jou", if (lijst == null) "Reageren" else "${alle.size} ${if (alle.size == 1) "gesprek" else "gesprekken"}") {
            Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = naarInstellingen).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text("instellingen", style = NHTekst.label, color = k.muted)
            }
        }
        if (nietIngelogd) {
            Venster("nog-reageren.exe", "返信") {
                Text("Log in met de mail en het wachtwoord van de planner (Instellingen).", style = NHTekst.body, color = k.ink)
                Knop("Naar instellingen", hoofd = true, onClick = naarInstellingen)
            }
            return@Scherm
        }
        fout?.let { Text(it, style = NHTekst.small, color = k.muted) }

        val perTab = Reageren.TABS.associateWith { t -> alle.filter { Reageren.tabVan(it.app) == t } }
        val zichtbaar = Reageren.TABS.filter { it != "overig" || perTab[it]!!.isNotEmpty() }
        // Standaard: het tabje met de oudste wachtende (zoals de planner)
        val gekozen = tab?.takeIf { it in zichtbaar } ?: alle.minByOrNull { it.since }?.let { Reageren.tabVan(it.app) } ?: "whatsapp"
        val rijen = perTab[gekozen]!!.sortedBy { it.since }
        Venster("nog-reageren.exe", "返信", zonderRand = true) {
            Tabjes(zichtbaar.map { it to perTab[it]!!.size }, zichtbaar.indexOf(gekozen)) { tab = zichtbaar[it] }
            if (lijst == null && fout == null) Text("ophalen_", style = NHTekst.label, color = k.muted, modifier = Modifier.padding(12.dp))
            else if (rijen.isEmpty()) Lijntekening(Tekening.Klaar, "$gekozen: niks open.", breedte = 130.dp)
            else rijen.forEachIndexed { i, w ->
                if (i > 0) Scheiding()
                val oud = System.currentTimeMillis() - w.since > 2 * 24 * 3600000L
                Rij(
                    w.person, Tijd.wachtTijd(w.since), let = oud,
                    sub = w.bericht ?: "${if (gekozen == "dating" || gekozen == "overig") "${w.app} · " else ""}sinds ${Tijd.uur(w.since)}",
                ) {
                    Box(
                        Modifier.padding(start = 6.dp).size(44.dp).clickable(role = Role.Button) {
                            Reageren.weg(context, w)
                            lijst = lijst?.filterNot { it.person == w.person && it.app == w.app }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text("×", style = NHTekst.titelM, color = k.muted) }
                }
            }
        }
    }
}

// ================= Instellingen =================
@Composable
fun InstellingenScherm(onTerug: () -> Unit) {
    val context = LocalContext.current
    val k = LocalNH.current
    val prefs = remember { Prefs(context) }
    val wakker = Wakker.tel
    var ververs by remember { mutableIntStateOf(0) }
    val toegang = remember(wakker, ververs) {
        (Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "").contains(context.packageName)
    }
    val ingelogd = remember(wakker, ververs) { Supabase.loggedIn(context) }

    Scherm {
        Kop("dashboard", "Instellingen") {
            Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onTerug).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text("terug", style = NHTekst.label, color = k.muted)
            }
        }

        Venster("toegang.exe", "設定") {
            val regels = mutableListOf(
                "Toegang tot meldingen: ${if (toegang) "aan" else "uit"}",
                "Ingelogd: ${if (ingelogd) "ja" else "nee"}",
                "Agenda van de telefoon: ${if (Agenda.magLezen(context)) "ja" else "nee"}",
            )
            prefs.get("status.sent")?.toLongOrNull()?.let { regels += "Laatst doorgegeven: ${Tijd.dagKort(it)} ${Tijd.uur(it)}" }
            val wachtrij = Outbox.waiting(context)
            if (wachtrij > 0) regels += "Wacht op verzending: $wachtrij"
            prefs.get("status.error")?.let { regels += it }
            regels.forEach { Text(it, style = NHTekst.small, color = k.ink) }
            Spacer(Modifier.height(4.dp))
            if (!toegang) Knop("Toegang tot meldingen geven", hoofd = true) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            Knop("Accu-instelling openen") {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
            Text("Zet bij Accu de app op Onbeperkt, anders kan Samsung hem stilleggen. Toegang tot meldingen is ook nodig om te zien wat er speelt.", style = NHTekst.small, color = k.muted)
        }

        if (!ingelogd) Inloggen { ververs++ }
        else Venster("account.exe", "鍵") {
            Text("Ingelogd met je planner-account.", style = NHTekst.body, color = k.ink)
            Knop("Uitloggen") { Supabase.signOut(context); ververs++ }
        }

        Venster("apps.exe", "返信", zonderRand = true) {
            CHAT_APPS.forEach { app -> Vinkje(app.label, prefs.isOn(app)) { prefs.setOn(app, it) } }
            Scheiding()
            Vinkje("Ook groepsgesprekken", prefs.groups) { prefs.groups = it }
        }

        Venster("meldingen.exe", "記録") {
            Text("Om te controleren of het werkt. Zonder namen en zonder tekst.", style = NHTekst.small, color = k.muted)
            Text(remember(wakker, ververs) { Diag.read(context) }, style = NHTekst.label.copy(fontSize = 11.sp, lineHeight = 15.sp), color = k.ink)
        }

        Text("Versie ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}", style = NHTekst.label, color = k.muted, modifier = Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun Vinkje(tekst: String, aan: Boolean, onChange: (Boolean) -> Unit) {
    val k = LocalNH.current
    var on by remember { mutableStateOf(aan) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { on = !on; onChange(on) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).border(1.5.dp, k.line).background(if (on) (if (k.donker) k.red else k.ink) else Color.Transparent), contentAlignment = Alignment.Center) {
            if (on) Text("✓", style = NHTekst.small.copy(fontWeight = FontWeight.Bold), color = if (k.donker) k.onEva else k.paper)
        }
        Spacer(Modifier.width(10.dp))
        Text(tekst, style = NHTekst.body, color = k.ink)
    }
}

@Composable
private fun Inloggen(onKlaar: () -> Unit) {
    val context = LocalContext.current
    val k = LocalNH.current
    var mail by remember { mutableStateOf("") }
    var wachtwoord by remember { mutableStateOf("") }
    var fout by remember { mutableStateOf<String?>(null) }
    var bezig by remember { mutableStateOf(false) }
    var poging by remember { mutableIntStateOf(0) }
    LaunchedEffect(poging) {
        if (poging == 0) return@LaunchedEffect
        bezig = true
        val f = withContext(Dispatchers.IO) { Supabase.signIn(context, mail.trim(), wachtwoord) }
        bezig = false
        if (f == null) { wachtwoord = ""; Outbox.kick(context); onKlaar() } else fout = f
    }
    Venster("inloggen.exe", "鍵") {
        Text("Met dezelfde mail en hetzelfde wachtwoord als de planner.", style = NHTekst.small, color = k.muted)
        Veld("Mail", mail, KeyboardType.Email, false) { mail = it }
        Veld("Wachtwoord", wachtwoord, KeyboardType.Password, true) { wachtwoord = it }
        fout?.let { Text(it, style = NHTekst.small, color = k.red) }
        Knop(if (bezig) "Bezig…" else "Inloggen", hoofd = true) { if (!bezig) { fout = null; poging++ } }
    }
}

@Composable
private fun Veld(label: String, waarde: String, type: KeyboardType, geheim: Boolean, onChange: (String) -> Unit) {
    val k = LocalNH.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = NHTekst.label, color = k.muted)
        BasicTextField(
            waarde, onChange, singleLine = true,
            textStyle = NHTekst.body.copy(color = k.ink),
            cursorBrush = SolidColor(k.ink),
            keyboardOptions = KeyboardOptions(keyboardType = type),
            visualTransformation = if (geheim) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).background(if (k.donker) Color.Transparent else k.paperRaised)
                .border(1.dp, k.line).padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}
