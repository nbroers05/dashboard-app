package nl.nbroers.reageren

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * De Supabase van het dashboard (sinds 0.4.0), los van die van de planner. Inloggen gaat net als in het dashboard
 * (sync.js): het wachtwoord is PBKDF2-SHA256(wachtzin, "dashboard-supabase-v1:<url>", 600.000 rondes), 32 bytes base64.
 * De wachtzin zelf wordt nergens bewaard; alleen dat afgeleide wachtwoord, versleuteld met een sleutel van het toestel.
 * Daar staan: de stand die het dashboard voor de telefoon klaarzet (_telefoon.stand), het AH-loon (_telefoon.loon),
 * de to-do (todo.items) en de Canvas-vinkjes (dashboard_canvas_done).
 */
object Dash {
    const val URL = "https://pakweewdaxaaxupdpfyu.supabase.co"
    private const val KEY = "sb_publishable_htAR8KMBf2SJaQJ-qY9AUw_hxtzTaDR"
    private const val EMAIL = "broersnoah@gmail.com"
    private const val PW = "dash.pw"
    private var access: String? = null
    private var expires = 0L
    private var userId: String? = null

    fun gekoppeld(context: Context) = Prefs(context).get(PW) != null

    private fun call(method: String, path: String, body: String?, extra: Map<String, String> = emptyMap(), auth: Boolean = true, context: Context? = null): HttpResult {
        val headers = HashMap(extra)
        headers["apikey"] = KEY
        if (auth) headers["Authorization"] = "Bearer ${token(context!!)}"
        val r = Supabase.call(method, "$URL$path", headers, body)
        return r
    }

    /** Wachtzin → wachtwoord, één keer inloggen om te controleren. Geeft een foutmelding of null. Duurt een paar seconden. */
    fun koppel(context: Context, wachtzin: String): String? {
        val spec = PBEKeySpec(wachtzin.toCharArray(), "dashboard-supabase-v1:$URL".toByteArray(), 600000, 256)
        val bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val pw = Base64.encodeToString(bits, Base64.NO_WRAP).trimEnd('=')
        return try {
            val r = login(pw)
            when {
                r == null -> { Prefs(context).put(PW, Vault.seal(pw)); null }
                else -> r
            }
        } catch (e: IOException) {
            "Geen verbinding met Supabase."
        }
    }

    fun ontkoppel(context: Context) {
        Prefs(context).put(PW, null)
        synchronized(this) { access = null; expires = 0; userId = null }
    }

    @Synchronized
    private fun login(pw: String): String? {
        val body = JSONObject().put("email", EMAIL).put("password", pw).toString()
        val r = Supabase.call("POST", "$URL/auth/v1/token?grant_type=password", mapOf("apikey" to KEY), body)
        if (r.code == 200) {
            val j = JSONObject(r.body)
            access = j.getString("access_token")
            expires = System.currentTimeMillis() + j.getLong("expires_in") * 1000 - 60000
            userId = j.optJSONObject("user")?.optString("id")
            return null
        }
        return if (r.code == 400) "Die wachtzin klopt niet." else "Inloggen lukt niet (fout ${r.code})."
    }

    @Synchronized
    @Throws(IOException::class, NeedLogin::class)
    private fun token(context: Context): String {
        access?.let { if (System.currentTimeMillis() < expires) return it }
        val pw = Prefs(context).get(PW)?.let { Vault.open(it) } ?: throw NeedLogin()
        val fout = login(pw)
        if (fout != null) { if (fout.startsWith("Die wachtzin")) throw NeedLogin(); throw IOException(fout) }
        return access!!
    }

    @Throws(IOException::class, NeedLogin::class)
    private fun rest(context: Context, method: String, path: String, body: String? = null, prefer: String? = null): String {
        val extra = if (prefer != null) mapOf("Prefer" to prefer) else emptyMap()
        var r = call(method, "/rest/v1/$path", body, extra, context = context)
        if (r.code == 401) { synchronized(this) { access = null }; r = call(method, "/rest/v1/$path", body, extra, context = context) }
        if (r.code !in 200..299) throw IOException("Supabase-fout ${r.code}")
        return r.body
    }

    // ---------- Lezen ----------
    /** Alles wat Vandaag nodig heeft in één keer: stand, loon, to-do en Canvas-vinkjes. null = niet gekoppeld. */
    @Throws(IOException::class)
    fun haal(context: Context): Gedeeld? {
        if (!gekoppeld(context)) return null
        return try {
            val rows = JSONArray(rest(context, "GET", "dashboard_settings?select=key,value&key=in.(%22_telefoon.stand%22,%22_telefoon.loon%22,%22todo.items%22)"))
            val byKey = HashMap<String, Any?>()
            for (i in 0 until rows.length()) rows.getJSONObject(i).let { byKey[it.getString("key")] = it.opt("value") }
            val done = JSONArray(rest(context, "GET", "dashboard_canvas_done?select=item_id,done"))
            val doneSet = HashSet<String>()
            for (i in 0 until done.length()) done.getJSONObject(i).let { if (it.getBoolean("done")) doneSet += it.getString("item_id") }
            Gedeeld(byKey["_telefoon.stand"] as? JSONObject, byKey["_telefoon.loon"] as? JSONObject, byKey["todo.items"] as? JSONArray, doneSet)
        } catch (e: NeedLogin) {
            null
        }
    }

    // ---------- Schrijven ----------
    /** Canvas-opdracht afvinken (zelfde tabel als het dashboard; nieuwste wint). */
    @Throws(IOException::class, NeedLogin::class)
    fun zetIngeleverd(context: Context, id: String, ingeleverd: Boolean) {
        token(context)
        val row = JSONObject().put("user_id", userId).put("item_id", id).put("done", ingeleverd).put("changed_at", System.currentTimeMillis())
        rest(context, "POST", "dashboard_canvas_done?on_conflict=user_id,item_id", JSONArray().put(row).toString(), "resolution=merge-duplicates,return=minimal")
    }

    /** De hele to-do-lijst wegschrijven (zoals sync.js: hele lijst, nieuwste wint). */
    @Throws(IOException::class, NeedLogin::class)
    fun zetTodo(context: Context, items: JSONArray) {
        token(context)
        val row = JSONObject().put("user_id", userId).put("key", "todo.items").put("value", items)
            .put("updated_at", java.time.Instant.now().toString())
        rest(context, "POST", "dashboard_settings?on_conflict=user_id,key", JSONArray().put(row).toString(), "resolution=merge-duplicates,return=minimal")
    }
}

/** Wat het dashboard voor de telefoon klaarzet, plus de to-do en de vinkjes. */
class Gedeeld(val stand: JSONObject?, val loon: JSONObject?, val todo: JSONArray?, val ingeleverd: Set<String>) {
    data class Opdracht(val id: String, val titel: String, val vak: String, val due: Long)
    data class Serie(val titel: String, val over: Int, val nieuw: Int)

    val opdrachten: List<Opdracht> get() {
        val arr = stand?.optJSONObject("canvas")?.optJSONArray("items") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }.map { Opdracht(it.getString("id"), it.optString("title"), it.optString("course"), it.optLong("due")) }
    }
    val series: List<Serie> get() {
        val arr = stand?.optJSONObject("episodes")?.optJSONArray("rows") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }.map { Serie(it.optString("title"), it.optInt("left"), it.optInt("nieuw")) }
    }
    val episodesTijd: Long? get() = stand?.optJSONObject("episodes")?.optLong("at")?.takeIf { it > 0 }
    val mail: Int? get() = stand?.optJSONObject("mail")?.let { if (it.has("count")) it.optInt("count") else null }
    val mailVan: String? get() = stand?.optJSONObject("mail")?.optJSONArray("top")?.optJSONObject(0)?.optString("from")
    val checkinsVandaag: Int? get() = stand?.optJSONObject("checkin")?.let {
        if (it.optString("day") == java.time.LocalDate.now().toString()) it.optInt("count") else 0
    }
    // Fase B (dashboard 1.6.0): film van vanavond en Spotify-maand
    data class Film(val titel: String, val jaar: String?, val verwacht: Double?, val poster: String?)
    data class Top(val naam: String, val sub: String, val beeld: String?)
    val film: Film? get() = stand?.optJSONObject("film")?.let {
        if (!it.has("title")) null else Film(it.optString("title"), it.optString("year").ifBlank { null }?.takeIf { y -> y != "null" },
            if (it.isNull("predicted")) null else it.optDouble("predicted"), if (it.isNull("poster")) null else it.optString("poster"))
    }
    private fun top(key: String): List<Top> {
        val arr = stand?.optJSONObject("spotify")?.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { Top(it.optString("name"), it.optString("sub"), if (it.isNull("img")) null else it.optString("img")) }
    }
    val nummers: List<Top> get() = top("tracks")
    val artiesten: List<Top> get() = top("artists")

    val todoOpen: Int get() = todo?.let { t -> (0 until t.length()).count { !t.getJSONObject(it).optBoolean("done") } } ?: 0
}
