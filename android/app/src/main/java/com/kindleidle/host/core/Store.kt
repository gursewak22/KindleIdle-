package com.kindleidle.host.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The shared state, ported from server/store.js.
 *
 * Same file format, so `data/state.json` can be carried between the Node
 * server and this one. Same version counter, so the same long-poll works.
 *
 * Two differences that the platform forces:
 *
 *  - the state is immutable and swapped wholesale, because Compose reads it
 *    from the main thread while HTTP worker threads write it;
 *  - [snapshot] exposes it as a StateFlow so the native screens observe it
 *    directly rather than polling their own server over the loopback.
 */

data class Lap(val at: Long, val split: Long)

data class Todo(
    val id: String,
    val text: String,
    val done: Boolean,
    val createdAt: Long,
    val doneAt: Long? = null
)

data class Stopwatch(
    val running: Boolean = false,
    val startedAt: Long? = null,
    val accumulated: Long = 0,
    val laps: List<Lap> = emptyList()
) {
    /** Milliseconds on the clock right now. */
    fun elapsed(now: Long = System.currentTimeMillis()): Long =
        accumulated + if (running && startedAt != null) now - startedAt else 0
}

data class State(
    val todos: List<Todo> = emptyList(),
    val scene: String,
    val stopwatch: Stopwatch = Stopwatch()
) {
    val open: List<Todo> get() = todos.filter { !it.done }
}

class Store(dataDir: File, private val scenes: Scenes) {

    private val file = File(dataDir, "state.json")
    private val tmp = File(dataDir, "state.json.tmp")

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var state: State = blank()
    private var version: Int = 1

    private val saver = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ki-store-save").apply { isDaemon = true }
    }
    private var pendingSave: ScheduledFuture<*>? = null

    private val _snapshot = MutableStateFlow(state)

    /** The current state, for Compose. Replaced whole on every change. */
    val snapshot: StateFlow<State> get() = _snapshot

    init {
        load()
        _snapshot.value = state
    }

    private fun blank() = State(scene = scenes.defaultScene)

    /* ---------------------------------------------------------------------
       reading
    --------------------------------------------------------------------- */

    fun getState(): State = lock.withLock { state }

    fun getVersion(): Int = lock.withLock { version }

    /**
     * Blocks until the version moves past [since], or [timeoutMs] elapses.
     * A null or already-stale [since] returns at once, which is what makes a
     * client that has fallen behind catch up on its next poll instead of
     * waiting out a hold it does not need.
     */
    fun waitForChange(since: Int?, timeoutMs: Long): Int = lock.withLock {
        if (since == null || since != version) return version
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (since == version && left > 0) {
            left = try {
                changed.awaitNanos(left)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        version
    }

    /* ---------------------------------------------------------------------
       writing

       Every mutation goes through [mutate], which swaps the state, bumps the
       version, wakes the long-polls and schedules a save. Returning the
       unchanged state from the block means "nothing happened" and skips all
       four, matching the no-op returns in store.js.
    --------------------------------------------------------------------- */

    private fun mutate(block: (State) -> State): Boolean {
        lock.withLock {
            val next = block(state)
            if (next === state) return false
            state = next
            version++
            changed.signalAll()
        }
        _snapshot.value = state
        scheduleSave()
        return true
    }

    private fun newId(): String =
        java.lang.Long.toString(System.currentTimeMillis(), 36) +
            java.lang.Long.toString((Math.random() * 1.0e9).toLong(), 36).take(4)

    fun addTodo(text: String?): Boolean = mutate { s ->
        val clean = (text ?: "").replace(Regex("\\s+"), " ").trim().take(200)
        if (clean.isEmpty()) return@mutate s
        s.copy(todos = s.todos + Todo(newId(), clean, false, System.currentTimeMillis()))
    }

    fun toggleTodo(id: String?): Boolean = mutate { s ->
        if (s.todos.none { it.id == id }) return@mutate s
        s.copy(todos = s.todos.map {
            if (it.id != id) it
            else it.copy(done = !it.done, doneAt = if (!it.done) System.currentTimeMillis() else null)
        })
    }

    fun deleteTodo(id: String?): Boolean = mutate { s ->
        val kept = s.todos.filter { it.id != id }
        if (kept.size == s.todos.size) return@mutate s
        s.copy(todos = kept)
    }

    fun clearDone(): Boolean = mutate { s ->
        val kept = s.todos.filter { !it.done }
        if (kept.size == s.todos.size) return@mutate s
        s.copy(todos = kept)
    }

    fun setScene(id: String?): Boolean = mutate { s ->
        if (id == null || !scenes.isScene(id) || s.scene == id) return@mutate s
        s.copy(scene = id)
    }

    fun stopwatchStart(): Boolean = mutate { s ->
        if (s.stopwatch.running) return@mutate s
        s.copy(stopwatch = s.stopwatch.copy(running = true, startedAt = System.currentTimeMillis()))
    }

    fun stopwatchStop(): Boolean = mutate { s ->
        val sw = s.stopwatch
        if (!sw.running || sw.startedAt == null) return@mutate s
        s.copy(stopwatch = sw.copy(
            running = false,
            startedAt = null,
            accumulated = sw.accumulated + (System.currentTimeMillis() - sw.startedAt)
        ))
    }

    fun stopwatchToggle(): Boolean =
        if (getState().stopwatch.running) stopwatchStop() else stopwatchStart()

    fun stopwatchReset(): Boolean = mutate { s -> s.copy(stopwatch = Stopwatch()) }

    fun stopwatchLap(): Boolean = mutate { s ->
        val sw = s.stopwatch
        val elapsed = sw.elapsed()
        if (elapsed <= 0) return@mutate s
        val lap = Lap(at = elapsed, split = elapsed - (sw.laps.firstOrNull()?.at ?: 0))
        s.copy(stopwatch = sw.copy(laps = (listOf(lap) + sw.laps).take(20)))
    }

    /* ---------------------------------------------------------------------
       persistence -- same shape as the Node server's data/state.json
    --------------------------------------------------------------------- */

    private fun load() {
        val raw = try {
            if (!file.exists()) return
            JSONObject(file.readText())
        } catch (e: Exception) {
            // Missing, truncated or from a future version: start clean rather
            // than refusing to boot. The screen matters more than the list.
            return
        }

        val todos = mutableListOf<Todo>()
        val arr = raw.optJSONArray("todos")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val text = o.optString("text")
                if (id.isEmpty() || text.isEmpty()) continue
                todos += Todo(
                    id = id,
                    text = text,
                    done = o.optBoolean("done"),
                    createdAt = o.optLong("createdAt"),
                    doneAt = if (o.isNull("doneAt")) null else o.optLong("doneAt")
                )
            }
        }

        val swo = raw.optJSONObject("stopwatch")
        val laps = mutableListOf<Lap>()
        val lapArr = swo?.optJSONArray("laps")
        if (lapArr != null) {
            for (i in 0 until lapArr.length()) {
                val o = lapArr.optJSONObject(i) ?: continue
                laps += Lap(o.optLong("at"), o.optLong("split"))
            }
        }
        val stopwatch = if (swo == null) Stopwatch() else {
            val running = swo.optBoolean("running")
            Stopwatch(
                running = running,
                startedAt = if (running && !swo.isNull("startedAt")) swo.optLong("startedAt") else null,
                accumulated = swo.optLong("accumulated"),
                laps = laps
            )
        }

        // A scene that no longer ships falls back rather than leaving both
        // screens with nothing to draw.
        val scene = raw.optString("scene").takeIf { scenes.isScene(it) } ?: scenes.defaultScene

        state = State(todos = todos, scene = scene, stopwatch = stopwatch)
    }

    /** Coalesces bursts of edits into one disk write. */
    private fun scheduleSave() {
        lock.withLock {
            if (pendingSave?.isDone == false) return
            pendingSave = saver.schedule({ writeNow() }, 250, TimeUnit.MILLISECONDS)
        }
    }

    private fun writeNow() {
        val json = lock.withLock { toJson(state) }
        try {
            file.parentFile?.mkdirs()
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                // renameTo will not clobber an existing file on some Android
                // filesystems, so fall back to replacing it outright.
                file.delete()
                if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
            }
        } catch (e: Exception) {
            android.util.Log.e("KindleIdle", "save failed: ${e.message}")
        }
    }

    /** Flushes any pending write. Called when the service is going away. */
    fun flush() {
        lock.withLock { pendingSave?.cancel(false) }
        writeNow()
    }

    private fun toJson(s: State): String {
        val todos = JSONArray()
        for (t in s.todos) {
            todos.put(JSONObject().apply {
                put("id", t.id)
                put("text", t.text)
                put("done", t.done)
                put("createdAt", t.createdAt)
                if (t.doneAt != null) put("doneAt", t.doneAt) else put("doneAt", JSONObject.NULL)
            })
        }
        val laps = JSONArray()
        for (l in s.stopwatch.laps) {
            laps.put(JSONObject().apply {
                put("at", l.at)
                put("split", l.split)
            })
        }
        return JSONObject().apply {
            put("todos", todos)
            put("scene", s.scene)
            put("stopwatch", JSONObject().apply {
                put("running", s.stopwatch.running)
                put("startedAt", s.stopwatch.startedAt ?: JSONObject.NULL)
                put("accumulated", s.stopwatch.accumulated)
                put("laps", laps)
            })
        }.toString(2)
    }
}
