package pl.aitrackereye.konfigurator

/** Czysta logika (bez Androida i sieci) - port z wersji na Windows (camera_api.py). */

class CameraException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class CameraAddress(
    val host: String,
    val user: String,
    val password: String,
    val httpPort: Int = 80,
    val rtspPort: Int = 554,
    val https: Boolean = false,
    val verifyTls: Boolean = true,
    val channel: Int = 1, // w RTSP kanały zaczynają się od 1, w CGI od 0
) {
    init {
        require(host.isNotEmpty() && host.none { it in "/@?#\\ " }) {
            "Wpisz adres IP lub nazwę hosta (bez http:// i ścieżki)."
        }
        require(!(host.count { it == ':' } == 1 && !host.startsWith("["))) {
            "Port HTTP wpisz w osobnym polu."
        }
        require(httpPort in 1..65535 && rtspPort in 1..65535) { "Niepoprawny port." }
        require(channel in 1..256) { "Niepoprawny numer kanału." }
    }

    val cgiChannel: Int get() = channel - 1

    val hostUri: String
        get() = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host

    val baseUrl: String
        get() = (if (https) "https" else "http") + "://" + hostUri + ":" + httpPort

    fun rtspUrl(subtype: Int): String {
        val auth = if (user.isNotEmpty()) percentEncode(user) + ":" + percentEncode(password) + "@" else ""
        return "rtsp://" + auth + hostUri + ":" + rtspPort +
            "/cam/realmonitor?channel=" + channel + "&subtype=" + subtype
    }
}

fun percentEncode(s: String): String = buildString {
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 128 && (ch.isLetterOrDigit() || ch in "-._~")) append(ch)
        else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
}

data class Snapshot(
    val config: Map<String, Map<String, String>> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val currentTime: String? = null,
    val recordCaps: Map<String, String> = emptyMap(),
    val encodeCaps: Map<String, String> = emptyMap(),
)

fun parseKeyValues(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (line in text.lines()) {
        val idx = line.indexOf('=')
        if (idx < 0) continue
        var name = line.substring(0, idx).trim()
        if (name.startsWith("table.")) name = name.substring(6)
        if (name.isNotEmpty()) result[name] = line.substring(idx + 1).trim()
    }
    return result
}

// ---------- kodowanie obrazu ----------

fun encodePrefix(channel: Int, stream: Int): String =
    if (stream == 0) "Encode[$channel].MainFormat[0]" else "Encode[$channel].ExtraFormat[${stream - 1}]"

fun encodeAvailable(config: Map<String, String>, channel: Int, stream: Int): Boolean {
    val prefix = encodePrefix(channel, stream) + ".Video."
    return config.keys.any { it.startsWith(prefix) }
}

/** BitRateOptions to LISTA dozwolonych wartości, nie para min,max. */
fun parseBitrateBounds(text: String): Pair<Int, Int>? {
    val nums = Regex("""\d+""").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
    if (nums.size < 2) return null
    return Pair(nums.min(), nums.max())
}

// ---------- harmonogram ----------

data class Segment(val mask: Int, val start: String, val end: String)

private val SEGMENT_RE = Regex("""(\d+)\s+(\d\d:\d\d:\d\d)-(\d\d:\d\d:\d\d)""")

fun parseSegment(value: String): Segment? {
    val m = SEGMENT_RE.matchEntire(value.trim()) ?: return null
    return Segment(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3])
}

fun recordScheduleKeys(config: Map<String, String>, channel: Int, weekday: Int): List<String> {
    val re = Regex("^Record\\[" + channel + "\\]\\.TimeSection\\[" + weekday + "\\]\\[(\\d+)\\]$")
    return config.keys
        .mapNotNull { k -> re.find(k)?.let { k to it.groupValues[1].toInt() } }
        .sortedBy { it.second }
        .map { it.first }
}

/** (złożony harmonogram?, zawiera reguły zdarzeń ruch/alarm?) */
fun scanSchedule(config: Map<String, String>, channel: Int): Pair<Boolean, Boolean> {
    var complexRegular = false
    var hasEvents = false
    for (day in 0..6) {
        var regularCount = 0
        for (key in recordScheduleKeys(config, channel, day)) {
            val entry = parseSegment(config.getValue(key))
            if (entry == null) { complexRegular = true; continue }
            if (entry.mask and 1.inv() != 0) hasEvents = true
            if (entry.mask and 1 != 0) regularCount++
        }
        if (regularCount > 1) complexRegular = true
    }
    return Pair(complexRegular, hasEvents)
}

/** Zwraca (tryb, od, do): tryb = all / range / off / complex. */
fun readDay(config: Map<String, String>, channel: Int, weekday: Int): Triple<String, String, String> {
    val entries = recordScheduleKeys(config, channel, weekday)
        .mapNotNull { parseSegment(config.getValue(it)) }
        .filter { it.mask and 1 != 0 }
    if (entries.isEmpty()) return Triple("off", "00:00", "23:59")
    if (entries.size != 1) return Triple("complex", "00:00", "23:59")
    val e = entries[0]
    if (e.start == "00:00:00" && (e.end == "24:00:00" || e.end == "23:59:59")) return Triple("all", "00:00", "23:59")
    return Triple("range", e.start.take(5), e.end.take(5))
}

/** Jawne ZASTĄPIENIE wszystkich slotów jednego dnia. Rzuca IllegalArgumentException przy złych danych. */
fun buildDayUpdate(
    config: Map<String, String>, channel: Int, weekday: Int,
    mode: String, start: String, end: String,
): Map<String, String> {
    var keys = recordScheduleKeys(config, channel, weekday)
    if (keys.isEmpty()) keys = listOf("Record[$channel].TimeSection[$weekday][0]")
    val timeRe = Regex("""\d\d:\d\d""")
    val first = when (mode) {
        "all" -> "1 00:00:00-24:00:00"
        "off" -> "0 00:00:00-24:00:00"
        "range" -> {
            require(timeRe.matches(start) && timeRe.matches(end)) { "Niepoprawny format czasu harmonogramu (HH:MM)." }
            require(start < end) { "Początek okresu musi być wcześniejszy niż koniec; zakres nie może przechodzić przez północ." }
            "1 $start:00-$end:59"
        }
        else -> throw IllegalArgumentException("Wybierz jeden z prostych trybów harmonogramu.")
    }
    val changes = LinkedHashMap<String, String>()
    for (k in keys) changes[k] = "0 00:00:00-24:00:00"
    changes[keys[0]] = first
    return changes
}

// ---------- strumień zapisu ----------

private val RECORD_STREAM_CANDIDATES = listOf(
    "Record[%d].RecordStream", "Record[%d].StreamType", "Record[%d].StreamMode", "MediaGlobal.RecordStream",
)

private val STREAM_PAIRS = mapOf(
    "MainStream" to Pair("main", "SubStream"), "SubStream" to Pair("sub", "MainStream"),
    "ExtraStream" to Pair("sub", "MainStream"),
    "Main" to Pair("main", "Sub"), "Sub" to Pair("sub", "Main"),
)

/** (klucz, "main"/"sub", wartość przeciwna) albo null, gdy pole nierozpoznane. */
fun detectedRecordStream(record: Map<String, String>, media: Map<String, String>, channel: Int): Triple<String, String, String>? {
    val merged = record + media
    for (candidate in RECORD_STREAM_CANDIDATES) {
        val key = candidate.replace("%d", channel.toString())
        val pair = STREAM_PAIRS[merged[key]] ?: continue
        return Triple(key, pair.first, pair.second)
    }
    return null
}

// ---------- diagnostyka ----------

fun redactedDiagnostic(snapshot: Snapshot, host: String, channel: Int): String {
    val lines = ArrayList<String>()
    lines += "AiTrackerEye - diagnostyka BCS/Dahua (bez hasła)"
    lines += "Host: $host"
    lines += "Kanał: $channel"
    lines += "Czas: ${snapshot.currentTime ?: "brak"}"
    lines += ""
    for ((section, config) in snapshot.config) {
        lines += "[$section]"
        for ((key, value) in config.toSortedMap()) {
            val secret = listOf("password", "secret", "token", "privatekey").any { key.lowercase().contains(it) }
            lines += key + "=" + (if (secret) "[UKRYTO]" else value)
        }
        lines += ""
    }
    for ((name, err) in snapshot.errors) lines += "Brak sekcji $name: $err"
    return lines.joinToString("\n")
}
