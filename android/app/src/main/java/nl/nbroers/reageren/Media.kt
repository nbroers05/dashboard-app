package nl.nbroers.reageren

import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.nbroers.reageren.ui.*

// Filter chips at the top (like the old phone version: All/Film/Series/F1, plus Feeds)
private val FILTERS = listOf("all", "film", "series", "f1", "feeds")

// ================= Media (phase B, since 0.5.0) =================
@Composable
fun MediaScherm(speelt: Speelt?, zonderToegang: Boolean) {
    val context = LocalContext.current
    val k = LocalNH.current
    val nu = minuut()
    val wakker = Wakker.tel
    var filter by rememberSaveable { mutableStateOf("all") }
    val (gedeeld, _) = rememberGedeeld(nu)

    var gekeken by remember { mutableStateOf<List<Openbaar.Log>?>(null) }
    var vrienden by remember { mutableStateOf<List<Openbaar.Log>?>(null) }
    var f1 by remember { mutableStateOf<List<Openbaar.Sessie>?>(null) }
    var feeds by remember { mutableStateOf<Map<String, List<Openbaar.Bericht>>?>(null) }
    LaunchedEffect(wakker, nu / (15 * 60000)) {
        withContext(Dispatchers.IO) {
            try { gekeken = Openbaar.gekeken() } catch (e: Exception) { }
            try { vrienden = Openbaar.vrienden() } catch (e: Exception) { }
        }
    }
    LaunchedEffect(wakker, nu / (6 * 3600000)) { withContext(Dispatchers.IO) { try { f1 = Openbaar.f1() } catch (e: Exception) { } } }
    LaunchedEffect(filter, wakker, nu / (10 * 60000)) {
        if (filter == "feeds") withContext(Dispatchers.IO) { try { feeds = Openbaar.feeds() } catch (e: Exception) { } }
    }

    Scherm {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FILTERS.forEach { f ->
                val on = f == filter
                Box(
                    Modifier.heightIn(min = 44.dp).background(if (on) k.eva else Color.Transparent, RoundedCornerShape(50))
                        .border(1.dp, if (on) k.eva else k.line, RoundedCornerShape(50)).clickable(role = Role.Tab) { filter = f }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(f, style = NHTekst.label, color = if (on) k.onEva else k.ink) }
            }
        }

        if (filter == "all") Speler(speelt, zonderToegang)
        if (filter == "all" || filter == "film") FilmVenster(gedeeld?.film, gekeken)
        if (filter == "all" || filter == "series") Afleveringen(gedeeld)
        if (filter == "all" || filter == "f1" || filter == "film") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                if (filter != "film") F1Venster(f1, nu, Modifier.weight(1f).fillMaxHeight())
                if (filter != "f1") VriendenVenster(vrienden, Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (filter == "all") SpotifyMaand(gedeeld)
        if (filter == "feeds") FeedsVenster(feeds, context)
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- Player with lyrics ----------
@Composable
private fun Speler(s: Speelt?, zonderToegang: Boolean) {
    val k = LocalNH.current
    Venster("${s?.app?.lowercase()?.replace(" ", "") ?: "player"}.exe", "音楽") {
        when {
            zonderToegang -> Text("Give the app notification access (Reply › settings), then you see what's playing here.", style = NHTekst.small, color = k.muted)
            s == null -> Text("Nothing is playing.", style = NHTekst.body, color = k.muted)
            else -> {
                // Position: from the media session, kept up while something plays
                var pos by remember(s.titel) { mutableLongStateOf(0L) }
                LaunchedEffect(s.titel, s.speelt) {
                    while (true) {
                        val st = s.controller.playbackState
                        pos = if (st == null) 0 else if (st.state == PlaybackState.STATE_PLAYING)
                            st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else st.position
                        if (!s.speelt) break
                        delay(400)
                    }
                }
                var tekst by remember(s.titel, s.artiest) { mutableStateOf<List<Pair<Long, String>>?>(null) }
                LaunchedEffect(s.titel, s.artiest) {
                    tekst = withContext(Dispatchers.IO) { try { Openbaar.songtekst(s.artiest, s.titel, s.album, s.duur) } catch (e: Exception) { emptyList() } }
                }

                Row(verticalAlignment = Alignment.Top) {
                    Hoes(s, 120)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.app.lowercase(), style = NHTekst.label, color = k.muted)
                        Text(s.titel, style = NHTekst.titelM.copy(fontSize = 24.sp, lineHeight = 26.sp), color = k.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(s.artiest.ifBlank { s.wie }, style = NHTekst.small, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(4.dp).background(k.lineSoft)) {
                            if (s.duur > 0) Box(Modifier.fillMaxWidth((pos.toFloat() / s.duur).coerceIn(0f, 1f)).fillMaxHeight().background(k.ink))
                        }
                    }
                }
                // Lyrics: the line before, the line playing now big, the line after
                val t = tekst
                if (!t.isNullOrEmpty()) {
                    val i = t.indexOfLast { it.first <= pos + 300 }
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(k.lineSoft))
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t.getOrNull(i - 1)?.second ?: "", style = NHTekst.small, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (i >= 0) t[i].second else "♪", style = NHTekst.titelM, color = k.ink, maxLines = 2)
                        Text(t.getOrNull(i + 1)?.second ?: "", style = NHTekst.small, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Knop("Previous", modifier = Modifier.weight(1f)) { s.controller.transportControls.skipToPrevious() }
                    Knop(if (s.speelt) "Pause" else "Play", hoofd = true, modifier = Modifier.weight(1f)) {
                        if (s.speelt) s.controller.transportControls.pause() else s.controller.transportControls.play()
                    }
                    Knop("Next", modifier = Modifier.weight(1f)) { s.controller.transportControls.skipToNext() }
                }
            }
        }
    }
}

// ---------- Film: tonight / last watched ----------
@Composable
private fun FilmVenster(film: Gedeeld.Film?, gekeken: List<Openbaar.Log>?) {
    val k = LocalNH.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Venster("film.exe", "映画", zonderRand = true) {
        Tabjes(listOf("tonight" to null, "last watched" to null), tab) { tab = it }
        if (tab == 0) {
            if (film == null) Text("Tonight's film comes from the dashboard on the PC (Sterrenvoorspeller).", style = NHTekst.small, color = k.muted, modifier = Modifier.padding(12.dp))
            else {
                var bios by remember(film.titel) { mutableStateOf<Openbaar.Voorstelling?>(null) }
                LaunchedEffect(film.titel) { bios = withContext(Dispatchers.IO) { Openbaar.bios(film.titel) } }
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(72.dp).aspectRatio(2f / 3f).border(1.dp, k.line)) {
                        NetBeeld(film.poster, Modifier.fillMaxSize(), k.red)
                        if (film.poster == null) Text("夜", style = NHTekst.titelM, color = k.onEva, modifier = Modifier.padding(6.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(film.titel, style = NHTekst.titelM, color = k.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(film.jaar, film.verwacht?.let { "predicted ${"%.1f".format(NL, it)}" }, "watchlist").joinToString(" · "), style = NHTekst.small, color = k.muted)
                        bios?.let { b ->
                            val wanneer = b.volgende?.let { "${Tijd.dagKort(it)} ${Tijd.uur(it)}" } ?: b.tot?.let { "until $it" } ?: ""
                            Text("cinema: ${b.bios} $wanneer".trim(), style = NHTekst.small, color = k.ink)
                        }
                    }
                }
            }
        } else {
            val logs = gekeken
            if (logs == null) Text("loading_", style = NHTekst.label, color = k.muted, modifier = Modifier.padding(12.dp))
            else Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                logs.take(12).forEach { l -> Poster(l, null) }
            }
        }
    }
}

@Composable
private fun Poster(l: Openbaar.Log, naam: String?) {
    val k = LocalNH.current
    Column(Modifier.width(76.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NetBeeld(l.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f).border(1.dp, k.line), k.lineSoft)
        if (naam != null) Text(naam, style = NHTekst.small, color = k.ink, maxLines = 1)
        Text(sterren(l.cijfer), style = NHTekst.label, color = k.red, maxLines = 1)
        Text(l.gekeken.takeLast(5).let { md -> md.substring(3) + "-" + md.substring(0, 2) }, style = NHTekst.label.copy(fontSize = 11.sp), color = k.muted)
    }
}

private fun sterren(c: Double?): String {
    if (c == null) return ""
    val heel = c.toInt()
    return "★".repeat(heel) + if (c - heel >= 0.5) "½" else ""
}

// ---------- Episodes Left ----------
@Composable
private fun Afleveringen(g: Gedeeld?) {
    val k = LocalNH.current
    Venster("episodes.exe", "話数", zonderRand = true) {
        val series = g?.series ?: emptyList()
        when {
            g == null -> Text("Link the dashboard (settings) for Episodes Left.", style = NHTekst.small, color = k.muted, modifier = Modifier.padding(12.dp))
            series.isEmpty() -> Text("All caught up.", style = NHTekst.body, color = k.muted, modifier = Modifier.padding(12.dp))
            else -> series.sortedByDescending { it.nieuw * 1000 + it.over }.forEachIndexed { i, s ->
                if (i > 0) Scheiding()
                Rij(s.titel, "${s.over} left", sub = when (s.nieuw) { 0 -> null; 1 -> "1 new episode"; else -> "${s.nieuw} new episodes" })
            }
        }
        g?.episodesTijd?.let { Text("status from the PC, ${Tijd.dagKort(it).takeIf { _ -> System.currentTimeMillis() - it > 20 * 3600000L } ?: ""} ${Tijd.uur(it)}".replace("  ", " "), style = NHTekst.small, color = k.muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
    }
}

// ---------- F1 ----------
@Composable
private fun F1Venster(sessies: List<Openbaar.Sessie>?, nu: Long, modifier: Modifier) {
    val k = LocalNH.current
    Venster("f1.exe", "レース", modifier) {
        val nu2 = System.currentTimeMillis()
        val bezig = sessies?.firstOrNull { it.start <= nu2 && it.eind > nu2 }
        val volgende = sessies?.firstOrNull { it.start > nu2 }
        when {
            sessies == null -> Text("loading_", style = NHTekst.label, color = k.muted)
            bezig != null -> {
                Text("${bezig.naam} ${bezig.gp}".lowercase(), style = NHTekst.small, color = k.muted)
                Text("live now", style = NHTekst.label.copy(fontSize = 22.sp, lineHeight = 26.sp), color = k.red)
            }
            volgende != null -> {
                Text("${volgende.naam} ${volgende.gp}".lowercase(), style = NHTekst.small, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val min = (volgende.start - nu2) / 60000
                Text(if (min >= 24 * 60) "${min / 1440}d %02d:%02d".format((min % 1440) / 60, min % 60) else "%02d:%02d".format(min / 60, min % 60),
                    style = NHTekst.label.copy(fontSize = 24.sp, lineHeight = 28.sp), color = k.ink)
                Text("${Tijd.dagKort(volgende.start)} ${Tijd.uur(volgende.start)}", style = NHTekst.small, color = k.muted)
            }
            else -> Text("Season over.", style = NHTekst.small, color = k.muted)
        }
    }
}

// ---------- Friends ----------
@Composable
private fun VriendenVenster(logs: List<Openbaar.Log>?, modifier: Modifier) {
    val k = LocalNH.current
    Venster("friends.exe", "友達", modifier) {
        when {
            logs == null -> Text("loading_", style = NHTekst.label, color = k.muted)
            logs.isEmpty() -> Text("Nothing logged yet.", style = NHTekst.small, color = k.muted)
            else -> logs.take(3).forEach { l ->
                Text(l.wie ?: "", style = NHTekst.small, color = k.ink, maxLines = 1)
                Text(sterren(l.cijfer).ifBlank { l.titel }, style = NHTekst.label, color = k.red, maxLines = 1)
            }
        }
    }
}

// ---------- Spotify month ----------
@Composable
private fun SpotifyMaand(g: Gedeeld?) {
    val k = LocalNH.current
    val nummers = g?.nummers ?: emptyList()
    val artiesten = g?.artiesten ?: emptyList()
    if (nummers.isEmpty() && artiesten.isEmpty()) return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Venster("month.exe", "音楽", zonderRand = true) {
        Tabjes(listOf("tracks" to null, "artists" to null), tab) { tab = it }
        (if (tab == 0) nummers else artiesten).forEachIndexed { i, t ->
            if (i > 0) Scheiding()
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = NHTekst.label, color = k.muted, modifier = Modifier.width(18.dp))
                NetBeeld(t.beeld, Modifier.size(40.dp).border(1.dp, k.line), k.lineSoft)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.naam, style = NHTekst.body, color = k.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.sub.isNotBlank()) Text(t.sub, style = NHTekst.small, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------- Feeds ----------
@Composable
private fun FeedsVenster(feeds: Map<String, List<Openbaar.Bericht>>?, context: Context) {
    val k = LocalNH.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val namen = listOf("news", "film", "youtube")
    Venster("feeds.exe", "新聞", zonderRand = true) {
        Tabjes(namen.map { it to null }, tab) { tab = it }
        val lijst = feeds?.get(namen[tab])
        when {
            feeds == null -> Text("loading_", style = NHTekst.label, color = k.muted, modifier = Modifier.padding(12.dp))
            lijst.isNullOrEmpty() -> Text("Nothing new.", style = NHTekst.body, color = k.muted, modifier = Modifier.padding(12.dp))
            else -> lijst.forEachIndexed { i, b ->
                if (i > 0) Scheiding()
                Rij(b.titel, b.tijd?.let { if (System.currentTimeMillis() - it < 20 * 3600000L) Tijd.uur(it) else Tijd.dagKort(it) } ?: "", sub = b.bron,
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(b.link))) })
            }
        }
    }
}
