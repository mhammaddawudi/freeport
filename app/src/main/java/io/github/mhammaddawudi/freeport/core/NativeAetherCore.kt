package io.github.mhammaddawudi.freeport.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import io.github.mhammaddawudi.freeport.data.AetherSettings
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

/**
 * [AetherCore] over the real `libaether.so`, through the thin C shim in
 * `src/main/cpp/aether_jni.c`.
 *
 * Every native call returns the core's JSON reply as a string. Replies always
 * carry `ok`; on failure they carry `error`, which is passed through verbatim
 * because the core's messages are written for people.
 */
class NativeAetherCore : AetherCore {

    override suspend fun applyEnvironment(settings: AetherSettings) {
        io {
            CorePayloads.environment(settings).forEach { (name, value) -> setEnv(name, value) }
        }
    }

    override suspend fun version(): String? = io {
        if (!loaded) return@io null
        JSONObject(nativeVersion()).optString("version").ifEmpty { null }
    }

    override suspend fun openIdentity(path: String, transport: String): CoreResult<Identity> =
        runJob(nativeIdentityOpen(CorePayloads.open(path, transport))) { result ->
            val summary = result.getJSONObject("summary")
            Identity(
                handle = result.getLong("identity"),
                deviceId = summary.optString("device_id"),
                ipv4 = summary.optString("ipv4"),
                ipv6 = summary.optString("ipv6"),
                organization = summary.optString("organization"),
                assignedEndpoint = summary.optString("assigned_endpoint"),
            )
        }

    override suspend fun scan(
        identity: Identity,
        settings: AetherSettings,
        onProgress: (ScanProgress) -> Unit,
    ): CoreResult<Endpoint> {
        // A pinned peer skips the scan entirely, the same way `--peer` does.
        CorePayloads.parsePeer(settings.pinnedPeer)?.let { (host, port) ->
            onProgress(ScanProgress(ScanProgress.Phase.Settled, 1, 1))
            return CoreResult.Ok(Endpoint(host, port, rttMs = 0))
        }

        onProgress(ScanProgress(ScanProgress.Phase.Probing))
        val result = runJob(
            nativeScanStart(identity.handle, CorePayloads.scan(settings)),
        ) { reply ->
            val endpoint = reply.getJSONObject("endpoint")
            Endpoint(
                ip = endpoint.getString("ip"),
                port = endpoint.getInt("port"),
                rttMs = endpoint.optLong("rtt_ms"),
            )
        }
        if (result is CoreResult.Ok) {
            onProgress(ScanProgress(ScanProgress.Phase.Settled, bestRttMs = result.value.rttMs))
        }
        return result
    }

    override suspend fun startTunnel(
        identity: Identity,
        endpoint: Endpoint,
        settings: AetherSettings,
    ): CoreResult<JobHandle> = io {
        val reply = JSONObject(
            nativeTunnelStart(identity.handle, CorePayloads.tunnel(endpoint, settings)),
        )
        if (!reply.optBoolean("ok")) {
            CoreResult.Err(reply.optString("error", "the core refused to start the tunnel"))
        } else {
            CoreResult.Ok(JobHandle(reply.getLong("job")))
        }
    }

    override suspend fun startManaged(
        settings: AetherSettings,
        configDir: String,
    ): CoreResult<JobHandle> = io {
        val arguments = JSONArray(CorePayloads.coreArguments(settings, configDir)).toString()
        val reply = JSONObject(nativeCoreStart(arguments))
        if (!reply.optBoolean("ok")) {
            CoreResult.Err(reply.optString("error", "the core refused to start"))
        } else {
            CoreResult.Ok(JobHandle(reply.getLong("job")))
        }
    }

    override suspend fun awaitProxyReady(
        job: JobHandle,
        settings: AetherSettings,
        timeoutMs: Long,
    ): String? = io {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = pollJob(job)
            if (state is JobState.Done) {
                return@io state.detail.ifEmpty { "the core exited before the tunnel came up" }
            }
            val listening = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", settings.socksPort), 250) }
            }.isSuccess
            if (listening) return@io null
            // Short: every poll here is time the person spends watching the
            // button, and a refused loopback connect costs next to nothing.
            delay(150)
        }
        "the core did not open its proxy within ${timeoutMs / 1000}s"
    }

    override suspend fun awaitProxyClosed(settings: AetherSettings, timeoutMs: Long): Boolean = io {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", settings.socksPort), 200) }
            }.isSuccess
            if (!open) return@io true
            delay(200)
        }
        false
    }

    override suspend fun stopTunnel(job: JobHandle) {
        io {
            nativeJobCancel(job.id)
            nativeJobFree(job.id)
        }
    }

    override suspend fun pollJob(job: JobHandle): JobState = io {
        val reply = JSONObject(nativeJobPoll(job.id))
        when {
            !reply.optBoolean("ok") -> JobState.Done(false, reply.optString("error"))
            reply.optString("state") == "running" -> JobState.Running
            else -> {
                val result = reply.optJSONObject("result") ?: JSONObject()
                JobState.Done(
                    ok = result.optBoolean("ok"),
                    detail = result.optString("error").ifEmpty { result.optString("state") },
                )
            }
        }
    }

    override suspend fun closeIdentity(identity: Identity) {
        io { nativeIdentityFree(identity.handle) }
    }

    /**
     * Takes the reply of a `*_start` call, polls the job it names until it is
     * done, frees it, and maps the result.
     *
     * The poll interval backs off from 40ms to 250ms: identity and tunnel
     * setup usually finish in well under a second, while a thorough scan can
     * run for a minute and gains nothing from being asked more often.
     */
    private suspend fun <T> runJob(
        startReply: String,
        transform: (JSONObject) -> T,
    ): CoreResult<T> = io {
        val started = JSONObject(startReply)
        if (!started.optBoolean("ok")) {
            return@io CoreResult.Err(started.optString("error", "the core refused the request"))
        }
        val id = started.getLong("job")
        var interval = 40L
        try {
            while (coroutineContext.isActive) {
                val polled = JSONObject(nativeJobPoll(id))
                if (!polled.optBoolean("ok")) {
                    return@io CoreResult.Err(polled.optString("error"))
                }
                if (polled.optString("state") == "done") {
                    val result = polled.getJSONObject("result")
                    return@io if (result.optBoolean("ok")) {
                        CoreResult.Ok(transform(result))
                    } else {
                        CoreResult.Err(result.optString("error", "the core reported a failure"))
                    }
                }
                delay(interval)
                interval = (interval * 3 / 2).coerceAtMost(250)
            }
            CoreResult.Err("cancelled")
        } finally {
            // Freeing also cancels, so a coroutine cancelled mid-scan stops
            // the scan in the core rather than leaving it probing.
            nativeJobFree(id)
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    // --- JNI -------------------------------------------------------------
    // Each returns the JSON the C function returned, already copied into a
    // Java string and with the native buffer released by the shim.

    private external fun nativeVersion(): String
    private external fun nativeIdentityOpen(payload: String): String
    private external fun nativeIdentityFree(identity: Long): String
    private external fun nativeScanStart(identity: Long, payload: String): String
    private external fun nativeTunnelStart(identity: Long, payload: String): String
    private external fun nativeJobPoll(job: Long): String
    private external fun nativeJobCancel(job: Long): String
    private external fun nativeJobFree(job: Long): String
    external fun nativeCoreStart(argumentsJson: String): String
    private external fun nativeCaptureLogs(): String
    private external fun nativeSetEnv(name: String, value: String): String

    /** Makes the core talk at the level we want, and sends it to the log. */
    fun prepareLogging(level: String = "info") {
        runCatching {
            nativeSetEnv("AETHER_LOG_LEVEL", level)
            nativeSetEnv("RUST_LOG", "info,aether=$level")
            nativeCaptureLogs()
        }
    }

    /** Sets one of the core's `AETHER_*` switches for this process. */
    fun setEnv(name: String, value: String) {
        runCatching { nativeSetEnv(name, value) }
    }

    companion object {
        /**
         * Whether both `libaether.so` and the JNI shim loaded.
         *
         * A failure here silently demotes the whole app to the mock core, so
         * the reason goes into the log rather than being swallowed: on a
         * device it is nearly always a missing ABI or a page-size mismatch,
         * and the linker's own message says which.
         */
        val loaded: Boolean by lazy {
            runCatching {
                System.loadLibrary("aether")
                System.loadLibrary("aether_jni")
            }.onFailure { failure ->
                CoreLog.error("[!] the core could not be loaded: ${failure.message}")
            }.isSuccess
        }
    }
}
