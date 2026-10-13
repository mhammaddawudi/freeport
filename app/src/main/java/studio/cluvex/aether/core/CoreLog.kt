package studio.cluvex.aether.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The in-app log the Logs tab shows, mirrored to logcat.
 *
 * Lines keep the core's own prefixes — `[+]` for progress, `[-]` for a
 * recoverable problem, `[!]` for a hard failure — so a log copied out of the
 * app reads like one from the CLI.
 */
object CoreLog {

    enum class Level { Info, Good, Warn, Error }

    data class Line(val id: Long, val timeMillis: Long, val level: Level, val text: String)

    private const val CAPACITY = 500
    private const val TAG = "Aether"

    private var nextId = 0L
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    /**
     * Called from the JNI shim for every line the Rust core writes to stderr,
     * so the core's own view of a connection lands next to the app's.
     */
    @JvmStatic
    fun fromCore(line: String) {
        val text = line.substringAfter("aether: ").trim()
        if (text.isEmpty()) return
        append(
            when {
                text.startsWith("[!]") || text.contains("ERROR") -> Level.Error
                text.startsWith("[-]") || text.contains("WARN") -> Level.Warn
                text.startsWith("[+]") -> Level.Good
                else -> Level.Info
            },
            text,
        )
    }

    fun info(text: String) = append(Level.Info, text)
    fun good(text: String) = append(Level.Good, text)
    fun warn(text: String) = append(Level.Warn, text)
    fun error(text: String) = append(Level.Error, text)

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    @Synchronized
    private fun append(level: Level, text: String) {
        Log.println(
            when (level) {
                Level.Info, Level.Good -> Log.INFO
                Level.Warn -> Log.WARN
                Level.Error -> Log.ERROR
            },
            TAG,
            text,
        )
        val current = _lines.value
        val kept = if (current.size >= CAPACITY) current.subList(current.size - CAPACITY + 1, current.size) else current
        _lines.value = kept + Line(nextId++, System.currentTimeMillis(), level, text)
    }
}
