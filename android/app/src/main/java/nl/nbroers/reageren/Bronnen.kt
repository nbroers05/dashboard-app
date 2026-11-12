package nl.nbroers.reageren

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlin.math.pow

// ---------- Wat er speelt (mediasessies van de telefoon) ----------
// Mag dankzij de toegang tot meldingen (ReplyListener): dezelfde toestemming, niks extra koppelen.
data class Speelt(
    val titel: String,
    val wie: String,
    val app: String,
    val hoes: Bitmap?,
    val speelt: Boolean,
    val controller: MediaController,
)

class Media(private val context: Context, private val onChange: (Speelt?) -> Unit) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, ReplyListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var current: MediaController? = null
    var geenToegang = false
        private set

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = pick()
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(sessions, component, main)
            geenToegang = false
            pick()
        } catch (e: SecurityException) {
            geenToegang = true
            onChange(null)
        }
    }

    fun stop() {
        try { manager.removeOnActiveSessionsChangedListener(sessions) } catch (e: Exception) { }
        current?.unregisterCallback(callback)
        current = null
    }

    // Wat nu speelt gaat voor; anders de eerste sessie met een titel (gepauzeerd)
    private fun pick() {
        val list = try { manager.getActiveSessions(component) } catch (e: SecurityException) { geenToegang = true; emptyList() }
        val withTitle = list.filter { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
        val next = withTitle.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: withTitle.firstOrNull()
        if (next?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(callback)
            current = next
            next?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        val c = current ?: return onChange(null)
        val m = c.metadata ?: return onChange(null)
        val label = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        } catch (e: Exception) { c.packageName }
        onChange(
            Speelt(
                titel = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                wie = listOfNotNull(m.getString(MediaMetadata.METADATA_KEY_ARTIST), m.getString(MediaMetadata.METADATA_KEY_ALBUM)).filter { it.isNotBlank() }.joinToString(" · "),
                app = label,
                hoes = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART),
                speelt = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                controller = c,
            )
        )
    }
}

// ---------- Agenda van de telefoon ----------
// Alleen lezen. AH = "AH" als los woord in de titel (net als het dashboard); Canvas = agenda met "Canvas" in de naam.
data class Afspraak(
    val titel: String,
    val plek: String,
    val start: Long,
    val eind: Long,
    val heleDag: Boolean,
    val agenda: String,
) {
    val isAH get() = !heleDag && AH.containsMatchIn(titel)
    val isCanvas get() = agenda.contains("canvas", ignoreCase = true)
    val isCollege get() = !isCanvas && (COLLEGE.containsMatchIn(agenda) || COLLEGE.containsMatchIn(titel))

    companion object {
        private val AH = Regex("""\bAH\b""")
        private val COLLEGE = Regex("""(?i)rooster|timetable|erasmus|\beur\b|college|lecture|werkgroep|tutorial""")
    }
}

object Agenda {
    fun magLezen(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun tussen(context: Context, van: Long, tot: Long): List<Afspraak> {
        if (!magLezen(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, van)
            ContentUris.appendId(it, tot)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.VISIBLE, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val out = mutableListOf<Afspraak>()
        try {
            context.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val agenda = c.getString(5) ?: ""
                    // Verborgen agenda's niet, behalve Canvas (die staat op de pc ook verborgen en heeft een eigen vak)
                    if (c.getInt(6) == 0 && !agenda.contains("canvas", true)) continue
                    val allDay = c.getInt(4) == 1
                    var start = c.getLong(2)
                    var end = c.getLong(3)
                    if (allDay) {
                        // Hele-dag-afspraken staan in UTC-middernacht: omzetten naar de eigen dag
                        val zone = ZoneId.systemDefault()
                        start = Instant.ofEpochMilli(start).atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                        end = Instant.ofEpochMilli(end).atZone(ZoneId.of("UTC")).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                    }
                    out += Afspraak(c.getString(0) ?: "(geen titel)", c.getString(1) ?: "", start, end, allDay, agenda)
                }
            }
        } catch (e: SecurityException) { }
        return out
    }

    fun dagStart(dag: LocalDate = LocalDate.now()) = dag.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    fun weekStart(): LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

// ---------- Weer (Open-Meteo) en regen (Buienradar), Rotterdam ----------
data class Weer(val temp: Double?, val code: Int?, val regen: List<Pair<Long, Double>>, val zonOnder: Long?)

object WeerBron {
    private const val LAT = 51.92
    private const val LON = 4.48
    const val REGEN_MIN = 0.1

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "dashboard-app")
            if (c.responseCode != 200) throw IOException("fout ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun haal(): Weer {
        var temp: Double? = null
        var code: Int? = null
        var zonOnder: Long? = null
        try {
            val j = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON&current=temperature_2m,weather_code&daily=sunset&timeformat=unixtime&timezone=Europe%2FAmsterdam&forecast_days=1"))
            j.optJSONObject("current")?.let { temp = it.optDouble("temperature_2m"); code = it.optInt("weather_code") }
            j.optJSONObject("daily")?.optJSONArray("sunset")?.let { if (it.length() > 0) zonOnder = it.getLong(0) * 1000 }
        } catch (e: Exception) { }
        val regen = try { buienradar() } catch (e: Exception) { emptyList() }
        return Weer(temp, code, regen, zonOnder)
    }

    // "val|HH:MM" per 5 minuten, komende 2 uur; mm/u = 10^((val-109)/32)
    private fun buienradar(): List<Pair<Long, Double>> {
        val text = get("https://gpsgadget.buienradar.nl/data/raintext?lat=$LAT&lon=$LON")
        val now = System.currentTimeMillis()
        val zone = ZoneId.of("Europe/Amsterdam")
        val today = LocalDate.now(zone)
        return text.lines().mapNotNull { line ->
            val parts = line.trim().split("|")
            if (parts.size != 2) return@mapNotNull null
            val v = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val (h, m) = parts[1].trim().split(":").map { it.toIntOrNull() ?: return@mapNotNull null }
            var t = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
            if (t < now - 12 * 3600000) t += 24 * 3600000   // na middernacht
            t to (if (v == 0) 0.0 else 10.0.pow((v - 109) / 32.0))
        }
    }

    fun zin(w: Weer): String {
        val now = System.currentTimeMillis()
        val slots = w.regen.filter { it.first + 5 * 60000 > now }
        if (slots.isEmpty()) return omschrijving(w.code)
        val nat = slots.firstOrNull { it.second >= REGEN_MIN } ?: return "Droog, komende 2 uur"
        if (nat.first <= now) {
            val droog = slots.dropWhile { it.second >= REGEN_MIN }.firstOrNull()
            return if (droog != null) "Regen tot ${Tijd.uur(droog.first)}" else "Regen, komende 2 uur"
        }
        return "Droog tot ${Tijd.uur(nat.first)}"
    }

    fun omschrijving(code: Int?) = when (code) {
        null -> ""
        0 -> "Helder"
        1, 2 -> "Half bewolkt"
        3 -> "Bewolkt"
        45, 48 -> "Mist"
        in 51..67 -> "Regen"
        in 71..77 -> "Sneeuw"
        in 80..82 -> "Buien"
        in 85..86 -> "Sneeuwbuien"
        in 95..99 -> "Onweer"
        else -> ""
    }
}

// ---------- Nog reageren (Supabase, planner_replies) ----------
data class Wacht(val person: String, val app: String, val since: Long, val bericht: String?)

object Reageren {
    // Zelfde tabjes als de planner
    val TABS = listOf("whatsapp", "snap", "insta", "dating", "overig")
    fun tabVan(app: String) = when {
        app.startsWith("WhatsApp", true) -> "whatsapp"
        app.equals("Snapchat", true) -> "snap"
        app.equals("Instagram", true) -> "insta"
        app.equals("Hinge", true) || app.equals("Tinder", true) || app.equals("Bumble", true) -> "dating"
        else -> "overig"
    }

    /** null = niet ingelogd. Gooit IOException bij geen verbinding. */
    @Throws(IOException::class)
    fun lijst(context: Context): List<Wacht>? {
        if (!Supabase.loggedIn(context)) return null
        fun req() = Supabase.call(
            "GET", "$SUPABASE_URL/rest/v1/planner_replies?select=person,app,since,last_message&order=since.asc",
            mapOf("Authorization" to "Bearer ${Supabase.token(context)}"), null,
        )
        return try {
            var r = req()
            if (r.code == 401) { Supabase.forgetAccess(); r = req() }
            if (r.code !in 200..299) throw IOException("fout ${r.code}")
            val arr = JSONArray(r.body)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Wacht(
                    o.getString("person"), o.getString("app"),
                    try { Instant.parse(o.getString("since")).toEpochMilli() } catch (e: Exception) { System.currentTimeMillis() },
                    if (o.isNull("last_message")) null else o.optString("last_message").ifBlank { null },
                )
            }
        } catch (e: NeedLogin) {
            null
        }
    }

    /** Regel weghalen (zelfde als het kruisje in de planner). */
    fun weg(context: Context, w: Wacht) = Outbox.answered(w.person, w.app, context)
}

// ---------- Tijd ----------
object Tijd {
    private val zone get() = ZoneId.systemDefault()
    fun uur(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    private val DAGEN = listOf("ma", "di", "wo", "do", "vr", "za", "zo")
    fun dagKort(ms: Long): String = DAGEN[Instant.ofEpochMilli(ms).atZone(zone).dayOfWeek.value - 1]
    fun wachtTijd(ms: Long): String {
        val min = ((System.currentTimeMillis() - ms) / 60000).coerceAtLeast(0)
        return when {
            min < 60 -> "$min min"
            min < 48 * 60 -> "${min / 60} uur"
            else -> "${min / (24 * 60)} dagen"
        }
    }
    fun over(ms: Long): String {
        val min = ((ms - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
        return if (min < 60) "${min}m" else "${min / 60}u ${min % 60}m"
    }
}
