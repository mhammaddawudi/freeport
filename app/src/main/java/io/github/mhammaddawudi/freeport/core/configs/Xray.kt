package io.github.mhammaddawudi.freeport.core.configs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Xray-core as a child process (`libxray.so`, an executable Android extracts
 * with the libraries). One process can serve many configs at once, each on a
 * SOCKS port of its own, which is how a whole batch is tested in one go.
 */
class Xray(private val binary: File, private val folder: File) {

    /** A config bound to the local SOCKS port that reaches it. */
    data class Route(val link: ShareLink, val port: Int)

    /** A running Xray; closing it stops the process. */
    class Running internal constructor(private val process: Process) : AutoCloseable {
        val isAlive: Boolean get() = process.isAlive

        override fun close() {
            process.destroy()
            if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) process.destroyForcibly()
        }
    }

    val isAvailable: Boolean get() = binary.isFile

    /**
     * Starts Xray serving every route; null with the reason when it refuses,
     * which is usually one config it does not accept.
     */
    suspend fun start(routes: List<Route>, name: String, udp: Boolean = false): Result<Running> = withContext(Dispatchers.IO) {
        folder.mkdirs()
        val file = File(folder, "$name.json")
        file.writeText(config(routes, udp).toString())
        val process = runCatching {
            ProcessBuilder(binary.absolutePath, "run", "-c", file.absolutePath)
                .directory(folder)
                .redirectErrorStream(true)
                .apply { environment()["XRAY_LOCATION_ASSET"] = folder.absolutePath }
                .start()
        }.getOrElse { return@withContext Result.failure(it) }

        val ready = CompletableDeferred<String?>()
        Thread({
            val last = ArrayDeque<String>()
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (last.size == LAST_LINES) last.removeFirst()
                    last.addLast(line)
                    if (STARTED.containsMatchIn(line)) ready.complete(null)
                }
            }
            ready.complete(last.joinToString(" | ").takeLast(400).ifEmpty { "xray exited" })
        }, "xray-$name").apply { isDaemon = true }.start()

        val failure = withTimeoutOrNull(START_TIMEOUT_MS) { ready.await() } ?: "xray did not start"
        val running = Running(process)
        if (failure == null || (failure == "xray did not start" && process.isAlive)) {
            Result.success(running)
        } else {
            running.close()
            Result.failure(IllegalStateException(failure))
        }
    }

    private fun config(routes: List<Route>, udp: Boolean): JSONObject {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        routes.forEachIndexed { index, route ->
            inbounds.put(
                JSONObject().put("tag", "in$index").put("listen", "127.0.0.1").put("port", route.port).put("protocol", "socks")
                    .put("settings", JSONObject().put("udp", udp)),
            )
            outbounds.put(route.link.outbound("out$index"))
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("in$index")).put("outboundTag", "out$index"))
        }
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", rules))
    }

    companion object {
        private val STARTED = Regex("Xray \\S+ started")
        private const val START_TIMEOUT_MS = 8_000L
        private const val LAST_LINES = 6
    }
}
