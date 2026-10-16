package com.bandsync.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bandsync.session.SessionManager

/** Red banner for transient notes/errors, shown on every screen. */
@Composable
fun NoteBanner() {
    val ui by SessionManager.uiState.collectAsState()
    if (ui.note.isNotEmpty()) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.note,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { SessionManager.clearNote() }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
fun HomeScreen(onHost: () -> Unit, onJoin: () -> Unit) {
    val songs by SessionManager.songs.collectAsState()
    val busy by SessionManager.busy.collectAsState()
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("BandSync", style = MaterialTheme.typography.headlineLarge)
        Text(
            "One click for the whole band, over Wi-Fi. " +
                "The host picks the song and tempo — every phone plays the same " +
                "stems at the same tempo, each with its own mix.",
            style = MaterialTheme.typography.bodyMedium
        )
        NoteBanner()
        Button(onClick = onHost, Modifier.fillMaxWidth()) { Text("Host a session") }
        Button(onClick = onJoin, Modifier.fillMaxWidth()) { Text("Join a session") }
        HorizontalDivider()
        Text(
            "Songs on this phone (${songs.size})",
            style = MaterialTheme.typography.titleSmall
        )
        songs.forEach { s ->
            Text(
                "• ${s.name} — ${s.nativeBpm.toInt()} BPM, ${s.stems.size} stems",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (songs.isEmpty()) {
            if (busy) {
                CircularProgressIndicator()
            } else {
                Button(onClick = { SessionManager.createDemoSong() }) {
                    Text("Create demo song")
                }
            }
            Text(
                "No songs yet. Add a folder per song under the app's songs directory " +
                    "(song.json + one WAV per stem), or generate the demo song to test with two phones.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.weight(1f))
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            Button(
                onClick = {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                },
                Modifier.fillMaxWidth()
            ) { Text("Allow playback with screen locked") }
        }
    }
}

@Composable
fun MixerPanel() {
    val names by SessionManager.stemNames.collectAsState()
    val vols by SessionManager.stemVolumes.collectAsState()
    val clickOn by SessionManager.clickEnabled.collectAsState()
    val clickVol by SessionManager.clickVolume.collectAsState()

    if (names.isEmpty()) {
        Text(
            "Stems appear here once the song starts.",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEach { n ->
            val v = vols[n] ?: 1f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = v,
                    onValueChange = { SessionManager.setStemVolume(n, it) },
                    valueRange = 0f..1.5f,
                    modifier = Modifier.weight(1f)
                )
                Text("${(v * 100).toInt()}%", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Click", Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
            Switch(checked = clickOn, onCheckedChange = { SessionManager.setClickEnabled(it) })
            Slider(
                value = clickVol,
                onValueChange = { SessionManager.setClickVolume(it) },
                valueRange = 0f..1.5f,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }
    }
}
