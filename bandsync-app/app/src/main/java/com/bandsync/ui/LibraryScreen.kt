package com.bandsync.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.bandsync.audio.Song
import com.bandsync.session.SessionManager

private sealed interface PendingImport {
    data class Tree(val uri: Uri) : PendingImport
    data class Files(val uris: List<Uri>) : PendingImport
}

@Composable
fun LibraryScreen(onBack: () -> Unit) {
    val songs by SessionManager.songs.collectAsState()
    val progress by SessionManager.importProgress.collectAsState()
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var renameTarget by remember { mutableStateOf<Song?>(null) }
    var editTarget by remember { mutableStateOf<Song?>(null) }
    var deleteTarget by remember { mutableStateOf<Song?>(null) }

    val treeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) pending = PendingImport.Tree(uri)
        }
    val filesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) pending = PendingImport.Files(uris)
        }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Songs", style = MaterialTheme.typography.headlineSmall)
        }
        NoteBanner()
        val p = progress
        if (p != null) {
            Text(p.label, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = {
                    p.done.toFloat() / p.total.coerceAtLeast(1).toFloat()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { treeLauncher.launch(null) },
                enabled = p == null,
                modifier = Modifier.weight(1f)
            ) { Text("Import folder") }
            Button(
                onClick = { filesLauncher.launch(arrayOf("audio/*")) },
                enabled = p == null,
                modifier = Modifier.weight(1f)
            ) { Text("Import files") }
        }
        Text(
            "One song per folder, or pick the stem files directly. " +
                "WAV, MP3, M4A and FLAC are decoded once at import.",
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider()
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(songs, key = { it.id }) { song ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(song.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${song.nativeBpm.toInt()} BPM · ${song.stems.size} stems · ${songSizeLabel(song)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { renameTarget = song }) { Text("Rename") }
                            TextButton(onClick = { editTarget = song }) { Text("BPM") }
                            TextButton(onClick = { deleteTarget = song }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    pending?.let { pd ->
        ImportDialog(
            defaultName = when (pd) {
                is PendingImport.Tree ->
                    DocumentFile.fromTreeUri(ctx, pd.uri)?.name ?: "Imported song"
                is PendingImport.Files -> "Imported song"
            },
            onDismiss = { pending = null },
            onConfirm = { name, bpm, bpb ->
                pending = null
                when (pd) {
                    is PendingImport.Tree ->
                        SessionManager.startImportTree(ctx, pd.uri, name, bpm, bpb)
                    is PendingImport.Files ->
                        SessionManager.startImportFiles(ctx, pd.uris, name, bpm, bpb)
                }
            }
        )
    }

    renameTarget?.let { song ->
        var name by remember(song.id) { mutableStateOf(song.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename song") },
            text = {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SessionManager.renameSong(song.id, name)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    editTarget?.let { song ->
        var bpm by remember(song.id) { mutableStateOf(song.nativeBpm.toInt().toString()) }
        var bpb by remember(song.id) { mutableStateOf(song.beatsPerBar.toString()) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Song tempo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = bpm,
                        onValueChange = { bpm = it },
                        label = { Text("Native BPM") },
                        singleLine = true
                    )
                    TextField(
                        value = bpb,
                        onValueChange = { bpb = it },
                        label = { Text("Beats per bar") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val b = bpm.toDoubleOrNull()
                    val bb = bpb.toIntOrNull()
                    if (b != null && b in 20.0..400.0 && bb != null && bb in 1..12) {
                        SessionManager.updateSongMeta(song.id, b, bb)
                        editTarget = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("Cancel") }
            }
        )
    }

    deleteTarget?.let { song ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete song?") },
            text = {
                Text(
                    "“${song.name}” and its ${song.stems.size} stems will be " +
                        "removed from this phone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SessionManager.deleteSong(song.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

private fun songSizeLabel(song: Song): String {
    val bytes = try {
        song.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) {
        0L
    }
    return if (bytes >= 1_048_576) "${bytes / 1_048_576} MB" else "${bytes / 1024} KB"
}

@Composable
private fun ImportDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Double, Int) -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    var bpm by remember { mutableStateOf("120") }
    var bpb by remember { mutableStateOf("4") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import song") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Song name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = bpm,
                        onValueChange = { bpm = it },
                        label = { Text("Native BPM") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextField(
                        value = bpb,
                        onValueChange = { bpb = it },
                        label = { Text("Beats/bar") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Native BPM is the tempo the stems were rendered at — " +
                        "it's how the engine locks to the host. Get it right.",
                    style = MaterialTheme.typography.bodySmall
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val b = bpm.toDoubleOrNull()
                val bb = bpb.toIntOrNull()
                when {
                    name.isBlank() -> error = "Give the song a name."
                    b == null || b !in 20.0..400.0 -> error = "BPM must be 20–400."
                    bb == null || bb !in 1..12 -> error = "Beats per bar must be 1–12."
                    else -> onConfirm(name.trim(), b, bb)
                }
            }) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
