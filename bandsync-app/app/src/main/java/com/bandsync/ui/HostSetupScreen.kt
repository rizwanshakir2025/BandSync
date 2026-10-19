package com.bandsync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bandsync.session.SessionManager

@Composable
fun HostSetupScreen(onBack: () -> Unit) {
    val songs by SessionManager.songs.collectAsState()
    val ctx = LocalContext.current

    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = songs.find { it.id == selectedId } ?: songs.firstOrNull()
    LaunchedEffect(songs) {
        if (selectedId == null) selectedId = songs.firstOrNull()?.id
    }

    var bpm by remember { mutableStateOf(100f) }
    LaunchedEffect(selected) {
        selected?.let { bpm = it.nativeBpm.toFloat() }
    }
    var countIn by remember { mutableStateOf(1) }
    var loop by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Host a session", style = MaterialTheme.typography.headlineSmall)
        NoteBanner()

        TextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Song", style = MaterialTheme.typography.titleSmall)
        if (songs.isEmpty()) {
            Text(
                "No songs on this phone yet — import one from the Songs library first.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        songs.forEach { song ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { selectedId = song.id }
            ) {
                RadioButton(
                    selected = selected?.id == song.id,
                    onClick = { selectedId = song.id }
                )
                Text(
                    "${song.name} (${song.nativeBpm.toInt()} BPM, ${song.stems.size} stems)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Text("Tempo: ${bpm.toInt()} BPM", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = bpm,
            onValueChange = { bpm = it },
            valueRange = 40f..220f,
            steps = 179,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Count-in bars", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(0, 1, 2).forEach { n ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { countIn = n }.padding(end = 16.dp)
                ) {
                    RadioButton(selected = countIn == n, onClick = { countIn = n })
                    Text("$n")
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = loop, onCheckedChange = { loop = it })
            Text("  Loop song", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                val song = selected ?: return@Button
                SessionManager.startHosting(
                    ctx, song, bpm.toDouble(), countIn, loop, name.trim().ifEmpty { "Host" }
                )
            },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start hosting") }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
