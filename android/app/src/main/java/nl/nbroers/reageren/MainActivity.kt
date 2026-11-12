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
 * De telefoon-app (sinds 0.3.0): drie tabjes (Vandaag, Planner, Reageren) in de huisstijl. De meldingenlezer
 * (ReplyListener) en de wachtrij naar Supabase (Outbox) werken zoals in 0.2.0; hun instellingen staan onder Reageren › instellingen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
