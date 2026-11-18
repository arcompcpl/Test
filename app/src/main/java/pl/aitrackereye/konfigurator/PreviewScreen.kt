@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package pl.aitrackereye.konfigurator

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

@Composable
fun PreviewTab(vm: MainViewModel) {
    val url = vm.previewUrl
    var error by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown("Podgląd", listOf("główny" to false, "pomocniczy" to true), vm.previewSub) { vm.previewSub = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { error = null; vm.startPreview() }) { Text("Uruchom podgląd") }
        OutlinedButton(onClick = { vm.stopPreview() }, enabled = url != null) { Text("Zatrzymaj") }
    }
    if (url != null) {
        val context = LocalContext.current
        val exo = remember(url) {
            ExoPlayer.Builder(context).build().apply {
                val source = RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url))
                setMediaSource(source)
                addListener(object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) {
                        error = "Nie można odtworzyć RTSP (${e.errorCodeName}). Sprawdź port, login, hasło i czy strumień jest włączony."
                    }
                })
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(exo) { onDispose { exo.release() } }
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    this.player = exo
                }
            },
            update = { it.player = exo },
        )
    }
    error?.let { Text(it) }
    Hint("Podgląd RTSP (port zwykle 554). Nie oznacza, że wybrany strumień jest zapisywany na kartę SD - to oddzielne ustawienie.")
}
