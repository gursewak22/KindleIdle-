package com.kindleidle.host

import android.content.Context
import com.kindleidle.host.core.Auth
import com.kindleidle.host.core.Render
import com.kindleidle.host.core.Scenes
import com.kindleidle.host.core.Store
import com.kindleidle.host.net.HttpServer
import com.kindleidle.host.net.Lan
import com.kindleidle.host.net.Router
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.BindException

/**
 * One server, one store, one account, for the whole process.
 *
 * A singleton because there is genuinely one of each: the foreground service
 * runs the socket, and the native screens read and write the same [Store]
 * in-process rather than talking to the server over the loopback. That is the
 * whole reason the native remote is worth having -- no HTTP round trip, no
 * second copy of the state to keep in step.
 */
object ServerController {

    private const val PREFS = "kindle-idle-host"
    private const val KEY_PORT = "port"
    private const val KEY_AUTOSTART = "autostart"
    const val DEFAULT_PORT = 8080

    lateinit var scenes: Scenes
        private set
    lateinit var store: Store
        private set
    lateinit var auth: Auth
        private set
    lateinit var render: Render
        private set

    private var server: HttpServer? = null
    private var appContext: Context? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> get() = _running

    private val _addresses = MutableStateFlow<List<String>>(emptyList())
    val addresses: StateFlow<List<String>> get() = _addresses

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = _error

    /**
     * The password from the run that created the account. Held only in memory
     * and only until dismissed: after that the sole copy is the scrypt hash
     * in auth.json, and a lost one is replaced from the Account screen rather
     * than recovered.
     */
    private val _firstRunPassword = MutableStateFlow<String?>(null)
    val firstRunPassword: StateFlow<String?> get() = _firstRunPassword

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> get() = _username

    /**
     * Whether this app may talk to the local network at all.
     *
     * From Android 16 that is a permission of its own, separate from
     * INTERNET, and without it the server still binds, still accepts
     * connections and still answers itself -- the request bytes from any
     * other device simply never arrive. There is nothing to see from the
     * inside, so the UI has to say it out loud.
     */
    private val _localNetworkGranted = MutableStateFlow(true)
    val localNetworkGranted: StateFlow<Boolean> get() = _localNetworkGranted

    fun setLocalNetworkGranted(granted: Boolean) {
        _localNetworkGranted.value = granted
    }

    @Volatile private var initialised = false

    fun ensureInit(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val app = context.applicationContext
            appContext = app

            // Mirrors the Node server's layout, so a data/ folder can be
            // carried from one host to the other.
            val dataDir = File(app.filesDir, "data").apply { mkdirs() }

            scenes = Scenes.load(app.assets)
            store = Store(dataDir, scenes)
            auth = Auth(dataDir)
            render = Render(scenes)

            val info = auth.init()
            _username.value = info.username
            if (info.password != null) _firstRunPassword.value = info.password

            initialised = true
        }
    }

    fun dismissFirstRunPassword() {
        _firstRunPassword.value = null
    }

    /* ---------------------------------------------------------------------
       settings
    --------------------------------------------------------------------- */

    fun port(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PORT, port).apply()
    }

    fun autostart(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOSTART, true)

    fun setAutostart(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOSTART, on).apply()
    }

    /* ---------------------------------------------------------------------
       the socket
    --------------------------------------------------------------------- */

    /** Called by the service. Returns false and sets [error] if the port is taken. */
    fun startServer(context: Context): Boolean {
        ensureInit(context)
        if (server?.isRunning == true) {
            refreshAddresses()
            return true
        }

        val router = Router(context.applicationContext.assets, store, auth, render, scenes)
        val http = HttpServer(port(context)) { request -> router.handle(request) }

        return try {
            http.start()
            server = http
            _running.value = true
            _error.value = null
            refreshAddresses()
            true
        } catch (e: BindException) {
            _error.value = "Port ${port(context)} is already in use. Pick another one below."
            _running.value = false
            false
        } catch (e: Exception) {
            _error.value = e.message ?: "The server could not start."
            _running.value = false
            false
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
        _running.value = false
        if (initialised) store.flush()
    }

    fun refreshAddresses() {
        _addresses.value = Lan.addresses()
    }

    fun baseUrls(context: Context): List<String> =
        _addresses.value.map { "http://$it:${port(context)}" }
}
