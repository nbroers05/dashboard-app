package nl.nbroers.reageren

import android.app.Notification
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Leest alleen de naam en de app van een chatmelding. EXTRA_TEXT en de inhoud van EXTRA_MESSAGES worden nooit gelezen.
 *
 * Melding weg door aantikken of doordat de app hem zelf weghaalt (chat geopend) = gereageerd.
 * Zelf weggeveegd of alles wissen = blijft staan.
 */
class ReplyListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, Runnable>()
    private lateinit var connectivity: ConnectivityManager

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = Outbox.kick(this@ReplyListener)
    }

    private data class Chat(val person: String, val app: ChatApp) {
        val key get() = "${app.pkg}|$person"
    }

    override fun onListenerConnected() {
        Prefs(this).put("status.connected", System.currentTimeMillis().toString())
        connectivity = getSystemService(ConnectivityManager::class.java)
        try { connectivity.registerDefaultNetworkCallback(network) } catch (e: IllegalArgumentException) { }  // stond al aan
        Outbox.kick(this)
        // Wat er al stond toen de telefoon opstartte of de app werd bijgewerkt
        activeNotifications?.forEach { sbn -> parse(sbn)?.let { Outbox.waiting(it.person, it.app.label, sbn.postTime, this) } }
    }

    override fun onListenerDisconnected() {
        try { connectivity.unregisterNetworkCallback(network) } catch (e: Exception) { }
        requestRebind(android.content.ComponentName(this, ReplyListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val chat = parse(sbn)
        Diag.add(this, "melding", sbn, if (chat != null) "regel" else "overgeslagen")
        chat ?: return
        // Sommige apps halen een melding weg en zetten hem meteen terug: dan is er niet gereageerd
        pending.remove(chat.key)?.let { main.removeCallbacks(it) }
        Outbox.waiting(chat.person, chat.app.label, sbn.postTime, this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        val chat = parse(sbn)
        val answered = reason == REASON_CLICK || reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL
        Diag.add(this, "weg:${reasonName(reason)}", sbn, if (chat != null && answered) "gereageerd" else "blijft")
        if (chat == null || !answered) return
        pending.remove(chat.key)?.let { main.removeCallbacks(it) }
        val delete = Runnable {
            pending.remove(chat.key)
            Outbox.answered(chat.person, chat.app.label, this)
        }
        pending[chat.key] = delete
        main.postDelayed(delete, GRACE)
    }

    private fun reasonName(reason: Int) = when (reason) {
        REASON_CLICK -> "aangetikt"
        REASON_CANCEL -> "weggeveegd"
        REASON_CANCEL_ALL -> "alles-wissen"
        REASON_APP_CANCEL -> "app"
        REASON_APP_CANCEL_ALL -> "app-alles"
        else -> reason.toString()
    }

    private fun parse(sbn: StatusBarNotification): Chat? {
        val prefs = Prefs(this)
        val app = prefs.enabled(sbn.packageName) ?: return null
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        val extras = n.extras
        val hasMessages = extras.containsKey(Notification.EXTRA_MESSAGES)
        // Snapchat geeft niet altijd een categorie mee; de rest moet echt een chatbericht zijn
        val isChat = n.category == Notification.CATEGORY_MESSAGE || hasMessages ||
            (app.pkg == "com.snapchat.android" && n.category == null)
        if (!isChat) return null

        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || (hasMessages && conversation.isNotEmpty())
        if (isGroup && !prefs.groups) return null

        val raw = if (isGroup) conversation else extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val person = raw.replace(COUNT, "").trim().take(100)
        if (person.isEmpty() || person.equals(app.label, ignoreCase = true)) return null
        return Chat(person, app)
    }

    companion object {
        private const val GRACE = 5000L
        private val COUNT = Regex("""\s*\(\d+\s*\p{L}*\)\s*$""")
    }
}

/** Laatste gebeurtenissen om te kunnen zien wat er misgaat. Zonder namen en zonder tekst. */
object Diag {
    private const val KEY = "diag"
    private const val MAX = 40

    @Synchronized
    fun add(context: Context, what: String, sbn: StatusBarNotification, outcome: String) {
        val label = CHAT_APPS.firstOrNull { it.pkg == sbn.packageName }?.label ?: return
        val n = sbn.notification
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        val samenvatting = if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) " samenvatting" else ""
        val line = "$time $label $what [${n.category ?: "-"}$samenvatting] $outcome"
        val prefs = Prefs(context)
        val lines = (prefs.get(KEY)?.split("\n") ?: emptyList())
        prefs.put(KEY, (listOf(line) + lines).take(MAX).joinToString("\n"))
    }

    fun read(context: Context): String = Prefs(context).get(KEY) ?: "Nog niets."
}
