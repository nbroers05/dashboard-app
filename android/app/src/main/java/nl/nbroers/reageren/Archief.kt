package nl.nbroers.reageren

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Het privé data-archief (nbroers05/dashboard-data) voor het Data-tabje (sinds 0.6.0): kerncijfers en Memory Lane lezen
 * (data/aggregates/), check-ins toevoegen (data/self-report/<jjjj-mm>.csv, zelfde kolommen als widgets/checkin.js).
 * Het token komt uit vault.json van het dashboard (de inlogbundel, versleuteld met de wachtzin: PBKDF2-SHA256 → AES-GCM),
 * één keer ontsleuteld bij het koppelen en daarna alleen versleuteld met een sleutel van het toestel bewaard.
 */
object Archief {
    private const val REPO = "nbroers05/dashboard-data"
    private const val API = "https://api.github.com/repos/$REPO/contents/"
    private const val TOKEN = "archief.token"
    private const val WACHTRIJ = "checkin.queue"

    fun heeftToken(context: Context) = Prefs(context).get(TOKEN) != null
    fun vergeet(context: Context) = Prefs(context).put(TOKEN, null)

    /** vault.json ontsleutelen met de wachtzin en het archief-token bewaren. Geeft een foutmelding of null. */
    fun koppel(context: Context, wachtzin: String): String? = try {
        val box = JSONObject(Openbaar.get("https://nbroers05.github.io/Dashboard/vault.json?t=${System.currentTimeMillis()}"))
        val salt = Base64.decode(box.getString("salt"), Base64.DEFAULT)
        val iv = Base64.decode(box.getString("iv"), Base64.DEFAULT)
        val data = Base64.decode(box.getString("data"), Base64.DEFAULT)
        val bits = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(wachtzin.toCharArray(), salt, box.optInt("iter", 600000), 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(bits, "AES"), GCMParameterSpec(128, iv))
        val payload = JSONObject(String(cipher.doFinal(data)))
        // In de bundel staan de waarden zoals in localStorage: het token als JSON-tekst ("\"github_pat_…\"")
        val raw = payload.optJSONObject("keys")?.optString("data.token").orEmpty()
        val token = if (raw.startsWith("\"")) JSONArray("[$raw]").getString(0) else raw
        if (token.isBlank()) "The login bundle has no archive token."
        else { Prefs(context).put(TOKEN, Vault.seal(token)); null }
    } catch (e: javax.crypto.AEADBadTagException) {
        "That passphrase is wrong."
    } catch (e: Exception) {
        "Linking the archive failed (${e.javaClass.simpleName})."
    }

    private fun token(context: Context): String = Prefs(context).get(TOKEN)?.let { Vault.open(it) } ?: throw NeedLogin()

    private fun call(context: Context, method: String, path: String, body: String? = null, raw: Boolean = false): HttpResult {
        val c = URL(API + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Authorization", "Bearer ${token(context)}")
            c.setRequestProperty("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "dashboard-app (telefoon)")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            return HttpResult(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    /** Bestand als tekst, of null als het niet bestaat. */
    @Throws(IOException::class, NeedLogin::class)
    fun lees(context: Context, path: String): String? {
        val r = call(context, "GET", path, raw = true)
        if (r.code == 404) return null
        if (r.code == 401) throw NeedLogin()
        if (r.code != 200) throw IOException("GitHub error ${r.code}")
        return r.body
    }

    // ---------- Check-in ----------
    private val COLS = listOf("timestamp_utc", "timestamp_local", "source", "collected_at", "id", "mood", "energy", "focus", "stress", "note", "trigger", "prompt_utc", "delay_min", "device")
    private val AMS = ZoneId.of("Europe/Amsterdam")
    private fun utc(i: Instant) = DateTimeFormatter.ISO_INSTANT.format(i.truncatedTo(ChronoUnit.SECONDS))
    private fun lokaal(i: Instant) = ZonedDateTime.ofInstant(i.truncatedTo(ChronoUnit.SECONDS), AMS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** Nieuwe check-in in de wachtrij zetten en meteen proberen te versturen. Geeft true als hij in het archief staat. */
    fun checkin(context: Context, waarden: Map<String, Int>, notitie: String): Boolean {
        val nu = Instant.now()
        val rij = JSONObject()
            .put("timestamp_utc", utc(nu)).put("timestamp_local", lokaal(nu)).put("source", "self-report").put("collected_at", utc(nu))
            .put("id", UUID.randomUUID().toString()).put("note", notitie.replace(Regex("\\s*\\n\\s*"), " ").trim())
            .put("trigger", "manual").put("prompt_utc", "").put("delay_min", "").put("device", "phone")
        for ((k, v) in waarden) rij.put(k, v)
        val prefs = Prefs(context)
        val rijen = try { JSONArray(prefs.get(WACHTRIJ) ?: "[]") } catch (e: Exception) { JSONArray() }
        rijen.put(rij)
        prefs.put(WACHTRIJ, rijen.toString())
        return verstuur(context)
    }

    fun wachtend(context: Context) = try { JSONArray(Prefs(context).get(WACHTRIJ) ?: "[]").length() } catch (e: Exception) { 0 }

    /** Wachtrij naar het archief (één commit per maandbestand). true = niks meer in de wachtrij. */
    @Synchronized
    fun verstuur(context: Context): Boolean {
        val prefs = Prefs(context)
        val rijen = try { JSONArray(prefs.get(WACHTRIJ) ?: "[]") } catch (e: Exception) { JSONArray() }
        if (rijen.length() == 0) return true
        if (!heeftToken(context)) return false
        val perMaand = (0 until rijen.length()).map { rijen.getJSONObject(it) }.groupBy { it.getString("timestamp_local").take(7) }
        val over = mutableListOf<JSONObject>()
        for ((maand, lijst) in perMaand) {
            if (!voegToe(context, "data/self-report/$maand.csv", lijst)) over += lijst
        }
        prefs.put(WACHTRIJ, JSONArray(over).toString())
        return over.isEmpty()
    }

    private fun esc(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (Regex("[\",\\n\\r]").containsMatchIn(s)) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun voegToe(context: Context, path: String, rijen: List<JSONObject>): Boolean {
        repeat(3) {
            try {
                val cur = call(context, "GET", path)
                val (tekst, sha) = if (cur.code == 200) {
                    val j = JSONObject(cur.body)
                    String(Base64.decode(j.optString("content"), Base64.DEFAULT)) to j.getString("sha")
                } else if (cur.code == 404) "" to null else return false
                val nieuw: String
                if (tekst.isNotBlank()) {
                    val cols = tekst.lineSequence().first().split(",").map { it.trim('"') }.toMutableList()
                    for (c in COLS) if (c !in cols) cols += c
                    val todo = rijen.filter { !tekst.contains(",${it.getString("id")},") }
                    if (todo.isEmpty()) return true
                    val body = tekst.lines().drop(1).filter { it.isNotBlank() }.joinToString("\n")
                    nieuw = cols.joinToString(",") + "\n" + (if (body.isNotEmpty()) "$body\n" else "") + todo.joinToString("\n") { r -> cols.joinToString(",") { esc(r.opt(it)) } } + "\n"
                } else {
                    nieuw = COLS.joinToString(",") + "\n" + rijen.joinToString("\n") { r -> COLS.joinToString(",") { esc(r.opt(it)) } } + "\n"
                }
                val put = JSONObject().put("message", "Check-in ${rijen.first().getString("timestamp_utc")} (phone)")
                    .put("content", Base64.encodeToString(nieuw.toByteArray(), Base64.NO_WRAP))
                if (sha != null) put.put("sha", sha)
                val r = call(context, "PUT", path, put.toString())
                if (r.code in 200..201) return true
                if (r.code != 409 && r.code != 422) return false
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    /** Aantal check-ins vandaag (deze maand uit het archief + wat nog in de wachtrij staat). */
    fun checkinsVandaag(context: Context): Int {
        val vandaag = java.time.LocalDate.now(AMS).toString()
        val tekst = try { lees(context, "data/self-report/${vandaag.take(7)}.csv") } catch (e: Exception) { null } ?: ""
        val kop = tekst.lineSequence().firstOrNull()?.split(",")?.map { it.trim('"') } ?: emptyList()
        val i = kop.indexOf("timestamp_local")
        val uitArchief = if (i < 0) 0 else tekst.lines().drop(1).count { it.split(",").getOrNull(i)?.startsWith(vandaag) == true }
        val wachtrij = try { JSONArray(Prefs(context).get(WACHTRIJ) ?: "[]") } catch (e: Exception) { JSONArray() }
        return uitArchief + (0 until wachtrij.length()).count { wachtrij.getJSONObject(it).getString("timestamp_local").startsWith(vandaag) }
    }
}
