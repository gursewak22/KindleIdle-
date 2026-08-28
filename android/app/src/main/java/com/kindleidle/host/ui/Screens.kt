package com.kindleidle.host.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kindleidle.host.ServerController
import com.kindleidle.host.ServerService
import com.kindleidle.host.core.State
import kotlinx.coroutines.delay

/* ===========================================================================
   Host -- the server's own control panel
=========================================================================== */

@Composable
fun HostScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val running by ServerController.running.collectAsState()
    val addresses by ServerController.addresses.collectAsState()
    val error by ServerController.error.collectAsState()
    val firstRunPassword by ServerController.firstRunPassword.collectAsState()
    val username by ServerController.username.collectAsState()
    val localNetwork by ServerController.localNetworkGranted.collectAsState()

    var showAccount by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(ServerController.port(context).toString()) }

    // The list of addresses changes when the phone joins or leaves a network,
    // and nothing else would notice while this screen is open.
    LaunchedEffect(running) {
        while (true) {
            ServerController.refreshAddresses()
            delay(5000)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        SectionTitle("Server")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = running,
                onCheckedChange = { on ->
                    // Also the standing intent: turning it off here means it
                    // stays off the next time the app is opened, rather than
                    // autostart quietly switching it back on.
                    ServerController.setAutostart(context, on)
                    if (on) ServerService.start(context) else ServerService.stop(context)
                }
            )
            Spacer(Modifier.size(14.dp))
            Column {
                Text(
                    if (running) "Running" else "Stopped",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (running) "Keep this app installed; the screen may sleep."
                    else "The Kindle cannot reach the screen while this is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        error?.let {
            Spacer(Modifier.size(14.dp))
            Note(it, error = true)
        }

        if (!localNetwork) {
            Spacer(Modifier.size(14.dp))
            Note(
                "This app is not allowed on the local network, so the Kindle " +
                    "cannot reach it -- the server will appear to run fine and " +
                    "answer nothing. Grant \"Local network devices\" in " +
                    "Settings → Apps → Kindle Idle Host → Permissions.",
                error = true
            )
        }

        firstRunPassword?.let { password ->
            Spacer(Modifier.size(18.dp))
            FirstRunCard(username, password) { ServerController.dismissFirstRunPassword() }
        }

        Spacer(Modifier.size(26.dp))
        SectionTitle("Addresses")

        if (addresses.isEmpty()) {
            Note("Not on a Wi-Fi network. The Kindle and this phone have to be on the same one.")
        } else {
            for (address in addresses) {
                val base = "http://$address:${ServerController.port(context)}"
                AddressRow("Kindle screen", "$base/", context)
                AddressRow("Phone remote", "$base/remote", context)
                Spacer(Modifier.size(10.dp))
            }
            Note(
                "Open the first address on the Kindle. It will ask to be signed " +
                    "in; use Pair a device below to get a code."
            )
        }

        Spacer(Modifier.size(26.dp))
        SectionTitle("Pair a device")
        PairingCard(enabled = running)

        Spacer(Modifier.size(26.dp))
        SectionTitle("Account")
        Text(
            "Signed in as \"$username\".",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.size(10.dp))
        OutlinedButton(onClick = { showAccount = true }) {
            Text("Change username or password")
        }
        Note(
            "Changing either signs every device out, including the Kindle.",
            spacedAbove = true
        )

        Spacer(Modifier.size(26.dp))
        SectionTitle("Port")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                singleLine = true,
                modifier = Modifier.size(width = 130.dp, height = 60.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
            Spacer(Modifier.size(12.dp))
            TextButton(
                onClick = {
                    val port = portText.toIntOrNull()
                    if (port != null && port in 1024..65535) {
                        ServerController.setPort(context, port)
                        if (running) {
                            ServerService.stop(context)
                            ServerService.start(context)
                        }
                    }
                },
                enabled = portText.toIntOrNull()?.let { it in 1024..65535 } == true
            ) { Text("Apply") }
        }
        Note("Ports below 1024 are not available to an app on Android.", spacedAbove = true)

        Spacer(Modifier.size(40.dp))
    }

    if (showAccount) {
        AccountDialog(onDismiss = { showAccount = false })
    }
}

@Composable
private fun FirstRunCard(username: String, password: String, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.onSurface)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text("Write this down", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "An account was created for this server. The password is not stored " +
                "in readable form and will not be shown again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(14.dp))
        Text("username   $username", fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        Text("password   $password", fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        Spacer(Modifier.size(14.dp))
        Row {
            val context = LocalContext.current
            OutlinedButton(onClick = { copy(context, "$username / $password") }) { Text("Copy") }
            Spacer(Modifier.size(10.dp))
            TextButton(onClick = onDismiss) { Text("I have it") }
        }
    }
}

@Composable
private fun PairingCard(enabled: Boolean) {
    var code by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(code) {
        while (code != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val left = expiresAt - now
    if (code != null && left <= 0) code = null

    Column {
        if (code == null) {
            Text(
                "Generate a six-digit code, then type it on the Kindle's sign-in page.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.size(10.dp))
            Button(
                enabled = enabled,
                onClick = {
                    val pair = ServerController.auth.newPairingCode()
                    code = pair.code
                    expiresAt = pair.expiresAt
                    now = System.currentTimeMillis()
                }
            ) { Text("Get a code") }
            if (!enabled) {
                Note("Start the server first.", spacedAbove = true)
            }
        } else {
            val shown = code!!.substring(0, 3) + " " + code!!.substring(3)
            Text(
                shown,
                fontSize = 42.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 18.dp)
            )
            Spacer(Modifier.size(8.dp))
            val seconds = (left / 1000).coerceAtLeast(0)
            Text(
                "Expires in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" +
                    " · works once",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.size(10.dp))
            Row {
                OutlinedButton(onClick = {
                    val pair = ServerController.auth.newPairingCode()
                    code = pair.code
                    expiresAt = pair.expiresAt
                }) { Text("New code") }
                Spacer(Modifier.size(10.dp))
                TextButton(onClick = {
                    ServerController.auth.clearPairingCode()
                    code = null
                }) { Text("Done") }
            }
        }
    }
}

@Composable
private fun AccountDialog(onDismiss: () -> Unit) {
    var user by remember { mutableStateOf(ServerController.auth.username) }
    var pass by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change the account") },
        text = {
            Column {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Username") },
                    singleLine = true
                )
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("New password") },
                    singleLine = true
                )
                problem?.let {
                    Spacer(Modifier.size(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    "Every signed-in device, the Kindle included, will have to " +
                        "sign in again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    ServerController.auth.setAccount(user, pass)
                    onDismiss()
                } catch (e: IllegalArgumentException) {
                    problem = e.message
                } catch (e: Exception) {
                    problem = e.message ?: "That did not work."
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddressRow(label: String, url: String, context: Context) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { copy(context, url) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(url, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        }
        Text("Copy", style = MaterialTheme.typography.labelLarge)
    }
}

/* ===========================================================================
   Tasks
=========================================================================== */

@Composable
fun TasksScreen(state: State, modifier: Modifier = Modifier) {
    var draft by remember { mutableStateOf("") }
    val store = ServerController.store

    fun submit() {
        if (draft.isBlank()) return
        store.addTodo(draft)
        draft = ""
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.size(18.dp))
        SectionTitle("Tasks")
        Text(
            "${state.open.size} open",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.size(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(200) },
                placeholder = { Text("Add a task…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() })
            )
            Spacer(Modifier.size(10.dp))
            Button(onClick = { submit() }, enabled = draft.isNotBlank()) { Text("Add") }
        }

        Spacer(Modifier.size(12.dp))

        val pending = state.todos.filter { !it.done }
        val done = state.todos.filter { it.done }

        if (state.todos.isEmpty()) {
            Note("Nothing on the list. Add one above.")
        }

        LazyColumn(Modifier.weight(1f)) {
            items(pending, key = { it.id }) { todo ->
                TodoRow(todo.id, todo.text, false)
            }
            if (done.isNotEmpty()) {
                item {
                    Spacer(Modifier.size(14.dp))
                    Text(
                        "Done · ${done.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                items(done, key = { it.id }) { todo ->
                    TodoRow(todo.id, todo.text, true)
                }
                item {
                    Spacer(Modifier.size(10.dp))
                    OutlinedButton(onClick = { ServerController.store.clearDone() }) {
                        Text("Clear finished")
                    }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun TodoRow(id: String, text: String, done: Boolean) {
    val store = ServerController.store
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { store.toggleTodo(id) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // A square outline that fills in, matching the box on the web pages
        // rather than a Material checkbox.
        Box(
            Modifier
                .size(20.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurface)
                .background(
                    if (done) MaterialTheme.colorScheme.onSurface else Color.Transparent
                )
        )
        Spacer(Modifier.size(14.dp))
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface
        )
        TextButton(onClick = { store.deleteTodo(id) }) { Text("×", fontSize = 22.sp) }
    }
}

/* ===========================================================================
   Timer
=========================================================================== */

@Composable
fun TimerScreen(state: State, modifier: Modifier = Modifier) {
    val store = ServerController.store
    val sw = state.stopwatch
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Only tick while it is running: a stopped stopwatch has nothing to
    // redraw, and this screen may sit open for an hour.
    LaunchedEffect(sw.running) {
        while (sw.running) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }

    val elapsed = sw.elapsed(if (sw.running) now else System.currentTimeMillis())

    Column(
        modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SectionTitle("Stopwatch", center = true)
        Spacer(Modifier.size(30.dp))
        Text(
            formatElapsed(elapsed),
            fontSize = 64.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.size(30.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            Button(onClick = { store.stopwatchToggle() }) {
                Text(if (sw.running) "Stop" else "Start")
            }
            Spacer(Modifier.size(10.dp))
            OutlinedButton(onClick = { store.stopwatchLap() }) { Text("Lap") }
            Spacer(Modifier.size(10.dp))
            OutlinedButton(onClick = { store.stopwatchReset() }) { Text("Reset") }
        }
        Spacer(Modifier.size(26.dp))

        if (sw.laps.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("#", Modifier.weight(0.2f),
                    style = MaterialTheme.typography.labelMedium)
                Text("Split", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium)
                Text("Total", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End)
            }
            HorizontalDivider()
            LazyColumn {
                items(sw.laps.size) { i ->
                    val lap = sw.laps[i]
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text("${sw.laps.size - i}", Modifier.weight(0.2f),
                            fontFamily = FontFamily.Monospace)
                        Text(formatElapsed(lap.split), Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace)
                        Text(formatElapsed(lap.at), Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val mm = m.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
}

/* ===========================================================================
   Scenes
=========================================================================== */

@Composable
fun ScenesScreen(state: State, modifier: Modifier = Modifier) {
    val store = ServerController.store
    val scenes = ServerController.scenes

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.size(18.dp))
        SectionTitle("Scenes")
        Text(
            "Choose a vignette for the Kindle",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(14.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(scenes.list.size) { i ->
                val scene = scenes.list[i]
                val chosen = scene.id == state.scene
                Column(
                    Modifier
                        .clickable { store.setScene(scene.id) }
                        .border(
                            width = if (chosen) 2.dp else 1.dp,
                            color = if (chosen) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .padding(6.dp)
                ) {
                    SceneThumb(
                        scene,
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(420f / 300f)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        scene.name,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/* ===========================================================================
   shared bits
=========================================================================== */

@Composable
private fun SectionTitle(text: String, center: Boolean = false) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth()
    )
    HorizontalDivider(
        Modifier.padding(top = 8.dp, bottom = 12.dp),
        thickness = 2.dp,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun Note(text: String, error: Boolean = false, spacedAbove: Boolean = false) {
    if (spacedAbove) Spacer(Modifier.size(10.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Kindle Idle", text))
}
