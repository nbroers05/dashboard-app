package nl.nbroers.reageren

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.nbroers.reageren.ui.Knop
import nl.nbroers.reageren.ui.LocalNH
import nl.nbroers.reageren.ui.NHTekst
import nl.nbroers.reageren.ui.Venster
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto-update (since 0.8.0). On start (once per app process) and from Settings › "Check now" the app looks at the
 * GitHub releases of nbroers05/dashboard-app (public, no key needed) for the highest tag `telefoon-v<version>`.
 * If that's newer than the installed version, Today and Settings show "New version — install": that downloads
 * dashboard-telefoon.apk into the app's cache and opens Android's installer via a FileProvider
 * (REQUEST_INSTALL_PACKAGES; the first time Android asks to allow installing from this app).
 * The release is signed with the same key as before, so it installs over the old one.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/nbroers05/dashboard-app/releases?per_page=30"
    private const val TAG = "telefoon-v"
    private const val APK = "dashboard-telefoon.apk"

    data class Release(val version: String, val url: String)

    /** idle | checking | latest | available | downloading | error */
    var state by mutableStateOf("idle")
        private set
    var release by mutableStateOf<Release?>(null)
        private set
    var progress by mutableStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var checkedThisRun = false

    fun installed(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Compares "0.10.1" with "0.9.0" number by number. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "dashboard-app (phone)")
            if (c.responseCode != 200) throw IOException("GitHub error ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** The newest release with tag telefoon-v… and the APK attached, or null. */
    @Throws(IOException::class)
    fun latest(): Release? {
        val arr = JSONArray(get(RELEASES))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("tag_name").startsWith(TAG) && !it.optBoolean("draft") }
            .mapNotNull { r ->
                val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == APK } ?: return@mapNotNull null
                Release(r.getString("tag_name").removePrefix(TAG), apk.getString("browser_download_url"))
            }
            .reduceOrNull { a, b -> if (newer(b.version, a.version)) b else a }
    }

    suspend fun check(context: Context, force: Boolean = false) {
        if (state == "checking" || state == "downloading") return
        if (checkedThisRun && !force) return
        checkedThisRun = true
        state = "checking"
        error = null
        try {
            val r = withContext(Dispatchers.IO) { latest() }
            release = r
            state = if (r != null && newer(r.version, installed(context))) "available" else "latest"
        } catch (e: Exception) {
            error = "Couldn't check for updates (${e.message ?: e.javaClass.simpleName})."
            state = "error"
        }
    }

    /** Download the APK into cache/updates and open Android's installer. */
    suspend fun install(context: Context) {
        val r = release ?: return
        // The first time: Android has to allow this app to install apps. Open that setting and stop here.
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            error = "Allow \"Install unknown apps\" for Dashboard, then come back and tap Install again."
            return
        }
        state = "downloading"
        progress = 0
        error = null
        try {
            val file = withContext(Dispatchers.IO) { download(context, r.url) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            state = "available"
        } catch (e: Exception) {
            error = "Download failed (${e.message ?: e.javaClass.simpleName})."
            state = "available"
        }
    }

    private fun download(context: Context, url: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, APK)
        // GitHub redirects to its download server (https → https, followed automatically)
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "dashboard-app (phone)")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) progress = (done * 100 / total).toInt()
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (out.length() < 100_000) throw IOException("file too small")
        return out
    }
}

/**
 * Today (altijd = false): only shows something when a newer version is available.
 * Settings (altijd = true): always shows the status, with a "Check now" button.
 */
@Composable
internal fun UpdateVenster(altijd: Boolean) {
    val context = LocalContext.current
    val k = LocalNH.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Updater.check(context) }
    val state = Updater.state
    val r = Updater.release
    if (!altijd && state != "available" && state != "downloading") return
    Venster("update.exe", "更新") {
        when (state) {
            "available", "downloading" -> {
                Text("New version ${r?.version} (you have ${Updater.installed(context)}).", style = NHTekst.body, color = k.ink)
                Knop(if (state == "downloading") "Downloading… ${Updater.progress}%" else "New version — install", hoofd = true) {
                    if (state != "downloading") scope.launch { Updater.install(context) }
                }
            }
            "checking" -> Text("Checking for a new version_", style = NHTekst.label, color = k.muted)
            "latest" -> Text("Up to date (${Updater.installed(context)}).", style = NHTekst.small, color = k.muted)
            else -> {}
        }
        Updater.error?.let { Text(it, style = NHTekst.small, color = k.red) }
        if (altijd && state != "downloading" && state != "checking") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Knop("Check now") { scope.launch { Updater.check(context, force = true) } }
            }
        }
    }
}
