package nl.nbroers.reageren

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The dashboard's Supabase (since 0.4.0), separate from the planner's. Logging in works like in the dashboard
 * (sync.js): the password is PBKDF2-SHA256(passphrase, "dashboard-supabase-v1:<url>", 600,000 rounds), 32 bytes base64.
 * The passphrase itself is never stored; only that derived password, encrypted with a key of the device.
 * In it: the status the dashboard prepares for the phone (_phone.status, old: _telefoon.stand), the AH wage
 * (_phone.wage, old: _telefoon.loon), the to-do (todo.items) and the Canvas check marks (dashboard_canvas_done).
 */
object Dash {
    const val URL = "https://pakweewdaxaaxupdpfyu.supabase.co"
    private const val KEY = "sb_publishable_htAR8KMBf2SJaQJ-qY9AUw_hxtzTaDR"
    private const val EMAIL = "broersnoah@gmail.com"
    private const val PW = "dash.pw"
    private var access: String? = null
    private var expires = 0L
    private var userId: String? = null
    private const val WRONG_PASSPHRASE = "That passphrase is wrong."

    fun gekoppeld(context: Context) = Prefs(context).get(PW) != null

    private fun call(method: String, path: String, body: String?, extra: Map<String, String> = emptyMap(), auth: Boolean = true, context: Context? = null): HttpResult {
        val headers = HashMap(extra)
        headers["apikey"] = KEY
        if (auth) headers["Authorization"] = "Bearer ${token(context!!)}"
        val r = Supabase.call(method, "$URL$path", headers, body)
        return r
    }

    /** Passphrase → password, log in once to check. Returns an error message or null. Takes a few seconds. */
    fun koppel(context: Context, wachtzin: String): String? {
        val spec = PBEKeySpec(wachtzin.toCharArray(), "dashboard-supabase-v1:$URL".toByteArray(), 600000, 256)
        val bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val pw = Base64.encodeToString(bits, Base64.NO_WRAP).trimEnd('=')
        return try {
            val r = login(pw)
            when {
                r == null -> {
                    Prefs(context).put(PW, Vault.seal(pw))
                    // The same passphrase also opens the login bundle with the archive token (Data tab); if that fails, the rest still works
                    Archief.koppel(context, wachtzin)
                    null
                }
                else -> r
            }
        } catch (e: IOException) {
            "No connection to Supabase."
        }
    }

    fun ontkoppel(context: Context) {
        Prefs(context).put(PW, null)
        Archief.vergeet(context)
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
        return if (r.code == 400) WRONG_PASSPHRASE else "Logging in failed (error ${r.code})."
    }

    @Synchronized
    @Throws(IOException::class, NeedLogin::class)
    private fun token(context: Context): String {
        access?.let { if (System.currentTimeMillis() < expires) return it }
        val pw = Prefs(context).get(PW)?.let { Vault.open(it) } ?: throw NeedLogin()
        val fout = login(pw)
        if (fout != null) { if (fout == WRONG_PASSPHRASE) throw NeedLogin(); throw IOException(fout) }
        return access!!
    }

    @Throws(IOException::class, NeedLogin::class)
    private fun rest(context: Context, method: String, path: String, body: String? = null, prefer: String? = null): String {
        val extra = if (prefer != null) mapOf("Prefer" to prefer) else emptyMap()
        var r = call(method, "/rest/v1/$path", body, extra, context = context)
        if (r.code == 401) { synchronized(this) { access = null }; r = call(method, "/rest/v1/$path", body, extra, context = context) }
        if (r.code !in 200..299) throw IOException("Supabase error ${r.code}")
        return r.body
    }

    // ---------- Reading ----------
    /** Everything Today needs in one go: status, wage, to-do and Canvas check marks. null = not linked.
     *  New keys first (_phone.status, _phone.wage, since dashboard 1.7.0); the old Dutch keys as fallback. */
    @Throws(IOException::class)
    fun haal(context: Context): Gedeeld? {
        if (!gekoppeld(context)) return null
        return try {
            val rows = JSONArray(rest(context, "GET", "dashboard_settings?select=key,value&key=in.(%22_phone.status%22,%22_phone.wage%22,%22_telefoon.stand%22,%22_telefoon.loon%22,%22todo.items%22)"))
            val byKey = HashMap<String, Any?>()
            for (i in 0 until rows.length()) rows.getJSONObject(i).let { byKey[it.getString("key")] = it.opt("value") }
            val done = JSONArray(rest(context, "GET", "dashboard_canvas_done?select=item_id,done"))
            val doneSet = HashSet<String>()
            for (i in 0 until done.length()) done.getJSONObject(i).let { if (it.getBoolean("done")) doneSet += it.getString("item_id") }
            Gedeeld((byKey["_phone.status"] ?: byKey["_telefoon.stand"]) as? JSONObject, (byKey["_phone.wage"] ?: byKey["_telefoon.loon"]) as? JSONObject, byKey["todo.items"] as? JSONArray, doneSet)
        } catch (e: NeedLogin) {
            null
        }
    }

    // ---------- Writing ----------
    /** Check off a Canvas assignment (same table as the dashboard; newest wins). */
    @Throws(IOException::class, NeedLogin::class)
    fun zetIngeleverd(context: Context, id: String, ingeleverd: Boolean) {
        token(context)
        val row = JSONObject().put("user_id", userId).put("item_id", id).put("done", ingeleverd).put("changed_at", System.currentTimeMillis())
        rest(context, "POST", "dashboard_canvas_done?on_conflict=user_id,item_id", JSONArray().put(row).toString(), "resolution=merge-duplicates,return=minimal")
    }

    /** Write the whole to-do list (like sync.js: whole list, newest wins). */
    @Throws(IOException::class, NeedLogin::class)
    fun zetTodo(context: Context, items: JSONArray) {
        token(context)
        val row = JSONObject().put("user_id", userId).put("key", "todo.items").put("value", items)
            .put("updated_at", java.time.Instant.now().toString())
        rest(context, "POST", "dashboard_settings?on_conflict=user_id,key", JSONArray().put(row).toString(), "resolution=merge-duplicates,return=minimal")
    }
}

/** What the dashboard prepares for the phone, plus the to-do and the check marks. */
class Gedeeld(val stand: JSONObject?, val loon: JSONObject?, val todo: JSONArray?, val ingeleverd: Set<String>) {
    data class Opdracht(val id: String, val titel: String, val vak: String, val due: Long)
    data class Serie(val titel: String, val over: Int, val nieuw: Int)

    val opdrachten: List<Opdracht> get() {
        val arr = stand?.optJSONObject("canvas")?.optJSONArray("items") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }.map { Opdracht(it.getString("id"), it.optString("title"), it.optString("course"), it.optLong("due")) }
    }
    val series: List<Serie> get() {
        val arr = stand?.optJSONObject("episodes")?.optJSONArray("rows") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }.map { Serie(it.optString("title"), it.optInt("left"), it.optInt("new", it.optInt("nieuw"))) }
    }
    val episodesTijd: Long? get() = stand?.optJSONObject("episodes")?.optLong("at")?.takeIf { it > 0 }
    val mail: Int? get() = stand?.optJSONObject("mail")?.let { if (it.has("count")) it.optInt("count") else null }
    val mailVan: String? get() = stand?.optJSONObject("mail")?.optJSONArray("top")?.optJSONObject(0)?.optString("from")
    val checkinsVandaag: Int? get() = stand?.optJSONObject("checkin")?.let {
        if (it.optString("day") == java.time.LocalDate.now().toString()) it.optInt("count") else 0
    }
    // Phase B (dashboard 1.6.0): film tonight and Spotify month
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
