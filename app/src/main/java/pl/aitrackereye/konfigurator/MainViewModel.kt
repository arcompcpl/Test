package pl.aitrackereye.konfigurator

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class ConfirmDialog(val title: String, val text: String, val onConfirm: () -> Unit)

class DayEdit(val weekday: Int, val name: String, initMode: String, initStart: String, initEnd: String) {
    var mode by mutableStateOf(initMode)
    var start by mutableStateOf(initStart)
    var end by mutableStateOf(initEnd)
}

val WEEKDAYS = listOf(1 to "Poniedziałek", 2 to "Wtorek", 3 to "Środa", 4 to "Czwartek",
    5 to "Piątek", 6 to "Sobota", 0 to "Niedziela")

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val backupDir = File(app.filesDir, "backups")

    // ---- połączenie (hasło celowo NIE jest zapamiętywane) ----
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var user by mutableStateOf(prefs.getString("user", "admin") ?: "admin")
    var password by mutableStateOf("")
    var httpPort by mutableStateOf(prefs.getString("http_port", "80") ?: "80")
    var rtspPort by mutableStateOf(prefs.getString("rtsp_port", "554") ?: "554")
    var channel by mutableStateOf(prefs.getString("channel", "1") ?: "1")
    var https by mutableStateOf(prefs.getBoolean("https", false))
    var verifyTls by mutableStateOf(prefs.getBoolean("verify_tls", true))

    // ---- stan ogólny ----
    var busy by mutableStateOf(false)
    var status by mutableStateOf("Niepołączono. Podaj adres kamery i kliknij „Połącz i odczytaj”.")
    var snapshot by mutableStateOf<Snapshot?>(null)
        private set
    private var connected: CameraAddress? = null
    var dialog by mutableStateOf<ConfirmDialog?>(null)
    var message by mutableStateOf<String?>(null)
    var diagnostic by mutableStateOf("")
        private set
    var lastBackupText by mutableStateOf<String?>(null)
        private set

    // ---- nagrywanie ----
    var recModeAvail by mutableStateOf(false)
    var recMode by mutableStateOf(0)
    var segmentAvail by mutableStateOf(false)
    var segment by mutableStateOf("0")
    var segmentInfo by mutableStateOf("")
    private var segmentMax = 60
    var overwriteAvail by mutableStateOf(false)
    var overwrite by mutableStateOf(true)
    var recStreamAvail by mutableStateOf(false)
    var recStream by mutableStateOf("main")

    // ---- harmonogram ----
    val days = mutableStateListOf<DayEdit>()
    var scheduleHint by mutableStateOf("Status harmonogramu zostanie pokazany po odczycie kamery.")
    var allowReplace by mutableStateOf(false)

    // ---- obraz ----
    var encStream by mutableStateOf(0)
    var encAvail by mutableStateOf(false)
    var codecs by mutableStateOf<List<String>>(emptyList())
    var codec by mutableStateOf("")
    var fps by mutableStateOf("")
    var bitrate by mutableStateOf("")
    var gop by mutableStateOf("")
    var control by mutableStateOf("VBR")
    var resolution by mutableStateOf("—")
    var codecAvail by mutableStateOf(false)
    var fpsAvail by mutableStateOf(false)
    var bitrateAvail by mutableStateOf(false)
    var gopAvail by mutableStateOf(false)
    var controlAvail by mutableStateOf(false)
    var encHint by mutableStateOf("")
    private var fpsMax = 120.0
    private var bitrateMin = 32
    private var bitrateMax = 100000

    // ---- podgląd ----
    var previewSub by mutableStateOf(false)
    var previewUrl by mutableStateOf<String?>(null)
        private set

    val connectedOk: Boolean get() = snapshot != null && connected != null

    // =====================================================================
    private fun toPort(text: String): Int =
        text.trim().toIntOrNull() ?: throw IllegalArgumentException("Niepoprawny port lub kanał.")

    private fun address(): CameraAddress = CameraAddress(
        host = host.trim(), user = user.trim(), password = password,
        httpPort = toPort(httpPort), rtspPort = toPort(rtspPort), channel = toPort(channel),
        https = https, verifyTls = verifyTls,
    )

    private fun savePrefs() {
        prefs.edit()
            .putString("host", host.trim()).putString("user", user.trim())
            .putString("http_port", httpPort).putString("rtsp_port", rtspPort)
            .putString("channel", channel).putBoolean("https", https)
            .putBoolean("verify_tls", verifyTls).apply()
    }

    fun onHttpsToggle(enabled: Boolean) {
        https = enabled
        if (httpPort.trim() == "80" || httpPort.trim() == "443") httpPort = if (enabled) "443" else "80"
    }

    private fun errorText(e: Exception): String = when (e) {
        is CameraException, is IllegalArgumentException -> e.message ?: "Błąd."
        else -> "Nieoczekiwany błąd: ${e.javaClass.simpleName}: ${e.message}"
    }

    private fun <T> execute(what: String, isSave: Boolean = false, work: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        status = "$what…"
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work() }
                busy = false
                done(result)
            } catch (e: Exception) {
                busy = false
                var msg = errorText(e)
                if (isSave) {
                    // Po potencjalnie częściowym zapisie blokujemy zmiany do ponownego odczytu.
                    snapshot = null
                    connected = null
                    msg += "\n\nPo błędzie zapisu koniecznie ponownie odczytaj konfigurację kamery."
                }
                status = "Błąd: $msg"
                message = msg
            }
        }
    }

    fun connect() {
        val addr = try { address() } catch (e: IllegalArgumentException) { message = e.message; return }
        snapshot = null
        connected = null
        savePrefs()
        execute("Odczyt konfiguracji i czasu kamery", work = { DahuaApi(addr).read() }) { snap ->
            snapshot = snap
            connected = addr
            showRecording(snap, addr)
            showSchedule(snap, addr)
            showEncoding()
            diagnostic = redactedDiagnostic(snap, addr.host, addr.channel) +
                (if (snap.errors.isNotEmpty()) "\n\nNie wszystkie sekcje są dostępne. Zobacz błędy powyżej." else "")
            status = "Połączono z ${addr.host} | Odczytano: ${snap.config.keys.joinToString(", ")} | Czas kamery: ${snap.currentTime ?: "nieznany"}"
        }
    }

    /** Dane połączenia mogły się zmienić od ostatniego odczytu - wtedy nie wolno zapisywać. */
    private fun ensureOriginal(): CameraAddress {
        val addr = try { address() } catch (e: IllegalArgumentException) { throw CameraException(e.message ?: "Niepoprawne dane.") }
        if (connected != addr || snapshot == null) throw CameraException("Dane połączenia zmieniły się. Ponownie kliknij „Połącz i odczytaj”.")
        return addr
    }

    // ---------------------------------------------------------------------
    private fun showRecording(s: Snapshot, addr: CameraAddress) {
        val ch = addr.cgiChannel
        val mode = s.config["RecordMode"]?.get("RecordMode[$ch].Mode")
        recModeAvail = mode != null && mode in listOf("0", "1", "2")
        if (recModeAvail) recMode = mode!!.toInt()
        val media = s.config["MediaGlobal"] ?: emptyMap()
        val length = media["MediaGlobal.PacketLength"]
        segmentAvail = length != null
        segment = "0"
        val rawMax = s.recordCaps["caps.PacketLengthRange[1]"] ?: ""
        segmentMax = minOf(60, rawMax.toIntOrNull() ?: 60)
        segmentInfo = "Aktualna długość pliku: ${length ?: "brak danych"} min. 0 = nie zmieniaj; zakres 1–$segmentMax."
        val ow = (media["MediaGlobal.OverWrite"] ?: "").lowercase()
        overwriteAvail = ow == "true" || ow == "false"
        if (overwriteAvail) overwrite = ow == "true"
        val setting = detectedRecordStream(s.config["Record"] ?: emptyMap(), media, ch)
        recStreamAvail = setting != null
        if (setting != null) recStream = setting.second
    }

    fun saveRecording() {
        val (addr, snap) = guard("Nagrywanie") ?: return
        val ch = addr.cgiChannel
        val oldMode = snap.config["RecordMode"] ?: emptyMap()
        val media = snap.config["MediaGlobal"] ?: emptyMap()
        val record = snap.config["Record"] ?: emptyMap()
        val changes = LinkedHashMap<String, String>()
        val modeKey = "RecordMode[$ch].Mode"
        if (modeKey in oldMode && recModeAvail && oldMode[modeKey] != recMode.toString()) changes[modeKey] = recMode.toString()
        val seg = segment.trim().toIntOrNull()
        if (seg == null || seg < 0 || seg > segmentMax) {
            message = "Długość pliku: wpisz 0 (bez zmian) albo liczbę od 1 do $segmentMax."
            return
        }
        if ("MediaGlobal.PacketLength" in media && seg > 0 && seg != media["MediaGlobal.PacketLength"]?.toIntOrNull()) {
            changes["MediaGlobal.PacketLength"] = seg.toString()
            if ("MediaGlobal.PacketType" in media) changes["MediaGlobal.PacketType"] = "0" // podział według czasu
        }
        if ("MediaGlobal.OverWrite" in media && overwriteAvail &&
            media.getValue("MediaGlobal.OverWrite").lowercase() != overwrite.toString()) {
            changes["MediaGlobal.OverWrite"] = overwrite.toString()
        }
        val setting = detectedRecordStream(record, media, ch)
        if (setting != null && recStreamAvail && setting.second != recStream) changes[setting.first] = setting.third
        applyUpdate("Ustawienia nagrywania", snap, addr, if (changes.isEmpty()) emptyList() else listOf(changes))
    }

    // ---------------------------------------------------------------------
    private fun showSchedule(s: Snapshot, addr: CameraAddress) {
        val record = s.config["Record"] ?: emptyMap()
        val ch = addr.cgiChannel
        days.clear()
        for ((wd, name) in WEEKDAYS) {
            val (mode, start, end) = readDay(record, ch, wd)
            days.add(DayEdit(wd, name, mode, start, end))
        }
        val (complexRegular, events) = scanSchedule(record, ch)
        scheduleHint = if (events || complexRegular)
            "UWAGA: obecny harmonogram zawiera reguły zdarzeń i/lub kilka okresów. Zapis uprości go i może usunąć nagrania wyzwalane ruchem/alarmem. Wymagana zgoda poniżej."
        else "Odczytano harmonogram. Zapis zastąpi tygodniowe zakresy nagrań ciągłych w tym kanale."
        allowReplace = false
    }

    fun saveSchedule() {
        val (addr, snap) = guard("Harmonogram") ?: return
        val cfg = snap.config["Record"] ?: emptyMap()
        if (cfg.isEmpty() || recordScheduleKeys(cfg, addr.cgiChannel, 0).isEmpty()) {
            message = "Brak rozpoznanego harmonogramu kanału w CGI."
            return
        }
        val (complexRegular, events) = scanSchedule(cfg, addr.cgiChannel)
        if ((complexRegular || events) && !allowReplace) {
            message = "Obecny plan zawiera dodatkowe przedziały lub typy zdarzeń. Zaznacz świadomą zgodę, jeśli chcesz zastąpić wszystkie reguły w tym kanale."
            return
        }
        val batches = try {
            days.map { buildDayUpdate(cfg, addr.cgiChannel, it.weekday, it.mode, it.start.trim(), it.end.trim()) }
        } catch (e: IllegalArgumentException) {
            message = e.message
            return
        }
        dialog = ConfirmDialog(
            "Zastąpienie harmonogramu",
            "Czy zastąpić siedmiodniowy harmonogram tego kanału?\nZostaną usunięte dotychczasowe przedziały nagrywania oraz reguły ruchu/alarmu z harmonogramu. Pozostałe ustawienia kamery pozostaną bez zmian.",
        ) { applyUpdate("Harmonogram tygodniowy", snap, addr, batches) }
    }

    // ---------------------------------------------------------------------
    fun selectEncStream(i: Int) {
        encStream = i
        showEncoding()
    }

    private fun showEncoding() {
        val s = snapshot ?: return
        val addr = connected ?: return
        val ch = addr.cgiChannel
        val cfg = s.config["Encode"] ?: emptyMap()
        val prefix = encodePrefix(ch, encStream)
        encAvail = encodeAvailable(cfg, ch, encStream)
        if (!encAvail) {
            codecAvail = false; fpsAvail = false; bitrateAvail = false; gopAvail = false; controlAvail = false
            resolution = "Strumień niedostępny w odpowiedzi API"
            encHint = ""
            return
        }
        fun v(field: String): String? = cfg["$prefix.Video.$field"]
        val capsPrefix = prefix.replace("Encode[", "caps[")
        val caps = s.encodeCaps
        val listed = (caps["$capsPrefix.Video.CompressionTypes"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val cur = v("Compression")
        if (cur != null && cur !in listed) listed.add(0, cur)
        codecs = listed
        codec = cur ?: ""
        codecAvail = cur != null
        fpsAvail = v("FPS") != null
        bitrateAvail = v("BitRate") != null
        gopAvail = v("GOP") != null
        controlAvail = v("BitRateControl") != null
        fpsMax = caps["$capsPrefix.Video.FPSMax"]?.toDoubleOrNull()?.coerceAtLeast(0.2) ?: 120.0
        val bounds = parseBitrateBounds(caps["$capsPrefix.Video.BitRateOptions"] ?: "")
        bitrateMin = bounds?.first ?: 32
        bitrateMax = bounds?.second ?: 100000
        fps = v("FPS") ?: ""
        bitrate = v("BitRate") ?: ""
        gop = v("GOP") ?: ""
        control = v("BitRateControl") ?: "VBR"
        val w = v("Width")
        val h = v("Height")
        resolution = if (w != null && h != null) "$w × $h" else "—"
        encHint = "Dozwolone: FPS 0,2–${fmt(fpsMax)}, bitrate $bitrateMin–$bitrateMax kb/s, GOP 1–1000."
    }

    private fun fmt(d: Double): String = if (d == Math.floor(d)) d.toInt().toString() else d.toString()

    fun saveEncoding() {
        val (addr, snap) = guard("Obraz") ?: return
        val cfg = snap.config["Encode"] ?: emptyMap()
        val prefix = encodePrefix(addr.cgiChannel, encStream)
        if (!encodeAvailable(cfg, addr.cgiChannel, encStream)) {
            message = "Ten strumień nie występuje w konfiguracji kamery."
            return
        }
        val diff = LinkedHashMap<String, String>()
        if (codecAvail && codec.isNotEmpty() && cfg["$prefix.Video.Compression"] != codec) diff["$prefix.Video.Compression"] = codec
        if (fpsAvail) {
            val f = fps.trim().replace(',', '.').toDoubleOrNull()
            if (f == null || f < 0.2 || f > fpsMax) { message = "FPS: wpisz liczbę od 0,2 do ${fmt(fpsMax)}."; return }
            val old = cfg["$prefix.Video.FPS"]?.toDoubleOrNull()
            if (old == null || abs(old - f) >= 0.001) diff["$prefix.Video.FPS"] = fmt(f)
        }
        if (bitrateAvail) {
            val b = bitrate.trim().toIntOrNull()
            if (b == null || b < bitrateMin || b > bitrateMax) { message = "Bitrate: wpisz liczbę od $bitrateMin do $bitrateMax kb/s."; return }
            if (cfg["$prefix.Video.BitRate"] != b.toString()) diff["$prefix.Video.BitRate"] = b.toString()
        }
        if (controlAvail && cfg["$prefix.Video.BitRateControl"] != control) diff["$prefix.Video.BitRateControl"] = control
        if (gopAvail) {
            val g = gop.trim().toIntOrNull()
            if (g == null || g < 1 || g > 1000) { message = "GOP: wpisz liczbę od 1 do 1000."; return }
            if (cfg["$prefix.Video.GOP"] != g.toString()) diff["$prefix.Video.GOP"] = g.toString()
        }
        applyUpdate("Parametry obrazu", snap, addr, if (diff.isEmpty()) emptyList() else listOf(diff))
    }

    // ---------------------------------------------------------------------
    fun syncTime() {
        val (addr, _) = guard("Czas") ?: return
        dialog = ConfirmDialog(
            "Synchronizacja czasu",
            "Ustawić w kamerze lokalną datę i godzinę telefonu?\nSprawdź wcześniej, czy kamera ma tę samą strefę czasową i ustawienia czasu letniego.",
        ) {
            execute("Synchronizacja czasu", isSave = true, work = { DahuaApi(addr).syncTimeFromPhone() }) { value ->
                message = "Kamera potwierdziła ustawienie czasu: $value"
                connect()
            }
        }
    }

    private fun guard(title: String): Pair<CameraAddress, Snapshot>? {
        if (busy) return null
        return try {
            val addr = ensureOriginal()
            Pair(addr, snapshot!!)
        } catch (e: CameraException) {
            message = "$title: ${e.message}"
            null
        }
    }

    private fun applyUpdate(title: String, snap: Snapshot, addr: CameraAddress, groups: List<Map<String, String>>) {
        if (groups.isEmpty()) { message = "Brak zmian do zapisania."; return }
        execute("Zapisywanie: $title", isSave = true, work = {
            val file = backupSnapshot(backupDir, snap, addr) // kopia przed pierwszą zmianą
            val api = DahuaApi(addr)
            for (g in groups) api.setConfig(g)
            file
        }) { file ->
            lastBackupText = file.readText(Charsets.UTF_8)
            message = "Kamera potwierdziła polecenia zapisu (OK).\nKopia poprzedniej konfiguracji:\n${file.name}\n\nNastąpi ponowny odczyt ustawień."
            connect()
        }
    }

    // ---------------------------------------------------------------------
    fun startPreview() {
        try {
            previewUrl = address().rtspUrl(if (previewSub) 1 else 0)
        } catch (e: IllegalArgumentException) {
            message = e.message
        }
    }

    fun stopPreview() { previewUrl = null }
}
