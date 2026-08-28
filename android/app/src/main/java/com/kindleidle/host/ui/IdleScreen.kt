package com.kindleidle.host.ui

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kindleidle.host.ServerController
import com.kindleidle.host.core.State
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Kindle is showing, on the phone.
 *
 * The same three parts as the Kindle page -- clock and date, the scene, and
 * the next few open tasks -- reading the same [State], so the phone and the
 * Kindle are never showing different things. The scene animates on the same
 * schedule idle.js sets, since both take FRAME_MS from the same place.
 *
 * Tapping goes full screen: without that this is a picture of an idle screen
 * sitting under a status bar and a row of tabs, which is not an idle screen.
 */
@Composable
fun IdleScreen(
    state: State,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scenes = ServerController.scenes
    val scene = remember(state.scene) {
        scenes.list.firstOrNull { it.id == state.scene }
            ?: scenes.list.first { it.id == scenes.defaultScene }
    }

    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(scene.id) {
        while (true) {
            delay(scenes.frameMs.toLong())
            frame++
        }
    }

    // Waking once a minute rather than once a second: nothing on this screen
    // shows seconds, and it is meant to be left on.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - (now % 60_000))
        }
    }

    KeepAwake(fullscreen)

    val date = Date(now)
    val clock = remember(now / 60_000) { SimpleDateFormat("HH:mm", Locale.US).format(date) }
    val dateLine = remember(now / 60_000) {
        SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(date)
    }

    Column(
        modifier
            .fillMaxSize()
            .clickable(
                // No ripple: a flash of grey across a still scene every time
                // it is touched is exactly the wrong effect here.
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleFullscreen
            )
            .padding(horizontal = 24.dp, vertical = if (fullscreen) 24.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            clock,
            fontSize = if (fullscreen) 88.sp else 64.sp,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            dateLine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.size(12.dp))

        SceneView(
            scene = scene,
            frame = frame,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(420f / 300f)
        )

        Spacer(Modifier.size(16.dp))

        UpNext(state, Modifier.weight(1f, fill = false))

        if (!fullscreen) {
            Spacer(Modifier.size(12.dp))
            Text(
                "Tap for full screen",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The same four-and-a-summary the Kindle page shows, so a glance at either
 * gives the same answer.
 */
@Composable
private fun UpNext(state: State, modifier: Modifier = Modifier) {
    val open = state.open

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "UP NEXT",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))

        if (open.isEmpty()) {
            Text(
                "All clear.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (todo in open.take(UP_NEXT_LIMIT)) {
                Text(
                    todo.text,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            val rest = open.size - UP_NEXT_LIMIT
            if (rest > 0) {
                Text(
                    "and $rest more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Holds the screen on while the idle screen is showing, and hides the system
 * bars while it is full screen. Both are undone on the way out, so leaving
 * this tab does not leave the phone awake.
 */
@Composable
private fun KeepAwake(fullscreen: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(fullscreen) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            if (fullscreen) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private const val UP_NEXT_LIMIT = 4
