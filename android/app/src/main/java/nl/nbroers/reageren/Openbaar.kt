package nl.nbroers.reageren

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Openbare bronnen voor Media (sinds 0.5.0): de data-bestanden van het dashboard op GitHub Pages (Letterboxd, vrienden,
 * bioscoop, feeds; de Action schrijft ze), F1 van Jolpica en songteksten van LRCLIB. Geen sleutels nodig.
 */
object Openbaar {
    private const val DATA = "https://nbroers05.github.io/Dashboard/data"

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "dashboard-app (telefoon)")
            if (c.responseCode != 200) throw IOException("fout ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- Letterboxd ----------
    data class Log(val titel: String, val jaar: Int?, val cijfer: Double?, val gekeken: String, val poster: String?, val wie: String? = null)

    private fun logs(arr: JSONArray?, namen: Map<String, String> = emptyMap()) = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }.map {
        Log(it.optString("title"), it.optInt("year").takeIf { y -> y > 0 }, if (it.isNull("rating")) null else it.optDouble("rating"),
            it.optString("watched"), it.optString("poster").ifBlank { null },
            it.optString("name").ifBlank { null } ?: namen[it.optString("user")])
    }

    fun gekeken(): List<Log> = logs(JSONObject(get("$DATA/letterboxd.json?t=${System.currentTimeMillis() / 900000}")).optJSONArray("logs"))

    /** Laatste film per vriend, nieuwste eerst. */
    fun vrienden(): List<Log> {
        val j = JSONObject(get("$DATA/friends.json?t=${System.currentTimeMillis() / 900000}"))
        val namen = HashMap<String, String>()
        j.optJSONArray("friends")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { namen[it.optString("user")] = it.optString("name") } }
        return logs(j.optJSONArray("logs"), namen).sortedByDescending { it.gekeken }.distinctBy { it.wie }
    }

    // ---------- Bioscoop (alleen LantarenVenster, zie cinema.py) ----------
    data class Voorstelling(val bios: String, val volgende: Long?, val tot: String?)
    fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").replace("&", "and").replace(Regex("[^a-z0-9]+"), "")
    fun bios(titel: String): Voorstelling? = try {
        val films = JSONObject(get("$DATA/cinema.json?t=${System.currentTimeMillis() / 3600000}")).optJSONArray("films") ?: JSONArray()
        val key = norm(titel)
        (0 until films.length()).map { films.getJSONObject(it) }.firstOrNull { norm(it.optString("title")) == key }?.optJSONArray("shows")?.optJSONObject(0)?.let {
            Voorstelling(it.optString("cinema"), it.optString("next").ifBlank { null }?.let { n -> try { OffsetDateTime.parse(n).toInstant().toEpochMilli() } catch (e: Exception) { null } }, it.optString("until").ifBlank { null })
        }
    } catch (e: Exception) { null }

    // ---------- Feeds ----------
    data class Bericht(val bron: String, val titel: String, val link: String, val tijd: Long?)
    fun feeds(): Map<String, List<Bericht>> {
        val j = JSONObject(get("$DATA/feeds.json?t=${System.currentTimeMillis() / 600000}"))
        return listOf("news" to "nieuws", "film" to "film", "youtube" to "youtube").associate { (key, naam) ->
            val groepen = j.optJSONArray(key) ?: JSONArray()
            naam to (0 until groepen.length()).flatMap { g ->
                val groep = groepen.getJSONObject(g)
                val items = groep.optJSONArray("items") ?: JSONArray()
                (0 until items.length()).map { items.getJSONObject(it) }.map {
                    Bericht(groep.optString("name"), it.optString("title"), it.optString("link"),
                        try { OffsetDateTime.parse(it.optString("time")).toInstant().toEpochMilli() } catch (e: Exception) { null })
                }
            }.sortedByDescending { it.tijd ?: 0 }.take(20)
        }
    }

    // ---------- F1 (Jolpica, zelfde als widgets/f1.js) ----------
    data class Sessie(val gp: String, val naam: String, val start: Long, val eind: Long)
    private val SESSIES = listOf("FirstPractice" to ("VT1" to 60), "SecondPractice" to ("VT2" to 60), "ThirdPractice" to ("VT3" to 60),
        "SprintQualifying" to ("Sprint-kwali" to 45), "SprintShootout" to ("Sprint-kwali" to 45), "Sprint" to ("Sprint" to 45), "Qualifying" to ("Kwali" to 60))
    fun f1(): List<Sessie> {
        fun jaar(y: Int): List<Sessie> {
            val races = JSONObject(get("https://api.jolpi.ca/ergast/f1/$y/races/?format=json&limit=100")).getJSONObject("MRData").getJSONObject("RaceTable").optJSONArray("Races") ?: JSONArray()
            val out = mutableListOf<Sessie>()
            for (i in 0 until races.length()) {
                val r = races.getJSONObject(i)
                val gp = r.optString("raceName").replace(Regex("\\s*Grand Prix$", RegexOption.IGNORE_CASE), "")
                fun add(o: JSONObject?, naam: String, min: Int) {
                    if (o == null || !o.has("date")) return
                    val start = Instant.parse("${o.getString("date")}T${o.optString("time").ifBlank { "12:00:00Z" }}").toEpochMilli()
                    out += Sessie(gp, naam, start, start + min * 60000L)
                }
                for ((k, v) in SESSIES) add(r.optJSONObject(k), v.first, v.second)
                add(r, "Race", 120)
            }
            return out.sortedBy { it.start }
        }
        val y = java.time.LocalDate.now().year
        val nu = System.currentTimeMillis()
        val dit = jaar(y)
        return if (dit.any { it.eind > nu }) dit else dit + jaar(y + 1)
    }

    // ---------- Songtekst (LRCLIB) ----------
    /** Regels met tijd in ms; leeg als er geen tekst met tijden is. */
    fun songtekst(artiest: String, titel: String, album: String, duurMs: Long): List<Pair<Long, String>> {
        val q = "artist_name=${enc(artiest)}&track_name=${enc(titel)}" + (if (album.isNotBlank()) "&album_name=${enc(album)}" else "") +
            (if (duurMs > 0) "&duration=${duurMs / 1000}" else "")
        val body = try { get("https://lrclib.net/api/get?$q") } catch (e: IOException) {
            val arr = JSONArray(get("https://lrclib.net/api/search?artist_name=${enc(artiest)}&track_name=${enc(titel)}"))
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { !it.isNull("syncedLyrics") }?.toString() ?: return emptyList()
        }
        val j = JSONObject(body)
        if (j.isNull("syncedLyrics")) return emptyList()
        val synced = j.optString("syncedLyrics").ifBlank { return emptyList() }
        val re = Regex("""\[(\d+):(\d+(?:\.\d+)?)]\s*(.*)""")
        return synced.lines().mapNotNull { l ->
            re.find(l)?.let { m -> ((m.groupValues[1].toLong() * 60000) + (m.groupValues[2].toDouble() * 1000).toLong()) to m.groupValues[3].trim() }
        }.filter { it.second.isNotEmpty() }
    }
}

// ---------- Plaatjes van internet (klein, in het geheugen bewaard) ----------
object Plaatjes {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun haal(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000
            val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
            c.disconnect()
            bmp?.also { cache.put(url, it) }
        } catch (e: Exception) { null }
    }
}

/** Plaatje van een adres; zolang het laadt (of als het mislukt) een vlak in de opgegeven kleur. */
@Composable
fun NetBeeld(url: String?, modifier: Modifier, kleur: Color) {
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { if (url != null) bmp = withContext(Dispatchers.IO) { Plaatjes.haal(url) } }
    Box(modifier.background(kleur)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}
