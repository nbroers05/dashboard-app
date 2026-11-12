package nl.nbroers.reageren

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class HttpResult(val code: Int, val body: String)

/** Versleutelt de blijvende sleutel met een sleutel die het toestel niet verlaat. Het wachtwoord zelf wordt nergens bewaard. */
internal object Vault {
    private const val ALIAS = "reageren.refresh"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun seal(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        null
    }
}

class NeedLogin : Exception()

object Supabase {
    private const val REFRESH = "auth.refresh"
    private var access: String? = null
    private var expires = 0L

    fun call(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("apikey", SUPABASE_KEY)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
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

    fun loggedIn(context: Context) = Prefs(context).get(REFRESH) != null

    @Synchronized
    private fun store(context: Context, json: JSONObject) {
        access = json.getString("access_token")
        expires = System.currentTimeMillis() + json.getLong("expires_in") * 1000 - 60000
        Prefs(context).put(REFRESH, Vault.seal(json.getString("refresh_token")))
    }

    /** Geeft een foutmelding terug, of null als het gelukt is. */
    fun signIn(context: Context, email: String, password: String): String? {
        val body = JSONObject().put("email", email).put("password", password).toString()
        return try {
            val r = call("POST", "$SUPABASE_URL/auth/v1/token?grant_type=password", emptyMap(), body)
            when {
                r.code == 200 -> { store(context, JSONObject(r.body)); null }
                r.code == 400 -> "Mail of wachtwoord klopt niet."
                else -> "Inloggen lukt niet (fout ${r.code})."
            }
        } catch (e: IOException) {
            "Geen verbinding met Supabase."
        }
    }

    fun signOut(context: Context) {
        synchronized(this) {
            access = null
            expires = 0
        }
        Prefs(context).put(REFRESH, null)
    }

    fun forgetAccess() = synchronized(this) { access = null }

    @Synchronized
    @Throws(IOException::class, NeedLogin::class)
    fun token(context: Context): String {
        access?.let { if (System.currentTimeMillis() < expires) return it }
        val refresh = Prefs(context).get(REFRESH)?.let { Vault.open(it) } ?: throw NeedLogin()
        val body = JSONObject().put("refresh_token", refresh).toString()
        val r = call("POST", "$SUPABASE_URL/auth/v1/token?grant_type=refresh_token", emptyMap(), body)
        if (r.code == 200) {
            store(context, JSONObject(r.body))
            return access!!
        }
        if (r.code in 400..499) throw NeedLogin()
        throw IOException("Fout ${r.code}")
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
