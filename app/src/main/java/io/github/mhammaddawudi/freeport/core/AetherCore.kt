package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.AetherSettings

/**
 * The seam between the app and Aether's Rust core.
 *
 * The core exposes a C ABI (`aether/src/ffi.rs`) where every call returns a
 * JSON string and long-running work is a *job*: the start call returns a job
 * id immediately, and the caller polls [pollJob] until it reports `done`.
 * This interface is that shape, minus the polling — the implementations turn
 * jobs into suspending calls so the UI layer never sees a poll loop.
 *
 * Two implementations exist:
 *
 *  - [MockAetherCore] — plausible timings and endpoints, no native library.
 *    This is what the app runs on until the Rust side is cross-compiled.
 *  - [NativeAetherCore] — the real thing, over JNI.
 *
 * Swapping one for the other is a single line in [CoreProvider].
 */
interface AetherCore {

    /**
     * Pushes the `AETHER_*` switches into the process the core reads them
     * from. Some options — HTTP/2 for MASQUE, ClientHello fragmenting — have
     * no field in the job payloads and are only read from the environment.
     */
    suspend fun applyEnvironment(settings: AetherSettings)

    /** The core's own version string, or null when the library is missing. */
    suspend fun version(): String?

    /**
     * Loads the device identity from [path], provisioning a new one if the
     * file is not there yet. Corresponds to `aether_identity_open`.
     */
    suspend fun openIdentity(path: String, transport: String): CoreResult<Identity>

    /**
     * Looks for a reachable edge. Corresponds to `aether_scan_start`.
     *
     * [onProgress] is driven by the implementation, not the core: the C API
     * has no progress channel, so the native implementation reports the
     * elapsed phases it can infer and the mock reports simulated ones.
     */
    suspend fun scan(
        identity: Identity,
        settings: AetherSettings,
        onProgress: (ScanProgress) -> Unit,
    ): CoreResult<Endpoint>

    /**
     * Opens the tunnel and runs it until [stopTunnel] is called or it fails.
     * Corresponds to `aether_tunnel_start`; the returned job id stays valid
     * for the life of the tunnel.
     */
    suspend fun startTunnel(
        identity: Identity,
        endpoint: Endpoint,
        settings: AetherSettings,
    ): CoreResult<JobHandle>

    /**
     * Hands the whole connection to the core — identity, scan and tunnel —
     * the way the CLI runs it. Corresponds to `aether_core_start`.
     *
     * The step API above has no way to express gool: `Transport` in `api.rs`
     * knows only masque and wireguard, and the nested session is assembled
     * inside `run_with`. So gool always takes this path.
     */
    suspend fun startManaged(settings: AetherSettings, configDir: String): CoreResult<JobHandle>

    /**
     * Waits until a session is carrying traffic, which is the moment its
     * local SOCKS5 listener starts accepting. Returns null once ready, or the
     * reason it never got there.
     *
     * Every path needs this, not just the managed one: starting tun2socks
     * against a proxy that is not listening yet produces a tunnel that says
     * it is up and carries nothing.
     */
    suspend fun awaitProxyReady(job: JobHandle, settings: AetherSettings, timeoutMs: Long): String?

    /**
     * Waits until nothing is listening on the local proxy port.
     *
     * A session that has not finished letting go of its listener makes the
     * next one look instantly ready, so the app reports a connection that is
     * really the previous one. Returns false if the port never freed up.
     */
    suspend fun awaitProxyClosed(settings: AetherSettings, timeoutMs: Long): Boolean

    /** Cancels a running job. Corresponds to `aether_job_cancel`. */
    suspend fun stopTunnel(job: JobHandle)

    /** True once the tunnel job has finished, either cleanly or with an error. */
    suspend fun pollJob(job: JobHandle): JobState

    /** Frees the identity slot the core holds. Corresponds to `aether_identity_free`. */
    suspend fun closeIdentity(identity: Identity)
}

/** The core's identity slot plus the summary it reported when it opened. */
data class Identity(
    val handle: Long,
    val deviceId: String,
    val ipv4: String,
    val ipv6: String,
    val organization: String,
    val assignedEndpoint: String,
)

/** One reachable edge, as `api::Endpoint` reports it. */
data class Endpoint(
    val ip: String,
    val port: Int,
    val rttMs: Long,
) {
    val socket: String get() = if (ip.contains(':')) "[$ip]:$port" else "$ip:$port"
}

/** An opaque core job id. */
@JvmInline
value class JobHandle(val id: Long)

sealed interface JobState {
    data object Running : JobState
    data class Done(val ok: Boolean, val detail: String) : JobState
}

/** What the scan is doing, so the connect button can say something true. */
data class ScanProgress(
    val phase: Phase,
    val probed: Int = 0,
    val total: Int = 0,
    val bestRttMs: Long? = null,
) {
    enum class Phase { Provisioning, Probing, Verifying, Settled }
}

/** Every core call either worked or came back with the core's own message. */
sealed interface CoreResult<out T> {
    data class Ok<T>(val value: T) : CoreResult<T>
    data class Err(val message: String) : CoreResult<Nothing>

    fun valueOrNull(): T? = when (this) {
        is Ok -> value
        is Err -> null
    }

    fun errorOrNull(): String? = when (this) {
        is Ok -> null
        is Err -> message
    }
}

inline fun <T, R> CoreResult<T>.map(transform: (T) -> R): CoreResult<R> = when (this) {
    is CoreResult.Ok -> CoreResult.Ok(transform(value))
    is CoreResult.Err -> this
}
