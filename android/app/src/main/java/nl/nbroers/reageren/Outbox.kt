package nl.nbroers.reageren

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/**
 * Wachtrij naar Supabase, die ook een herstart overleeft. Per persoon staat hier hooguit het laatste bericht, en alleen tot het verstuurd is.
 * Geen internet of even een fout: over 30 seconden opnieuw, of zodra er weer netwerk is.
 */
object Outbox {
    private const val KEY = "outbox"
    private const val MAX = 300
    private const val RETRY = 30000L
    private val thread = HandlerThread("outbox").apply { start() }
    private val handler = Handler(thread.looper)
    private var retry: Runnable? = null

    private fun load(context: Context): MutableList<JSONObject> {
        val raw = Prefs(context).get(KEY) ?: return mutableListOf()
        val array = try { JSONArray(raw) } catch (e: Exception) { return mutableListOf() }
        return MutableList(array.length()) { array.getJSONObject(it) }
    }

    private fun save(context: Context, items: List<JSONObject>) {
        Prefs(context).put(KEY, JSONArray(items).toString())
    }

    fun waiting(context: Context) = load(context).size

    fun waiting(person: String, app: String, since: Long, message: String?, context: Context) {
        val item = JSONObject().put("op", "up").put("person", person).put("app", app).put("since", Instant.ofEpochMilli(since).toString())
        if (message != null) item.put("msg", message)
        add(context, item)
    }

    fun answered(person: String, app: String, context: Context) {
        add(context, JSONObject().put("op", "del").put("person", person).put("app", app))
    }

    private fun add(context: Context, item: JSONObject) {
        val c = context.applicationContext
        handler.post {
            val items = load(c)
            val up = item.getString("op") == "up"
            val earlier = items.lastOrNull { it.getString("person") == item.getString("person") && it.getString("app") == item.getString("app") }
            if (up && earlier?.getString("op") == "up") {
                // Wacht er al een regel voor deze persoon: die houdt zijn tijd en krijgt het nieuwste bericht
                earlier.remove("msg")
                if (item.has("msg")) earlier.put("msg", item.getString("msg"))
            } else {
                if (!up) earlier?.remove("msg")
                items.add(item)
            }
            while (items.size > MAX) items.removeAt(0)
            save(c, items)
            flush(c)
        }
    }

    fun kick(context: Context) {
        val c = context.applicationContext
        handler.post { flush(c) }
    }

    private fun later(context: Context) {
        retry?.let { handler.removeCallbacks(it) }
        retry = Runnable { flush(context) }.also { handler.postDelayed(it, RETRY) }
    }

    private enum class Result { DONE, RETRY, STOP }

    private fun flush(context: Context) {
        retry?.let { handler.removeCallbacks(it) }
        val prefs = Prefs(context)
        while (true) {
            val items = load(context)
            if (items.isEmpty()) return
            when (send(context, items[0])) {
                Result.DONE -> {
                    items.removeAt(0)
                    save(context, items)
                    prefs.put("status.sent", System.currentTimeMillis().toString())
                    prefs.put("status.error", null)
                }
                Result.RETRY -> return later(context)
                Result.STOP -> return
            }
        }
    }

    private fun send(context: Context, item: JSONObject): Result {
        val prefs = Prefs(context)
        return try {
            var result = request(context, item)
            if (result.code == 401) {
                Supabase.forgetAccess()
                result = request(context, item)
            }
            when {
                result.code in 200..299 || result.code == 409 -> Result.DONE
                result.code == 404 -> { prefs.put("status.error", "Tabel niet gevonden: draai supabase.sql in Supabase."); Result.RETRY }
                // De regel zelf staat er dan al; alleen het bericht kan nog nergens heen
                result.body.contains("last_message") -> { prefs.put("status.error", "Laatste bericht werkt pas na supabase.sql opnieuw draaien."); Result.DONE }
                result.code in 400..499 -> { prefs.put("status.error", "Supabase weigerde een regel (fout ${result.code})."); Result.DONE }
                else -> { prefs.put("status.error", "Supabase gaf fout ${result.code}, probeert opnieuw."); Result.RETRY }
            }
        } catch (e: NeedLogin) {
            prefs.put("status.error", "Opnieuw inloggen nodig.")
            Result.STOP
        } catch (e: IOException) {
            prefs.put("status.error", "Geen verbinding, probeert opnieuw.")
            Result.RETRY
        }
    }

    private fun request(context: Context, item: JSONObject): HttpResult {
        val auth = mapOf("Authorization" to "Bearer ${Supabase.token(context)}")
        val table = "$SUPABASE_URL/rest/v1/planner_replies"
        val person = item.getString("person")
        val app = item.getString("app")
        return if (item.getString("op") == "up") {
            // Bestaat de regel al, dan blijft hij staan met de oude tijd: "sinds" is het eerste onbeantwoorde bericht
            val body = JSONObject().put("person", person).put("app", app).put("since", item.getString("since")).toString()
            val row = Supabase.call("POST", "$table?on_conflict=user_id,app,person", auth + ("Prefer" to "resolution=ignore-duplicates,return=minimal"), body)
            if (row.code !in 200..299 && row.code != 409) return row
            // Daarna het bericht overschrijven. Zonder "since" in de aanvraag blijft de tijd staan; geen tekst = een oud bericht wissen
            val message = JSONObject().put("person", person).put("app", app)
                .put("last_message", if (item.has("msg")) item.getString("msg") else JSONObject.NULL).toString()
            Supabase.call("POST", "$table?on_conflict=user_id,app,person", auth + ("Prefer" to "resolution=merge-duplicates,return=minimal"), message)
        } else {
            Supabase.call("DELETE", "$table?app=eq.${Supabase.encode(app)}&person=eq.${Supabase.encode(person)}", auth + ("Prefer" to "return=minimal"), null)
        }
    }
}
