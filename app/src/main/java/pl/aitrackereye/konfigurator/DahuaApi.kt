package pl.aitrackereye.konfigurator

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Odpowiedź HTTP w uproszczonej postaci. */
internal data class Raw(val code: Int, val authHeaders: List<String>, val body: String)

/** Klient Dahua CGI (HTTP Digest / Basic). Wszystkie metody są blokujące - wołać z wątku IO. */
class DahuaApi(private val addr: CameraAddress) {

    private val client: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
        if (addr.https && !addr.verifyTls) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, trustAll)
            b.hostnameVerifier { _, _ -> true }
        }
        b.build()
    }

    private fun request(url: HttpUrl, authorization: String?): Raw {
        val rb = Request.Builder().url(url).get()
        if (authorization != null) rb.header("Authorization", authorization)
        return client.newCall(rb.build()).execute().use { r ->
            Raw(r.code, r.headers("WWW-Authenticate"), r.body?.string() ?: "")
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun digestHeader(challenge: String, url: HttpUrl): String {
        val p = Regex("""(\w+)=("[^"]*"|[^,\s]*)""")
            .findAll(challenge.substringAfter("Digest", ""))
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim('"') }
        val realm = p["realm"] ?: ""
        val nonce = p["nonce"] ?: ""
        val uri = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val ha1 = md5(addr.user + ":" + realm + ":" + addr.password)
        val ha2 = md5("GET:$uri")
        val qopAuth = p["qop"]?.split(",")?.map { it.trim() }?.contains("auth") == true
        val sb = StringBuilder()
        sb.append("Digest username=\"").append(addr.user).append("\", realm=\"").append(realm)
            .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri).append("\"")
        if (qopAuth) {
            val nc = "00000001"
            val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val response = md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
            sb.append(", response=\"").append(response).append("\", qop=auth, nc=").append(nc)
                .append(", cnonce=\"").append(cnonce).append("\"")
        } else {
            sb.append(", response=\"").append(md5("$ha1:$nonce:$ha2")).append("\"")
        }
        p["opaque"]?.let { sb.append(", opaque=\"").append(it).append("\"") }
        sb.append(", algorithm=MD5")
        return sb.toString()
    }

    private fun authFor(challenges: List<String>, url: HttpUrl): String? {
        challenges.firstOrNull { it.trimStart().startsWith("Digest", ignoreCase = true) }
            ?.let { return digestHeader(it, url) }
        if (challenges.any { it.trimStart().startsWith("Basic", ignoreCase = true) }) {
            val token = Base64.encodeToString("${addr.user}:${addr.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            return "Basic $token"
        }
        return null
    }

    private fun get(path: String, params: Map<String, String>): String {
        val ub = (addr.baseUrl + path).toHttpUrlOrNull()?.newBuilder()
            ?: throw CameraException("Niepoprawny adres kamery.")
        for ((k, v) in params) ub.addQueryParameter(k, v)
        val url = ub.build()
        try {
            var raw = request(url, null)
            if (raw.code == 401) {
                val auth = authFor(raw.authHeaders, url)
                    ?: throw CameraException("Odmowa dostępu (401). Sprawdź użytkownika i hasło.")
                raw = request(url, auth)
            }
            if (raw.code == 401) throw CameraException("Odmowa dostępu (401). Sprawdź użytkownika i hasło.")
            if (raw.code == 403) throw CameraException("Brak uprawnień do tej funkcji (403).")
            if (raw.code >= 400) throw CameraException("Kamera zwróciła HTTP ${raw.code}.")
            val data = raw.body.trim()
            if (data.isEmpty()) throw CameraException("Pusta odpowiedź kamery.")
            val low = data.lowercase()
            if (low.startsWith("error") || low.startsWith("failed") || low.startsWith("invalid"))
                throw CameraException("Kamera odrzuciła polecenie: " + data.take(180))
            return data
        } catch (e: SSLException) {
            throw CameraException("Błąd certyfikatu HTTPS. Wyłącz „Sprawdzaj certyfikat” tylko świadomie.", e)
        } catch (e: IOException) {
            // Nie wypisujemy URL-a - mógłby zawierać dane użytkownika.
            throw CameraException("Brak połączenia z kamerą (${e.javaClass.simpleName}). Sprawdź IP, port i czy telefon jest w tej samej sieci.", e)
        }
    }

    fun getConfig(section: String): Map<String, String> {
        val body = get("/cgi-bin/configManager.cgi", mapOf("action" to "getConfig", "name" to section))
        val values = parseKeyValues(body)
        if (values.keys.none { it == section || it.startsWith("$section[") || it.startsWith("$section.") })
            throw CameraException("Sekcja $section nie jest udostępniana przez API kamery.")
        return values
    }

    fun setConfig(values: Map<String, String>) {
        if (values.isEmpty()) return
        val body = get("/cgi-bin/configManager.cgi", mapOf("action" to "setConfig") + values)
        if (body.lines().first().trim() != "OK") throw CameraException("Niepotwierdzony zapis konfiguracji: " + body.take(160))
    }

    fun getTime(): String {
        val values = parseKeyValues(get("/cgi-bin/global.cgi", mapOf("action" to "getCurrentTime")))
        return values["result"] ?: throw CameraException("Nie udało się odczytać czasu kamery.")
    }

    fun syncTimeFromPhone(): String {
        val value = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = get("/cgi-bin/global.cgi", mapOf("action" to "setCurrentTime", "time" to value))
        if (body.lines().first().trim() != "OK") throw CameraException("Niepotwierdzona zmiana czasu: " + body.take(150))
        return value
    }

    fun read(): Snapshot {
        val config = LinkedHashMap<String, Map<String, String>>()
        val errors = LinkedHashMap<String, String>()
        for (section in listOf("Record", "RecordMode", "MediaGlobal", "Encode")) {
            try { config[section] = getConfig(section) } catch (e: CameraException) { errors[section] = e.message ?: "błąd" }
        }
        if (config.isEmpty()) {
            val first = errors.values.firstOrNull() ?: ""
            // Błędy połączenia/autoryzacji pokazujemy wprost, bez zgadywania o CGI.
            throw CameraException(first.ifEmpty { "Nie udało się odczytać żadnej sekcji CGI." } +
                if (errors.values.all { it.startsWith("Sekcja") }) " Kamera może nie obsługiwać interfejsu Dahua CGI." else "")
        }
        var time: String? = null
        try { time = getTime() } catch (e: CameraException) { errors["Czas"] = e.message ?: "błąd" }
        var recordCaps: Map<String, String> = emptyMap()
        var encodeCaps: Map<String, String> = emptyMap()
        try { recordCaps = parseKeyValues(get("/cgi-bin/recordManager.cgi", mapOf("action" to "getCaps"))) } catch (e: CameraException) { /* opcjonalne */ }
        try { encodeCaps = parseKeyValues(get("/cgi-bin/encode.cgi", mapOf("action" to "getConfigCaps"))) } catch (e: CameraException) { /* opcjonalne */ }
        return Snapshot(config, errors, time, recordCaps, encodeCaps)
    }
}

/** Zapisuje kopię odczytanej konfiguracji (bez hasła) i zwraca plik. */
fun backupSnapshot(dir: File, snapshot: Snapshot, addr: CameraAddress): File {
    dir.mkdirs()
    val safeHost = addr.host.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val file = File(dir, "${safeHost}_$stamp.json")
    val json = JSONObject()
    json.put("host", addr.host)
    json.put("channel", addr.channel)
    json.put("saved_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
    val cfg = JSONObject()
    for ((section, values) in snapshot.config) cfg.put(section, JSONObject(values))
    json.put("config", cfg)
    file.writeText(json.toString(2), Charsets.UTF_8)
    return file
}
