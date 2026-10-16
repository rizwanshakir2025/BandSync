package com.bandsync.ui

import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bandsync.net.Discovery
import com.bandsync.session.SessionManager

@Composable
fun JoinScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val disc = remember { Discovery(ctx) }
    val found = remember { mutableStateListOf<Pair<String, NsdServiceInfo>>() }

    var name by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        disc.startDiscovery(object : Discovery.Listener {
            override fun onFound(service: NsdServiceInfo) {
                main.post {
                    if (found.none { it.first == service.serviceName }) {
                        found.add(service.serviceName to service)
                    }
                }
            }

            override fun onLost(service: NsdServiceInfo) {
                main.post { found.removeAll { it.first == service.serviceName } }
            }
        })
        onDispose { disc.stopDiscovery() }
    }

    fun connect(host: String, port: Int) {
        if (name.isBlank()) {
            SessionManager.postNote("Enter your name first.")
            return
        }
        SessionManager.startJoin(ctx, host, port, name.trim())
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Join a session", style = MaterialTheme.typography.headlineSmall)
        NoteBanner()
        TextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Hosts on this Wi-Fi", style = MaterialTheme.typography.titleSmall)
        if (found.isEmpty()) {
            Text(
                "None found yet — join the same Wi-Fi (or the host's hotspot) as the host. " +
                    "Some routers block discovery; use manual entry below in that case.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(found, key = { it.first }) { (svcName, info) ->
                Button(
                    onClick = {
                        disc.resolve(info) { host, port ->
                            if (host != null && port > 0) {
                                main.post { connect(host, port) }
                            } else {
                                main.post { SessionManager.postNote("Could not resolve $svcName — try manual entry.") }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(svcName.removePrefix("BandSync-")) }
            }
        }

        Text("Or enter manually", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                label = { Text("Host IP") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextField(
                value = manualPort,
                onValueChange = { manualPort = it.filter { c -> c.isDigit() } },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.weight(0.5f)
            )
        }
        Button(
            onClick = {
                val p = manualPort.toIntOrNull()
                if (manualIp.isBlank() || p == null) {
                    SessionManager.postNote("Enter the host IP and port shown on the host's screen.")
                    return@Button
                }
                connect(manualIp.trim(), p)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Connect") }

        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
