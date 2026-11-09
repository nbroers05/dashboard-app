package nl.nbroers.reageren

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var diag: TextView
    private lateinit var accessButton: Button
    private lateinit var loginBox: LinearLayout
    private lateinit var logoutButton: Button
    private lateinit var loginError: TextView

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        alpha = 0.75f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(TextView(this).apply {
            text = "Nog reageren"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        })
        root.addView(note("Zet naam en app van chatmeldingen in je planner. Nooit de berichttekst."))

        status = TextView(this).apply { setPadding(0, dp(14), 0, dp(4)) }
        root.addView(status)

        accessButton = Button(this).apply {
            text = "Toegang tot meldingen geven"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        root.addView(accessButton)
        root.addView(Button(this).apply {
            text = "Accu-instelling openen"
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        })
        root.addView(note("Zet bij Accu de app op Onbeperkt, anders kan Samsung hem stilleggen."))

        loginBox = buildLogin()
        root.addView(loginBox)

        root.addView(heading("Welke apps"))
        CHAT_APPS.forEach { app ->
            root.addView(CheckBox(this).apply {
                text = app.label
                isChecked = prefs.isOn(app)
                setOnCheckedChangeListener { _, on -> prefs.setOn(app, on) }
            })
        }

        root.addView(heading("Groepen"))
        root.addView(CheckBox(this).apply {
            text = "Ook groepsgesprekken"
            isChecked = prefs.groups
            setOnCheckedChangeListener { _, on -> prefs.groups = on }
        })

        root.addView(heading("Laatste meldingen"))
        root.addView(note("Om te controleren of het werkt. Zonder namen en zonder tekst."))
        diag = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
            setTextIsSelectable(true)
        }
        root.addView(diag)

        logoutButton = Button(this).apply {
            text = "Uitloggen"
            setOnClickListener {
                Supabase.signOut(this@MainActivity)
                refresh()
            }
        }
        root.addView(logoutButton)
        root.addView(note("Versie ${packageManager.getPackageInfo(packageName, 0).versionName}"))

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })
    }

    private fun buildLogin(): LinearLayout {
        val mail = EditText(this).apply {
            hint = "Mail"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
        }
        val password = EditText(this).apply {
            hint = "Wachtwoord van de planner"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        }
        loginError = TextView(this)
        val button = Button(this).apply { text = "Inloggen" }
        button.setOnClickListener {
            button.isEnabled = false
            loginError.text = ""
            val email = mail.text.toString().trim()
            val secret = password.text.toString()
            Thread {
                val error = Supabase.signIn(this, email, secret)
                runOnUiThread {
                    button.isEnabled = true
                    if (error == null) {
                        password.setText("")
                        Outbox.kick(this)
                        refresh()
                    } else {
                        loginError.text = error
                    }
                }
            }.start()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading("Inloggen"))
            addView(note("Met dezelfde mail en hetzelfde wachtwoord als de planner."))
            addView(mail)
            addView(password)
            addView(button)
            addView(loginError)
        }
    }

    private fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun refresh() {
        val access = hasAccess()
        val loggedIn = Supabase.loggedIn(this)
        accessButton.visibility = if (access) View.GONE else View.VISIBLE
        loginBox.visibility = if (loggedIn) View.GONE else View.VISIBLE
        logoutButton.visibility = if (loggedIn) View.VISIBLE else View.GONE

        val lines = mutableListOf(
            if (access) "Toegang tot meldingen: aan" else "Toegang tot meldingen: uit",
            if (loggedIn) "Ingelogd: ja" else "Ingelogd: nee",
        )
        prefs.get("status.sent")?.toLongOrNull()?.let {
            lines += "Laatst doorgegeven: " + SimpleDateFormat("d MMM HH:mm", Locale("nl", "NL")).format(Date(it))
        }
        val waiting = Outbox.waiting(this)
        if (waiting > 0) lines += "Wacht op verzending: $waiting"
        prefs.get("status.error")?.let { lines += it }
        status.text = lines.joinToString("\n")
        diag.text = Diag.read(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (hasAccess()) NotificationListenerService.requestRebind(ComponentName(this, ReplyListener::class.java))
        Outbox.kick(this)
    }
}
