package pl.aitrackereye.konfigurator

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF006D89)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, secondary = Teal)) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFFEEF3F7)) { App(vm) }
            }
        }
    }
}

@Composable
fun App(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val titles = listOf("Połączenie", "Nagrywanie", "Harmonogram", "Obraz", "Podgląd", "Diagnostyka")
    Column(Modifier.fillMaxSize()) {
        Text(
            "AiTrackerEye  •  Konfigurator kamer", color = Teal, fontWeight = FontWeight.Bold, fontSize = 20.sp,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 6.dp),
        )
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        if (vm.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                0 -> ConnectionTab(vm)
                1 -> RecordingTab(vm)
                2 -> ScheduleTab(vm)
                3 -> EncodingTab(vm)
                4 -> PreviewTab(vm)
                else -> DiagnosticTab(vm)
            }
        }
        Text(
            vm.status, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
    vm.message?.let { msg ->
        AlertDialog(onDismissRequest = { vm.message = null }, confirmButton = { TextButton({ vm.message = null }) { Text("OK") } },
            title = { Text("AiTrackerEye") }, text = { Text(msg) })
    }
    vm.dialog?.let { d ->
        AlertDialog(
            onDismissRequest = { vm.dialog = null },
            title = { Text(d.title) }, text = { Text(d.text) },
            confirmButton = { TextButton({ vm.dialog = null; d.onConfirm() }) { Text("Tak") } },
            dismissButton = { TextButton({ vm.dialog = null }) { Text("Nie") } },
        )
    }
}

// ----------------------------------------------------------------------------- elementy wspólne

@Composable
fun Hint(text: String) = Text(text, fontSize = 12.sp, color = Color(0xFF476174))

@Composable
fun Panel(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Teal)
            content()
        }
    }
}

@Composable
fun Field(label: String, value: String, enabled: Boolean = true, number: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = modifier,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}

@Composable
fun <T> Dropdown(label: String, options: List<Pair<String, T>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${options.firstOrNull { it.second == selected }?.first ?: "—"}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, value) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false })
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun NeedConnection(vm: MainViewModel) {
    if (!vm.connectedOk) Hint("Najpierw połącz się z kamerą (zakładka „Połączenie”).")
}

// ----------------------------------------------------------------------------- zakładki

@Composable
fun ConnectionTab(vm: MainViewModel) {
    var showPass by remember { mutableStateOf(false) }
    Panel("Połączenie z kamerą") {
        Field("IP / host (np. 192.168.1.108)", vm.host) { vm.host = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("HTTP(S)", vm.httpPort, number = true, modifier = Modifier.weight(1f)) { vm.httpPort = it }
            Field("RTSP", vm.rtspPort, number = true, modifier = Modifier.weight(1f)) { vm.rtspPort = it }
            Field("Kanał", vm.channel, number = true, modifier = Modifier.weight(1f)) { vm.channel = it }
        }
        Field("Login", vm.user) { vm.user = it }
        OutlinedTextField(
            value = vm.password, onValueChange = { vm.password = it }, label = { Text("Hasło (nie jest zapisywane)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showPass, onCheckedChange = { showPass = it }); Text("Pokaż hasło")
        }
        SwitchRow("HTTPS", vm.https) { vm.onHttpsToggle(it) }
        SwitchRow("Sprawdzaj certyfikat", vm.verifyTls, enabled = vm.https) { vm.verifyTls = it }
        Button(onClick = { vm.connect() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Połącz i odczytaj") }
    }
    Hint("Telefon musi być w tej samej sieci (Wi-Fi) co kamera, np. oba w 192.168.1.x. Dla HTTP bez HTTPS hasło idzie po sieci bez szyfrowania - używaj zaufanej sieci.")
    OutlinedButton(onClick = { vm.syncTime() }, enabled = vm.connectedOk && !vm.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Synchronizuj datę i godzinę z telefonem")
    }
}

@Composable
fun RecordingTab(vm: MainViewModel) {
    NeedConnection(vm)
    Panel("Sterowanie zapisem na kartę SD") {
        Dropdown("Tryb nagrywania", listOf("Auto - według harmonogramu" to 0, "Ręcznie - ciągle" to 1, "Wyłącz nagrywanie" to 2),
            vm.recMode, enabled = vm.recModeAvail) { vm.recMode = it }
        Field("Długość jednego pliku (min, 0 = nie zmieniaj)", vm.segment, enabled = vm.segmentAvail, number = true) { vm.segment = it }
        if (vm.segmentInfo.isNotEmpty()) Hint(vm.segmentInfo)
        Dropdown("Gdy karta jest pełna", listOf("Nadpisuj najstarsze" to true, "Zatrzymaj nagrywanie" to false),
            vm.overwrite, enabled = vm.overwriteAvail) { vm.overwrite = it }
        Dropdown("Strumień zapisu na SD", listOf("Główny" to "main", "Pomocniczy" to "sub"),
            vm.recStream, enabled = vm.recStreamAvail) { vm.recStream = it }
        if (!vm.recStreamAvail) Hint("Ten model nie udostępnia jednoznacznego pola strumienia zapisu w CGI - opcja wyłączona zamiast zgadywania.")
    }
    Button(onClick = { vm.saveRecording() }, enabled = vm.connectedOk && !vm.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Zapisz ustawienia nagrywania")
    }
}

@Composable
fun ScheduleTab(vm: MainViewModel) {
    NeedConnection(vm)
    Hint(vm.scheduleHint)
    val modes = listOf("Całą dobę" to "all", "Wybrane godziny" to "range", "Wyłączone" to "off", "Złożony - wybierz nowy" to "complex")
    vm.days.forEach { d ->
        Panel(d.name) {
            Dropdown("Tryb", modes, d.mode) { d.mode = it }
            if (d.mode == "range") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Od (GG:MM)", d.start, modifier = Modifier.weight(1f)) { d.start = it }
                    Field("Do (GG:MM)", d.end, modifier = Modifier.weight(1f)) { d.end = it }
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = vm.allowReplace, onCheckedChange = { vm.allowReplace = it })
        Text("Zgadzam się zastąpić istniejące reguły zdarzeń i/lub wiele okresów nagrywania", fontSize = 13.sp, color = Color(0xFF9C3C0A))
    }
    Button(onClick = { vm.saveSchedule() }, enabled = vm.connectedOk && !vm.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Zapisz harmonogram tygodnia")
    }
}

@Composable
fun EncodingTab(vm: MainViewModel) {
    NeedConnection(vm)
    Panel("Kodowanie obrazu") {
        Dropdown("Strumień", listOf("Główny (Main)" to 0, "Pomocniczy (Sub 1)" to 1), vm.encStream, enabled = vm.connectedOk) { vm.selectEncStream(it) }
        Dropdown("Kodek", vm.codecs.map { it to it }, vm.codec, enabled = vm.encAvail && vm.codecAvail) { vm.codec = it }
        Field("Liczba klatek (FPS)", vm.fps, enabled = vm.encAvail && vm.fpsAvail, number = true) { vm.fps = it }
        Field("Bitrate (kb/s)", vm.bitrate, enabled = vm.encAvail && vm.bitrateAvail, number = true) { vm.bitrate = it }
        Dropdown("Sterowanie bitrate", listOf("Stały (CBR)" to "CBR", "Zmienny (VBR)" to "VBR"), vm.control, enabled = vm.encAvail && vm.controlAvail) { vm.control = it }
        Field("Interwał klatek I (GOP)", vm.gop, enabled = vm.encAvail && vm.gopAvail, number = true) { vm.gop = it }
        Text("Rozdzielczość (odczyt): ${vm.resolution}")
        if (vm.encHint.isNotEmpty()) Hint(vm.encHint)
    }
    Hint("Zmiana kodowania może przerwać podgląd. Zapisywane są wyłącznie pola zwrócone wcześniej przez kamerę.")
    Button(onClick = { vm.saveEncoding() }, enabled = vm.connectedOk && !vm.busy && vm.encAvail, modifier = Modifier.fillMaxWidth()) {
        Text("Zapisz parametry obrazu")
    }
}

@Composable
fun DiagnosticTab(vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun share(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, title))
    }
    Hint("Surowe wartości API kamery (bez hasła). Przydatne do dopasowania obsługi konkretnego modelu. Przed wysłaniem sprawdź, czy nie zawierają danych, których nie chcesz ujawniać.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.diagnostic)) }, enabled = vm.diagnostic.isNotEmpty()) { Text("Kopiuj") }
        OutlinedButton(onClick = { share(vm.diagnostic, "Diagnostyka") }, enabled = vm.diagnostic.isNotEmpty()) { Text("Udostępnij") }
    }
    vm.lastBackupText?.let { backup ->
        OutlinedButton(onClick = { share(backup, "Kopia konfiguracji") }) { Text("Udostępnij ostatnią kopię konfiguracji") }
    }
    Text(
        if (vm.diagnostic.isEmpty()) "Najpierw połącz się z kamerą." else vm.diagnostic,
        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
    )
}
