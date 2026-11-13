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

/** Locale for dates and numbers on screen (English since 0.8.0; the name stays so all callers keep working). */
internal val NL: Locale = Locale.UK

/** Raised when the app comes back into view (MainActivity.onResume): screens then fetch again. */
object Wakker { var tel by mutableIntStateOf(0) }

@Composable
internal fun Scherm(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Ticks every minute, so times and "now" stay right. */
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

// Cycling to the shift (25 min before): dry or rain, only when it falls within the Buienradar window
internal fun fietsZin(a: Afspraak, w: Weer?): String {
    val tijden = "${Tijd.uur(a.start)} - ${Tijd.uur(a.eind)}"
    val vertrek = a.start - 25 * 60000
    val slot = w?.regen?.firstOrNull { vertrek >= it.first && vertrek < it.first + 5 * 60000 } ?: return tijden
    return "$tijden · bike: ${if (slot.second >= WeerBron.REGEN_MIN) "rain" else "dry"}"
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

// ================= Reply =================
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
                fout = "No connection, I'll try again shortly."
            }
        }
    }
    var tab by rememberSaveable { mutableStateOf<String?>(null) }

    Scherm {
        val alle = lijst ?: emptyList()
        Kop("waiting for you", if (lijst == null) "Reply" else "${alle.size} ${if (alle.size == 1) "chat" else "chats"}") {
            Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = naarInstellingen).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text("settings", style = NHTekst.label, color = k.muted)
            }
        }
        if (nietIngelogd) {
            Venster("reply.exe", "返信") {
                Text("Log in with the planner's email and password (Settings).", style = NHTekst.body, color = k.ink)
                Knop("To settings", hoofd = true, onClick = naarInstellingen)
            }
            return@Scherm
        }
        fout?.let { Text(it, style = NHTekst.small, color = k.muted) }

        val perTab = Reageren.TABS.associateWith { t -> alle.filter { Reageren.tabVan(it.app) == t } }
        val zichtbaar = Reageren.TABS.filter { it != "other" || perTab[it]!!.isNotEmpty() }
        // Default: the tab with the oldest waiting one (like the planner)
        val gekozen = tab?.takeIf { it in zichtbaar } ?: alle.minByOrNull { it.since }?.let { Reageren.tabVan(it.app) } ?: "whatsapp"
        val rijen = perTab[gekozen]!!.sortedBy { it.since }
        Venster("reply.exe", "返信", zonderRand = true) {
            Tabjes(zichtbaar.map { it to perTab[it]!!.size }, zichtbaar.indexOf(gekozen)) { tab = zichtbaar[it] }
            if (lijst == null && fout == null) Text("loading_", style = NHTekst.label, color = k.muted, modifier = Modifier.padding(12.dp))
            else if (rijen.isEmpty()) Lijntekening(Tekening.Klaar, "$gekozen: nothing open.", breedte = 130.dp)
            else rijen.forEachIndexed { i, w ->
                if (i > 0) Scheiding()
                val oud = System.currentTimeMillis() - w.since > 2 * 24 * 3600000L
                Rij(
                    w.person, Tijd.wachtTijd(w.since), let = oud,
                    sub = w.bericht ?: "${if (gekozen == "dating" || gekozen == "other") "${w.app} · " else ""}since ${Tijd.uur(w.since)}",
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

// ================= Settings =================
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
        Kop("dashboard", "Settings") {
            Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onTerug).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text("back", style = NHTekst.label, color = k.muted)
            }
        }

        Venster("access.exe", "設定") {
            val regels = mutableListOf(
                "Notification access: ${if (toegang) "on" else "off"}",
                "Logged in: ${if (ingelogd) "yes" else "no"}",
                "Phone calendar: ${if (Agenda.magLezen(context)) "yes" else "no"}",
            )
            prefs.get("status.sent")?.toLongOrNull()?.let { regels += "Last sent: ${Tijd.dagKort(it)} ${Tijd.uur(it)}" }
            val wachtrij = Outbox.waiting(context)
            if (wachtrij > 0) regels += "Waiting to be sent: $wachtrij"
            prefs.get("status.error")?.let { regels += it }
            regels.forEach { Text(it, style = NHTekst.small, color = k.ink) }
            Spacer(Modifier.height(4.dp))
            if (!toegang) Knop("Give notification access", hoofd = true) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            Knop("Open battery setting") {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
            Text("Under Battery set the app to Unrestricted, otherwise Samsung may stop it. Notification access is also needed to see what's playing.", style = NHTekst.small, color = k.muted)
        }

        if (!ingelogd) Inloggen { ververs++ }
        else Venster("account.exe", "鍵") {
            Text("Logged in with your planner account.", style = NHTekst.body, color = k.ink)
            Knop("Log out") { Supabase.signOut(context); ververs++ }
        }

        // Dashboard: the passphrase once, for AH net pay, Canvas check marks, to-do, Episodes and mail
        val dash = remember(wakker, ververs) { Dash.gekoppeld(context) }
        if (!dash) Wachtzin { ververs++ }
        else Venster("dashboard.exe", "鍵") {
            Text("Linked to the dashboard.", style = NHTekst.body, color = k.ink)
            Knop("Unlink") { Dash.ontkoppel(context); ververs++ }
        }

        Venster("apps.exe", "返信", zonderRand = true) {
            CHAT_APPS.forEach { app -> Vinkje(app.label, prefs.isOn(app)) { prefs.setOn(app, it) } }
            Scheiding()
            Vinkje("Group chats too", prefs.groups) { prefs.groups = it }
        }

        Venster("notifications.exe", "記録") {
            Text("To check that it works. Without names and without text.", style = NHTekst.small, color = k.muted)
            Text(remember(wakker, ververs) { Diag.read(context) }, style = NHTekst.label.copy(fontSize = 11.sp, lineHeight = 15.sp), color = k.ink)
        }

        // Last crash (selectable, to pass on); removed with the button
        prefs.get("crash.last")?.let { crash ->
            Venster("crash.exe", "記録") {
                Text("The app closed because of this error. Send a screenshot of the first lines.", style = NHTekst.small, color = k.muted)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(crash.lines().take(14).joinToString("\n"), style = NHTekst.label.copy(fontSize = 10.sp, lineHeight = 13.sp), color = k.ink)
                }
                Knop("Remove") { prefs.put("crash.last", null); ververs++ }
            }
        }

        UpdateVenster(altijd = true)

        Text("Version ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}", style = NHTekst.label, color = k.muted, modifier = Modifier.padding(bottom = 16.dp))
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
    Venster("login.exe", "鍵") {
        Text("With the same email and password as the planner.", style = NHTekst.small, color = k.muted)
        Veld("Email", mail, KeyboardType.Email, false) { mail = it }
        Veld("Password", wachtwoord, KeyboardType.Password, true) { wachtwoord = it }
        fout?.let { Text(it, style = NHTekst.small, color = k.red) }
        Knop(if (bezig) "Working…" else "Log in", hoofd = true) { if (!bezig) { fout = null; poging++ } }
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
        Text(if (alleenArchief) "Your passphrase once, for your numbers, Memory Lane and check-ins from the data archive. The passphrase itself is not stored." else "Your passphrase once, the same as when logging in on the dashboard. Then AH net pay, Canvas check marks, to-do, Episodes, mail and your data archive are here too. The passphrase itself is not stored.", style = NHTekst.small, color = k.muted)
        Veld("Passphrase", zin, KeyboardType.Password, true) { zin = it }
        fout?.let { Text(it, style = NHTekst.small, color = k.red) }
        Knop(if (bezig) "Calculating…" else "Link", hoofd = true) { if (!bezig && zin.isNotEmpty()) { fout = null; poging++ } }
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
