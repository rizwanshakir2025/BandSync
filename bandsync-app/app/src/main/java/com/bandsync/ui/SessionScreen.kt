package com.bandsync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bandsync.session.SessionManager

@Composable
fun SessionScreen(onLeave: () -> Unit) {
    val ui by SessionManager.uiState.collectAsState()
    val ctx = LocalContext.current

    if (ui.status == "starting") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Starting…", Modifier.padding(top = 12.dp))
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (ui.role == "host") {
            Text("HOSTING", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${ui.songName} · ${ui.targetBpm.toInt()} BPM",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Members (${ui.members.size}): " +
                    if (ui.members.isEmpty()) "none yet" else ui.members.joinToString(),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(ui.syncText, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ui.isPlaying) {
                    Button(onClick = { SessionManager.hostPlay() }) { Text("▶ Play") }
                } else {
                    Button(onClick = { SessionManager.hostStop() }) { Text("■ Stop") }
                }
            }
        } else {
            Text("SESSION", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Connected to ${ui.hostName.ifEmpty { "…" }}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(ui.syncText, style = MaterialTheme.typography.bodySmall)
            if (ui.songName.isNotEmpty()) {
                Text(
                    "${ui.songName} · ${ui.targetBpm.toInt()} BPM",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                if (ui.isPlaying) "▶ Playing" else "■ Stopped",
                style = MaterialTheme.typography.titleSmall
            )
        }

        NoteBanner()
        HorizontalDivider()
        Text("Your mix — only you hear this", style = MaterialTheme.typography.titleSmall)
        MixerPanel()

        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                SessionManager.leave(ctx)
                onLeave()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (ui.role == "host") "End session" else "Leave session") }
    }
}
