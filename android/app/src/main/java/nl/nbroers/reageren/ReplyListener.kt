package nl.nbroers.reageren

import android.app.Notification
import android.app.Person
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Leest de naam, de app en het laatste bericht van een chatmelding. Het bericht gaat alleen naar de regel in de planner
 * en wordt daar overschreven bij een nieuw bericht; het komt niet in Diag en niet in de cijfers voor het archief.
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

    private data class Chat(val person: String, val app: ChatApp, val message: String?) {
        val key get() = "${app.pkg}|$person"
    }

    override fun onListenerConnected() {
        Prefs(this).put("status.connected", System.currentTimeMillis().toString())
        connectivity = getSystemService(ConnectivityManager::class.java)
        try { connectivity.registerDefaultNetworkCallback(network) } catch (e: IllegalArgumentException) { }  // stond al aan
        Outbox.kick(this)
        // Wat er al stond toen de telefoon opstartte of de app werd bijgewerkt
        activeNotifications?.forEach { sbn -> parse(sbn)?.let { Outbox.waiting(it.person, it.app.label, sbn.postTime, it.message, this) } }
    }

    override fun onListenerDisconnected() {
        try { connectivity.unregisterNetworkCallback(network) } catch (e: Exception) { }
        requestRebind(android.content.ComponentName(this, ReplyListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val chat = parse(sbn)
        Diag.add(this, "posted", sbn, if (chat != null) "row" else "skipped")
        chat ?: return
        // Sommige apps halen een melding weg en zetten hem meteen terug: dan is er niet gereageerd
        pending.remove(chat.key)?.let { main.removeCallbacks(it) }
        Outbox.waiting(chat.person, chat.app.label, sbn.postTime, chat.message, this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        val chat = parse(sbn)
        val answered = reason == REASON_CLICK || reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL
        Diag.add(this, "removed:${reasonName(reason)}", sbn, if (chat != null && answered) "replied" else "stays")
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
        // Snapchat en de dating-apps geven niet altijd een categorie mee; de rest moet echt een chatbericht zijn
        val isChat = n.category == Notification.CATEGORY_MESSAGE || hasMessages || (app.loose && n.category == null)
        if (!isChat) return null

        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || (hasMessages && conversation.isNotEmpty())
        if (isGroup && !prefs.groups) return null

        val raw = if (isGroup) conversation else extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val person = raw.replace(COUNT, "").trim().take(100)
        if (person.isEmpty() || person.equals(app.label, ignoreCase = true)) return null
        if (app.loose) {
            if (NO_SENDER.containsMatchIn(person)) return null
            if (extras.getCharSequence(Notification.EXTRA_TEXT)?.let { SYSTEM_TEXT.containsMatchIn(it) } == true) return null
        }
        return Chat(person, app, lastMessage(extras, app))
    }

    /** De tekst van het laatste binnengekomen bericht, of null als de app alleen zoiets als "nieuw bericht" meestuurt. */
    private fun lastMessage(extras: Bundle, app: ChatApp): String? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val raw = if (messages != null) {
            val self = extras.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)?.name?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
            // Zonder afzender of met je eigen naam = een bericht van jezelf, bijvoorbeeld beantwoord vanuit de melding
            messages.filterIsInstance<Bundle>().lastOrNull {
                val sender = it.getCharSequence("sender")?.toString()
                !sender.isNullOrBlank() && sender != self
            }?.getCharSequence("text")
        } else {
            extras.getCharSequence(Notification.EXTRA_TEXT)
        }
        val text = raw?.toString()?.replace(SPACES, " ")?.trim().orEmpty()
        if (text.isEmpty() || GENERIC.matches(text)) return null
        if ((messages == null || app.loose) && BOILERPLATE.containsMatchIn(text)) return null
        if (text.length <= MAX_MESSAGE) return text
        return text.take(if (text[MAX_MESSAGE - 1].isHighSurrogate()) MAX_MESSAGE - 1 else MAX_MESSAGE)
    }

    companion object {
        private const val GRACE = 5000L
        private const val MAX_MESSAGE = 500
        private val COUNT = Regex("""\s*\(\d+\s*\p{L}*\)\s*$""")
        private val SPACES = Regex("""\s+""")

        // Titels van Snapchat en de dating-apps die geen persoon zijn
        private val NO_SENDER = Regex(
            """(?i)updating messages|missed snaps|snaps and messages|gemiste snaps""" +
                """|^\d+\+?\s+(new|nieuwe?|missed|gemiste|snaps?|chats?|messages?|berichten|gesprekken|conversations?)\b""" +
                """|\b(new|nieuwe?)\s+(messages?|bericht(en)?|chats?|snaps?|match(es)?|likes?)\b""" +
                """|\byou have\b|\bje hebt\b|\bsomeone\b|\biemand\b|\bit'?s a match\b|\bliked\b"""
        )

        // Tekst waaruit blijkt dat de melding niet over een bericht van iemand gaat
        private val SYSTEM_TEXT = Regex(
            """(?i)updating messages|missed snaps|snaps and messages|gemiste snaps""" +
                """|\b(new|nieuwe?)\s+match\b|\bit'?s a match\b|\bliked (you|your)\b|\bvindt je\b.*\bleuk\b"""
        )

        // De hele tekst is alleen een aankondiging
        private val GENERIC = Regex(
            """(?i)((\d+\+?\s+)?(new|nieuwe?|unread|ongelezen)\s+(messages?|bericht(en)?|chats?|snaps?)|messages?|bericht(en)?|new chat)[.!]*"""
        )

        // Zinnetjes waarmee apps een bericht aankondigen zonder de inhoud te geven
        private val BOILERPLATE = Regex(
            """(?i)\b(sent (you )?(a|an)\b|sent you\b|stuurde je\b|heeft je\b.*\bgestuurd\b|you have (a |an |\d+ )?(new|unread)\b""" +
                """|je hebt (een |\d+ )?(nieuwe?|ongelezen)\b|liked your\b|new (message|chat|snap) from\b|nieuwe? (bericht|chat|snap) van\b""" +
                """|is typing\b|aan het typen\b)"""
        )
    }
}

/** Latest events, to see what goes wrong. Without names and without text. */
object Diag {
    private const val KEY = "diag"
    private const val MAX = 40

    @Synchronized
    fun add(context: Context, what: String, sbn: StatusBarNotification, outcome: String) {
        val label = CHAT_APPS.firstOrNull { it.pkg == sbn.packageName }?.label ?: return
        val n = sbn.notification
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        val samenvatting = if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) " summary" else ""
        val line = "$time $label $what [${n.category ?: "-"}$samenvatting] $outcome"
        val prefs = Prefs(context)
        val lines = (prefs.get(KEY)?.split("\n") ?: emptyList())
        prefs.put(KEY, (listOf(line) + lines).take(MAX).joinToString("\n"))
    }

    fun read(context: Context): String = Prefs(context).get(KEY) ?: "Nothing yet."
}
