package nl.nbroers.reageren

import android.content.ComponentName
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import nl.nbroers.reageren.ui.NoahTheme

/**
 * The phone app (since 0.3.0): tabs Today, Planner, Media, Data and Reply in the Huisstijl. The notification reader
 * (ReplyListener) and the queue to Supabase (Outbox) work as in 0.2.0; their settings are under Reply › settings.
 * Since 0.8.0 it checks GitHub for a newer release on start (Update.kt).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Crash log (since 0.7.1): keep the last error, so it shows in Reply › settings and Noah can pass it on
        val vorige = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                Prefs(this).put("crash.last", "${java.time.LocalDateTime.now().withNano(0)} · version ${packageManager.getPackageInfo(packageName, 0).versionName}\n" + sw.toString().take(4000))
            } catch (x: Exception) { }
            vorige?.uncaughtException(t, e)
        }
        enableEdgeToEdge()
        setContent { NoahTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        Wakker.tel++
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        if (enabled.contains(packageName)) NotificationListenerService.requestRebind(ComponentName(this, ReplyListener::class.java))
        Outbox.kick(this)
    }
}
