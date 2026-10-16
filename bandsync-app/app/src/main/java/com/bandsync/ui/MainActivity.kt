package com.bandsync.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bandsync.session.SessionManager

sealed interface Screen {
    data object Home : Screen
    data object HostSetup : Screen
    data object Join : Screen
    data object Session : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionManager.init(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { BandSyncApp() }
    }
}

@Composable
fun BandSyncApp() {
    val ui by SessionManager.uiState.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    // follow the session lifecycle automatically
    LaunchedEffect(ui.status) {
        when (ui.status) {
            "starting", "live" -> if (screen != Screen.Session) screen = Screen.Session
            "idle" -> if (screen == Screen.Session) screen = Screen.Home
        }
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    onHost = { screen = Screen.HostSetup },
                    onJoin = { screen = Screen.Join }
                )
                Screen.HostSetup -> HostSetupScreen(onBack = { screen = Screen.Home })
                Screen.Join -> JoinScreen(onBack = { screen = Screen.Home })
                Screen.Session -> SessionScreen(onLeave = { screen = Screen.Home })
            }
        }
    }
}
