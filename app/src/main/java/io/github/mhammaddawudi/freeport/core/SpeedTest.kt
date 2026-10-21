package io.github.mhammaddawudi.freeport.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.random.Random

/** What a speed test has measured so far. Nulls are phases not reached yet. */
data class SpeedReport(
    val phase: Phase = Phase.Ping,
    val pingMs: Long? = null,
    val jitterMs: Long? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    /** How far the running phase is, 0..1. */
    val progress: Float = 0f,
    /** True when the test ran through the tunnel rather than the bare line. */
    val viaTunnel: Boolean = false,
) {
    enum class Phase { Ping, Download, Upload, Done }
}

/**
 * Latency and throughput against Cloudflare's speed test endpoints.
 *
 * The app is excluded from its own VPN — it has to reach the edge directly —
 * so a request from here would measure the bare line. When a session is up
 * the test goes through the core's SOCKS5 proxy instead, which is exactly the
 * path every tunnelled app takes. The hostname is resolved by the proxy, so
 * the local resolver plays no part in it.
 */
class SpeedTester(
    private val baseUrl: String = BASE_URL,
    private val phaseMs: Long = PHASE_MS,
) {

    /** Measures ping only: cheap enough to run on every new connection. */
    suspend fun ping(proxyPort: Int?): SpeedReport = withContext(Dispatchers.IO) {
        val (ping, jitter) = measurePing(proxy(proxyPort)) { }
        SpeedReport(SpeedReport.Phase.Done, ping, jitter, viaTunnel = proxyPort != null)
    }

    /** Runs ping, download and upload in turn, reporting as it goes. */
    suspend fun run(proxyPort: Int?, onUpdate: (SpeedReport) -> Unit): SpeedReport =
        withContext(Dispatchers.IO) {
            val proxy = proxy(proxyPort)
            var report = SpeedReport(viaTunnel = proxyPort != null)
            onUpdate(report)

            val (ping, jitter) = measurePing(proxy) { progress ->
                onUpdate(report.copy(progress = progress))
            }
            report = report.copy(phase = SpeedReport.Phase.Download, pingMs = ping, jitterMs = jitter, progress = 0f)
            onUpdate(report)

            val down = measureDownload(proxy) { mbps, progress ->
                onUpdate(report.copy(downMbps = mbps, progress = progress))
            }
            report = report.copy(phase = SpeedReport.Phase.Upload, downMbps = down, progress = 0f)
            onUpdate(report)

            val up = measureUpload(proxy) { mbps, progress ->
                onUpdate(report.copy(upMbps = mbps, progress = progress))
            }
            report = report.copy(phase = SpeedReport.Phase.Done, upMbps = up, progress = 1f)
            onUpdate(report)
            report
        }

    private fun proxy(port: Int?): Proxy =
        if (port == null || port <= 0) {
            Proxy.NO_PROXY
        } else {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        }

    /**
     * Times small requests on one kept-alive connection. The first one pays
     * for TCP and TLS and is left out; the median of the rest is the ping,
     * and how much they wander from one another is the jitter.
     */
    private suspend fun measurePing(proxy: Proxy, onProgress: (Float) -> Unit): Pair<Long, Long> {
        val samples = mutableListOf<Long>()
        for (i in 0..PING_SAMPLES) {
            currentCoroutineContext().ensureActive()
            val started = SystemClock.elapsedRealtime()
            request("$baseUrl/__down?bytes=0", proxy).useConnection { connection ->
                connection.inputStream.use { it.readBytes() }
            }
            if (i > 0) samples += SystemClock.elapsedRealtime() - started
            onProgress((i + 1f) / (PING_SAMPLES + 1))
        }
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val jitter = samples.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toLong()
        return median to jitter
    }

    private suspend fun measureDownload(proxy: Proxy, onSample: (Double, Float) -> Unit): Double {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        var started = 0L
        var lastReport = 0L
        request("$baseUrl/__down?bytes=$DOWNLOAD_BYTES", proxy).useConnection { connection ->
            connection.inputStream.use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    val now = SystemClock.elapsedRealtime()
                    // The clock starts at the first byte, so the request's own
                    // round trip is not counted as slowness.
                    if (started == 0L) started = now
                    total += read
                    val elapsed = now - started
                    if (now - lastReport >= REPORT_EVERY_MS) {
                        lastReport = now
                        onSample(mbps(total, elapsed), (elapsed.toFloat() / phaseMs).coerceIn(0f, 1f))
                    }
                    if (elapsed >= phaseMs) break
                }
            }
        }
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
        return mbps(total, elapsed)
    }

    private suspend fun measureUpload(proxy: Proxy, onSample: (Double, Float) -> Unit): Double {
        val chunk = Random.nextBytes(BUFFER)
        var total = 0L
        val started = SystemClock.elapsedRealtime()
        var lastReport = 0L
        val connection = request("$baseUrl/__up", proxy).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(UPLOAD_BYTES)
        }
        try {
            connection.outputStream.use { output ->
                while (total < UPLOAD_BYTES) {
                    currentCoroutineContext().ensureActive()
                    val size = minOf(chunk.size.toLong(), UPLOAD_BYTES - total).toInt()
                    output.write(chunk, 0, size)
                    total += size
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - started
                    if (now - lastReport >= REPORT_EVERY_MS) {
                        lastReport = now
                        onSample(mbps(total, elapsed), (elapsed.toFloat() / phaseMs).coerceIn(0f, 1f))
                    }
                    if (elapsed >= phaseMs) break
                }
                output.flush()
            }
            // Ending the body early leaves the request incomplete on purpose;
            // whatever the server says about it does not matter.
            runCatching { connection.responseCode }
        } catch (stoppedEarly: IOException) {
            if (total == 0L) throw stoppedEarly
        } finally {
            connection.disconnect()
        }
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
        return mbps(total, elapsed)
    }

    private fun request(url: String, proxy: Proxy): HttpURLConnection =
        (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Cache-Control", "no-store")
        }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T {
        try {
            val code = responseCode
            if (code !in 200..299) throw IOException("the test server answered $code")
            return block(this)
        } finally {
            // Not disconnect(): that would close the kept-alive socket the
            // next ping sample is meant to reuse.
            runCatching { errorStream?.close() }
        }
    }

    companion object {
        const val BASE_URL = "https://speed.cloudflare.com"
        const val PHASE_MS = 8_000L
        private const val PING_SAMPLES = 5
        private const val DOWNLOAD_BYTES = 200_000_000L
        private const val UPLOAD_BYTES = 50_000_000L
        private const val BUFFER = 64 * 1024
        private const val TIMEOUT_MS = 10_000
        private const val REPORT_EVERY_MS = 200L

        /** Megabits per second, the unit people compare plans in. */
        fun mbps(bytes: Long, millis: Long): Double =
            if (millis <= 0) 0.0 else bytes * 8.0 / millis / 1_000.0
    }
}
