// Noah Huisstijl voor Android (Jetpack Compose). Uit android/NoahHuisstijl.kt van het design system
// (claude.ai/artifact/1uHEFBcpmD23vuaQRoQED9), met de lettertypes in de app zelf (res/font, verkleind tot de letters
// en Japanse woorden die de app gebruikt) in plaats van Google Fonts via Play Services.
// Telefoon: Poster, en Titelkaart als de telefoon op donker staat.
package nl.nbroers.reageren.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.nbroers.reageren.R

// ---------- Kleuren ----------
data class NHKleuren(
    val paper: Color, val paperRaised: Color, val ink: Color, val muted: Color,
    val line: Color, val lineSoft: Color, val eva: Color, val sky: Color, val red: Color,
    val titlebar: Color, val onEva: Color = Color(0xFFFFFFFF),
    val ah: Color = Color(0xFF00ADE6), val onAh: Color = Color(0xFF04263A),
    val blokschaduw: Boolean, val donker: Boolean,
)

val Poster = NHKleuren(
    paper = Color(0xFFEFE9DC), paperRaised = Color(0xFFF8F4EA), ink = Color(0xFF16130F), muted = Color(0xFF5E574C),
    line = Color(0xFF16130F), lineSoft = Color(0xFFD3C9B6), eva = Color(0xFF1B2A8C), sky = Color(0xFF8CC0E8),
    red = Color(0xFFD7261E), titlebar = Color(0xFF1B2A8C), blokschaduw = true, donker = false,
)
val Titelkaart = NHKleuren(
    paper = Color(0xFF0B0B0D), paperRaised = Color(0xFF141417), ink = Color(0xFFF2EFE8), muted = Color(0xFFA7A29A),
    line = Color(0xFF5C5852), lineSoft = Color(0xFF2A2A30), eva = Color(0xFF1B2A8C), sky = Color(0xFF1C2E52),
    red = Color(0xFFE3241B), titlebar = Color(0xFF000000), blokschaduw = false, donker = true,
)

val LocalNH = staticCompositionLocalOf { Poster }

// ---------- Letters ----------
val Display = FontFamily(
    Font(R.font.nh_display_bold, FontWeight.Bold),
    Font(R.font.nh_display_extrabold, FontWeight.ExtraBold),
)
val Body = FontFamily(
    Font(R.font.nh_body_regular, FontWeight.Normal),
    Font(R.font.nh_body_medium, FontWeight.Medium),
    Font(R.font.nh_body_bold, FontWeight.Bold),
    Font(R.font.nh_body_black, FontWeight.Black),
)
val Pixel = FontFamily(Font(R.font.nh_pixel, FontWeight.Normal))

object NHTekst {
    val titelXl = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 56.sp, lineHeight = 52.sp)
    val titelL = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 36.sp)
    val titelM = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.Bold)
    val small = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val getal = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 30.sp)
    val zin = TextStyle(fontFamily = Body, fontWeight = FontWeight.Black, fontSize = 16.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = Pixel, fontSize = 13.sp, lineHeight = 16.sp)
}

// ---------- Thema ----------
@Composable
fun NoahTheme(content: @Composable () -> Unit) {
    val k = if (isSystemInDarkTheme()) Titelkaart else Poster
    val scheme = if (k.donker)
        darkColorScheme(primary = k.eva, onPrimary = k.onEva, background = k.paper, onBackground = k.ink, surface = k.paperRaised, onSurface = k.ink, error = k.red, outline = k.line)
    else
        lightColorScheme(primary = k.eva, onPrimary = k.onEva, background = k.paper, onBackground = k.ink, surface = k.paperRaised, onSurface = k.ink, error = k.red, outline = k.line)
    CompositionLocalProvider(LocalNH provides k) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize().background(k.paper)) { content() }
        }
    }
}

// ---------- Venster ----------
// Elk vak is een venster: titelbalk met bestandsnaam en Japans woord, recht, met blokschaduw in Poster.
// Op de telefoon geen knopjes in de titelbalk (te smal).
@Composable
fun Venster(
    titel: String,
    kanji: String,
    modifier: Modifier = Modifier,
    zonderRand: Boolean = false,
    inhoud: @Composable ColumnScope.() -> Unit,
) {
    val k = LocalNH.current
    val titelkaart = k.donker
    Box(modifier.padding(end = if (k.blokschaduw) 4.dp else 0.dp, bottom = if (k.blokschaduw) 4.dp else 0.dp)) {
        if (k.blokschaduw) Box(Modifier.matchParentSize().offset(4.dp, 4.dp).background(k.line))
        Column(Modifier.fillMaxWidth().background(k.paperRaised).border(1.dp, k.line)) {
            Row(
                Modifier.fillMaxWidth().background(k.titlebar).heightIn(min = 30.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (titelkaart) titel.removeSuffix(".exe").uppercase() else titel,
                    style = if (titelkaart) NHTekst.label.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp) else NHTekst.label,
                    color = k.onEva, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(kanji, style = NHTekst.label.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold), color = if (titelkaart) k.red else k.onEva)
            }
            if (!titelkaart) Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
            Column(
                if (zonderRand) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = inhoud,
            )
        }
    }
}

// ---------- Rij ----------
@Composable
fun Rij(
    wat: String,
    wanneer: String,
    sub: String? = null,
    let: Boolean = false,
    vierkantje: Color? = null,
    onClick: (() -> Unit)? = null,
    rechts: (@Composable () -> Unit)? = null,
) {
    val k = LocalNH.current
    val base = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    Row(
        (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vierkantje != null) {
                    Box(Modifier.size(10.dp).background(vierkantje))
                    Spacer(Modifier.width(8.dp))
                }
                Text(wat, style = NHTekst.body, color = k.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (sub != null) Text(sub, style = NHTekst.small, color = k.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(wanneer, style = NHTekst.label, color = if (let) k.red else k.muted)
        if (rechts != null) rechts()
    }
}

@Composable
fun Scheiding() {
    val k = LocalNH.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(k.lineSoft))
}

// ---------- Label ----------
enum class Soort { Appie, College, Deadline, Gewoon }

@Composable
fun Label(tekst: String, soort: Soort = Soort.Gewoon) {
    val k = LocalNH.current
    val (bg, fg) = when (soort) {
        Soort.Appie -> k.ah to k.onAh
        Soort.College -> k.eva to k.onEva
        Soort.Deadline -> k.red to k.onEva
        Soort.Gewoon -> k.paperRaised to k.ink
    }
    Text(
        tekst, style = NHTekst.small.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp), color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp),
    )
}

// ---------- Knop ----------
// Minstens 44 dp hoog. Hoofdknop rood (Poster) of rood in Titelkaart; gewone knop papier met rand.
@Composable
fun Knop(tekst: String, hoofd: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val k = LocalNH.current
    val bg = if (hoofd) k.red else if (k.donker) Color.Transparent else k.paperRaised
    val fg = if (hoofd) k.onEva else k.ink
    Box(modifier.padding(end = if (k.blokschaduw) 2.dp else 0.dp, bottom = if (k.blokschaduw) 2.dp else 0.dp)) {
        if (k.blokschaduw) Box(Modifier.matchParentSize().offset(2.dp, 2.dp).background(k.line))
        Box(
            Modifier.heightIn(min = 44.dp).background(bg).border(1.dp, if (hoofd && k.donker) k.red else k.line)
                .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (k.donker) tekst.uppercase() else tekst,
                style = if (k.donker) NHTekst.small.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp) else NHTekst.bodyStrong.copy(fontSize = 14.sp),
                color = fg,
            )
        }
    }
}

// ---------- Tabjes ----------
@Composable
fun Tabjes(items: List<Pair<String, Int?>>, actief: Int, onKies: (Int) -> Unit) {
    val k = LocalNH.current
    Row(Modifier.fillMaxWidth().border(BorderStroke(0.dp, Color.Transparent))) {
        items.forEachIndexed { i, (naam, n) ->
            val on = i == actief
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .background(if (on && !k.donker) k.eva else Color.Transparent)
                    .clickable(role = Role.Tab) { onKies(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (n != null) "$naam $n" else naam,
                    style = NHTekst.label, maxLines = 1,
                    color = if (on) (if (k.donker) k.ink else k.onEva) else k.muted,
                )
                if (on && k.donker) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(k.red))
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(if (k.donker) k.lineSoft else k.line))
}

// ---------- Onderbalk ----------
@Composable
fun Onderbalk(items: List<String>, actief: Int, onKies: (Int) -> Unit) {
    val k = LocalNH.current
    Column(Modifier.fillMaxWidth().background(if (k.donker) k.paper else k.paperRaised)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (k.donker) k.lineSoft else k.line))
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            items.forEachIndexed { i, naam ->
                val on = i == actief
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(if (on && !k.donker) k.eva else Color.Transparent)
                        .clickable(role = Role.Tab) { onKies(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on && k.donker) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(k.red))
                    Text(naam, style = NHTekst.label, color = if (on) (if (k.donker) k.ink else k.onEva) else k.muted)
                }
            }
        }
    }
}

// ---------- Kop van een scherm ----------
// Klein regeltje in pixel, één groot woord in de schreefletter, rechts een bijzaak
@Composable
fun Kop(klein: String, groot: String, rechts: String? = null, actie: (@Composable () -> Unit)? = null) {
    val k = LocalNH.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(klein, style = NHTekst.label, color = k.muted)
            Text(groot, style = NHTekst.titelL, color = k.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (rechts != null) Text(rechts, style = NHTekst.label, color = k.muted, textAlign = TextAlign.End, modifier = Modifier.padding(bottom = 6.dp))
        if (actie != null) actie()
    }
}

// ---------- Lijntekening ----------
// Kale tekening (één lijndikte, geen vulling) met een droge zin eronder. Uit Assets › Lijntekeningen.
private val KLAAR = listOf(
    "M8 104 C60 103 120 105 172 104", "M82 24 C84 18 94 17 99 23 M85 21 l-4 -5 M92 19 l0 -6 M98 22 l4 -4",
    "M90 41 C90 56 90 68 90 80", "M90 80 L80 104 M90 80 L100 104", "M90 50 L70 34 L64 18 M90 50 L110 34 L116 18", "M40 76 l8 8 l16 -18",
)
private val STEKKER = listOf(
    "M8 104 C60 103 120 105 172 104", "M50 30 C52 24 62 23 67 29 M53 27 l-4 -5 M60 25 l0 -6 M66 28 l4 -4",
    "M58 47 C59 62 60 76 62 90", "M62 90 L96 92 L100 104", "M62 90 L84 98 L86 104", "M59 58 L80 66 L94 60", "M59 60 L76 74 L92 66",
    "M94 54 h12 v14 h-12 z", "M106 58 h6 M106 64 h6", "M94 61 C80 50 70 70 52 80 C40 87 30 96 24 104", "M150 56 h16 v22 h-16 z",
)

enum class Tekening { Klaar, Stekker }

@Composable
fun Lijntekening(tekening: Tekening, zin: String, sub: String? = null, breedte: Dp = 150.dp) {
    val k = LocalNH.current
    val paths = remember(tekening) { (if (tekening == Tekening.Klaar) KLAAR else STEKKER).map { PathParser().parsePathString(it).toPath() } }
    val hoofd = if (tekening == Tekening.Klaar) Offset(90f, 30f) else Offset(58f, 36f)
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.width(breedte).aspectRatio(180f / 112f)) {
            val s = size.width / 180f
            scale(s, s, pivot = Offset.Zero) {
                val stroke = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                paths.forEach { drawPath(it, k.ink, style = stroke) }
                drawCircle(k.ink, radius = 11f, center = hoofd, style = stroke)
            }
        }
        Text(zin.uppercase(), style = NHTekst.zin, color = k.ink, textAlign = TextAlign.Center)
        if (sub != null) Text(sub, style = NHTekst.small, color = k.muted, textAlign = TextAlign.Center)
    }
}
