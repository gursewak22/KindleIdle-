package com.kindleidle.host

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.kindleidle.host.ui.HostScreen
import com.kindleidle.host.ui.IdleScreen
import com.kindleidle.host.ui.KindleIdleTheme
import com.kindleidle.host.ui.ScenesScreen
import com.kindleidle.host.ui.TasksScreen
import com.kindleidle.host.ui.TimerScreen

/**
 * The app is two things at once: the control panel for the server, and the
 * remote itself.
 *
 * The remote tabs are the phone half of docs/adr/0006 -- the same tasks,
 * stopwatch and scene picker the web remote offers -- except that they read
 * and write [ServerController.store] directly. No HTTP, no long-poll, no
 * second copy of the state: the Kindle's page still polls, and both are
 * looking at the same object.
 */
class MainActivity : ComponentActivity() {

    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Declined only costs the notification, not the server. */ }

    /**
     * Declining this one costs everything: the server keeps running and keeps
     * answering itself, while every other device on the Wi-Fi is met with
     * silence. So the refusal is surfaced rather than swallowed.
     */
    private val askLocalNetwork = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> ServerController.setLocalNetworkGranted(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ServerController.ensureInit(this)
        requestNotificationPermission()
        requestLocalNetworkPermission()

        // A host that has to be started by hand every time the phone reboots
        // is a host the Kindle cannot rely on.
        if (ServerController.autostart(this) && !ServerController.running.value) {
            ServerService.start(this)
        }

        setContent {
            KindleIdleTheme {
                AppScaffold()
            }
        }
    }

    /**
     * From Android 16, reaching devices on the same network is a separate
     * permission from INTERNET. Without it the socket binds, the handshake
     * completes, and the request bytes never arrive -- which looks exactly
     * like a broken server and is why this is asked for up front.
     */
    private fun requestLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < ANDROID_16) return
        val granted = ContextCompat.checkSelfPermission(
            this, LOCAL_NETWORK_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        ServerController.setLocalNetworkGranted(granted)
        if (!granted) askLocalNetwork.launch(LOCAL_NETWORK_PERMISSION)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        // The foreground service needs a visible notification, so without this
        // the server would be running with nothing to say so.
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Not in the SDK constants yet on every toolchain; 36 is Android 16. */
private const val ANDROID_16 = 36
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

private enum class Tab(val label: String) {
    Host("Host"),
    Idle("Idle"),
    Tasks("Tasks"),
    Timer("Timer"),
    Scenes("Scenes")
}

@Composable
private fun AppScaffold() {
    var tab by rememberSaveable { mutableStateOf(Tab.Host) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val state by ServerController.store.snapshot.collectAsState()

    // Full screen belongs to the idle screen alone: leaving the tab any other
    // way would strand the tab bar off-screen.
    val idleFullscreen = fullscreen && tab == Tab.Idle
    BackHandler(enabled = idleFullscreen) { fullscreen = false }

    Scaffold(
        bottomBar = {
            if (!idleFullscreen) {
                NavigationBar {
                    for (entry in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = {
                                if (entry != Tab.Idle) fullscreen = false
                                tab = entry
                            },
                            icon = {},
                            label = { Text(entry.label) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        }
    ) { padding ->
        val inner = Modifier.padding(padding)
        when (tab) {
            Tab.Host -> HostScreen(inner)
            Tab.Idle -> IdleScreen(
                state = state,
                fullscreen = idleFullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
                modifier = inner
            )
            Tab.Tasks -> TasksScreen(state, inner)
            Tab.Timer -> TimerScreen(state, inner)
            Tab.Scenes -> ScenesScreen(state, inner)
        }
    }
}
