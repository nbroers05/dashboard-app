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

internal val NL = Locale("nl", "NL")

/** Wordt opgehoogd als de app weer in beeld komt (MainActivity.onResume): schermen halen dan opnieuw op. */
object Wakker { var tel by mutableIntStateOf(0) }

@Composable
internal fun Scherm(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Tikt elke minuut, zodat tijden en "nu" kloppen. */
@Composable
internal fun minuut(): Long {
    var nu by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60000 - nu % 60000); nu = System.currentTimeMillis() } }
    return nu
}

internal fun weekNummer(d: LocalDate) = d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
internal fun dagNaam(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, NL).replaceFirstChar { it.uppercase() }
internal fun maandKort(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, NL).trimEnd('.')

internal fun kleurVan(a: Afspraak, k: NHKleuren): Color = when {
    a.isAH -> k.ah
    a.isCollege -> k.eva
    else -> k.lineSoft
}

// Fietsen naar de dienst (25 min ervoor): droog of regen, alleen als het binnen het buienradarvenster valt
internal fun fietsZin(a: Afspraak, w: Weer?): String {
    val tijden = "${Tijd.uur(a.start)} - ${Tijd.uur(a.eind)}"
    val vertrek = a.start - 25 * 60000
    val slot = w?.regen?.firstOrNull { vertrek >= it.first && vertrek < it.first + 5 * 60000 } ?: return tijden
    return "$tijden · fiets: ${if (slot.second >= WeerBron.REGEN_MIN) "regen" else "droog"}"
}

@Composable
internal fun RegenStaafjes(w: Weer) {
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

        // Dashboard: één keer de wachtzin, voor AH-netto, Canvas-vinkjes, to-do, Episodes en mail
        val dash = remember(wakker, ververs) { Dash.gekoppeld(context) }
        if (!dash) Wachtzin { ververs++ }
        else Venster("dashboard.exe", "鍵") {
            Text("Gekoppeld aan het dashboard.", style = NHTekst.body, color = k.ink)
            Knop("Ontkoppelen") { Dash.ontkoppel(context); ververs++ }
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

        // Laatste crash (selecteerbaar, om door te sturen); weg met de knop
        prefs.get("crash.last")?.let { crash ->
            Venster("crash.exe", "記録") {
                Text("De app is afgesloten door deze fout. Stuur een screenshot van de eerste regels.", style = NHTekst.small, color = k.muted)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(crash.lines().take(14).joinToString("\n"), style = NHTekst.label.copy(fontSize = 10.sp, lineHeight = 13.sp), color = k.ink)
                }
                Knop("Weghalen") { prefs.put("crash.last", null); ververs++ }
            }
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
internal fun Wachtzin(alleenArchief: Boolean = false, onKlaar: () -> Unit) {
    val context = LocalContext.current
    val k = LocalNH.current
    var zin by remember { mutableStateOf("") }
    var fout by remember { mutableStateOf<String?>(null) }
    var bezig by remember { mutableStateOf(false) }
    var poging by remember { mutableIntStateOf(0) }
    LaunchedEffect(poging) {
        if (poging == 0) return@LaunchedEffect
        bezig = true
        val f = withContext(Dispatchers.Default) { if (alleenArchief) Archief.koppel(context, zin) else Dash.koppel(context, zin) }
        bezig = false
        if (f == null) { zin = ""; onKlaar() } else fout = f
    }
    Venster("dashboard.exe", "鍵") {
        Text(if (alleenArchief) "Eén keer je wachtzin, voor je cijfers, Memory Lane en check-ins uit het data-archief. De wachtzin zelf wordt niet bewaard." else "Eén keer je wachtzin, dezelfde als bij inloggen op het dashboard. Dan staan AH-netto, Canvas-vinkjes, to-do, Episodes, mail en je data-archief hier ook. De wachtzin zelf wordt niet bewaard.", style = NHTekst.small, color = k.muted)
        Veld("Wachtzin", zin, KeyboardType.Password, true) { zin = it }
        fout?.let { Text(it, style = NHTekst.small, color = k.red) }
        Knop(if (bezig) "Even rekenen…" else "Koppelen", hoofd = true) { if (!bezig && zin.isNotEmpty()) { fout = null; poging++ } }
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
