package nl.nbroers.reageren

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * De planner (Supabase van de planner, zelfde account als Nog reageren), sinds 0.7.0.
 * planner_tasks: taken (kind task, met of zonder dag/tijd) en tijdblokken (kind block, ook die van de Standaardweek:
 * module_state auto = gestippeld, pinned = vastgezet, skipped = deze week niet). planner_habits + planner_habit_checks:
 * gewoontes met een vinkje per dag. planner_subjects: vakken met kleur.
 */
object PlannerData {
    data class Taak(
        val id: String, val blok: Boolean, val titel: String, val af: Boolean, val dag: LocalDate?,
        val startMin: Int?, val duurMin: Int, val vakKleur: Int?, val auto: Boolean,
    ) {
        fun start(): Long? = if (dag == null || startMin == null) null else Agenda.dagStart(dag) + startMin * 60000L
    }
    data class Gewoonte(val id: String, val naam: String, val vandaag: Boolean, val reeks: Int)
    class Alles(val taken: List<Taak>, val gewoontes: List<Gewoonte>)

    private fun req(context: Context, method: String, path: String, body: String? = null, prefer: String? = null): String {
        fun once() = Supabase.call(method, "$SUPABASE_URL/rest/v1/$path",
            mapOf("Authorization" to "Bearer ${Supabase.token(context)}") + (if (prefer != null) mapOf("Prefer" to prefer) else emptyMap()), body)
        var r = once()
        if (r.code == 401) { Supabase.forgetAccess(); r = once() }
        if (r.code !in 200..299) throw IOException("Supabase-fout ${r.code}")
        return r.body
    }

    private fun arr(s: String) = JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** null = niet ingelogd bij de planner. */
    @Throws(IOException::class)
    fun haal(context: Context): Alles? {
        if (!Supabase.loggedIn(context)) return null
        return try {
            val van = LocalDate.now().minusDays(7)
            val tot = LocalDate.now().plusDays(21)
            val vakken = arr(req(context, "GET", "planner_subjects?select=id,color")).associate { it.getString("id") to it.optString("color") }
            val taken = arr(req(context, "GET", "planner_tasks?select=id,kind,title,done,day,start_min,duration_min,subject_id,module_state" +
                "&or=(day.is.null,and(day.gte.$van,day.lte.$tot))&order=day.asc,start_min.asc"))
                .filter { it.optString("module_state") != "skipped" }
                .map {
                    Taak(
                        it.getString("id"), it.optString("kind") == "block", it.optString("title"), it.optBoolean("done"),
                        if (it.isNull("day")) null else LocalDate.parse(it.getString("day")),
                        if (it.isNull("start_min")) null else it.getInt("start_min"), it.optInt("duration_min", 60),
                        vakken[it.optString("subject_id")]?.let { c -> try { android.graphics.Color.parseColor(c) } catch (e: Exception) { null } },
                        it.optString("module_state") == "auto",
                    )
                }
            val gewoontes = try {
                val h = arr(req(context, "GET", "planner_habits?select=id,name&order=created_at.asc"))
                val checks = arr(req(context, "GET", "planner_habit_checks?select=habit_id,day&day=gte.${LocalDate.now().minusDays(130)}"))
                val vandaag = LocalDate.now()
                h.map { x ->
                    val dagen = checks.filter { it.getString("habit_id") == x.getString("id") }.map { LocalDate.parse(it.getString("day")) }.toSet()
                    // Reeks: dagen op rij tot en met vandaag; vandaag nog niet afgevinkt breekt hem niet (zoals de planner)
                    var d = if (vandaag in dagen) vandaag else vandaag.minusDays(1)
                    var n = 0
                    while (d in dagen) { n++; d = d.minusDays(1) }
                    Gewoonte(x.getString("id"), x.optString("name"), vandaag in dagen, n)
                }
            } catch (e: IOException) { emptyList() }   // tabellen van ronde 2 nog niet aangemaakt
            Alles(taken, gewoontes)
        } catch (e: NeedLogin) {
            null
        }
    }

    @Throws(IOException::class, NeedLogin::class)
    fun zetAf(context: Context, id: String, af: Boolean) {
        req(context, "PATCH", "planner_tasks?id=eq.$id", JSONObject().put("done", af).toString(), "return=minimal")
    }

    @Throws(IOException::class, NeedLogin::class)
    fun zetGewoonte(context: Context, id: String, aan: Boolean) {
        val dag = LocalDate.now(ZoneId.systemDefault()).toString()
        if (aan) req(context, "POST", "planner_habit_checks?on_conflict=habit_id,day", JSONArray().put(JSONObject().put("habit_id", id).put("day", dag)).toString(), "resolution=ignore-duplicates,return=minimal")
        else req(context, "DELETE", "planner_habit_checks?habit_id=eq.$id&day=eq.$dag", null, "return=minimal")
    }
}
