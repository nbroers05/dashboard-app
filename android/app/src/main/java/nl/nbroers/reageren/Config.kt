package nl.nbroers.reageren

import android.content.Context

/** Dezelfde twee waarden als planner/config.js. De sleutel is bedoeld om openbaar te zijn; de beveiliging zit in Row Level Security. */
const val SUPABASE_URL = "https://osqcctmvnvsawbjqgjkp.supabase.co"
const val SUPABASE_KEY = "sb_publishable_XMjRxWBxSsSvDfZJa9cDaw_6RDGtEm0"

/** loose: de app geeft niet altijd aan dat iets een chatbericht is, en stuurt ook meldingen zonder echte afzender. */
class ChatApp(val pkg: String, val label: String, val onByDefault: Boolean, val loose: Boolean = false)

val CHAT_APPS = listOf(
    ChatApp("com.whatsapp", "WhatsApp", true),
    ChatApp("com.instagram.android", "Instagram", true),
    ChatApp("com.snapchat.android", "Snapchat", true, loose = true),
    ChatApp("co.hinge.app", "Hinge", true, loose = true),
    ChatApp("com.tinder", "Tinder", true, loose = true),
    ChatApp("com.bumble.app", "Bumble", true, loose = true),
    ChatApp("com.whatsapp.w4b", "WhatsApp Business", false),
    ChatApp("org.telegram.messenger", "Telegram", false),
    ChatApp("org.thoughtcrime.securesms", "Signal", false),
    ChatApp("com.facebook.orca", "Messenger", false),
    ChatApp("com.discord", "Discord", false),
    ChatApp("com.samsung.android.messaging", "Samsung Berichten", false),
    ChatApp("com.google.android.apps.messaging", "Google Berichten", false),
)

/** Instellingen, op dit apparaat. */
class Prefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("reageren", Context.MODE_PRIVATE)

    fun isOn(app: ChatApp) = p.getBoolean("app.${app.pkg}", app.onByDefault)
    fun setOn(app: ChatApp, on: Boolean) = p.edit().putBoolean("app.${app.pkg}", on).apply()

    var groups: Boolean
        get() = p.getBoolean("groups", false)
        set(v) = p.edit().putBoolean("groups", v).apply()

    fun enabled(pkg: String): ChatApp? = CHAT_APPS.firstOrNull { it.pkg == pkg && isOn(it) }

    fun get(key: String): String? = p.getString(key, null)
    fun put(key: String, value: String?) = p.edit().putString(key, value).apply()
}
