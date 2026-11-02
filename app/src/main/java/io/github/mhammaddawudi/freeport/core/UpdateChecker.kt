package io.github.mhammaddawudi.freeport.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest

/** A newer release than the one installed, and the apk that fits this phone. */
data class AppUpdate(
    val version: String,
    val title: String,
    val apkName: String,
    val apkUrl: String,
    val apkBytes: Long,
    val pageUrl: String,
)

/**
 * Looks for a newer release on GitHub; the browser fetches the apk.
 *
 * The app is outside its own VPN, so when a session is up the check goes
 * through the tunnel's proxy, and falls back to the bare line otherwise.
 */
class UpdateChecker(
    private val context: Context,
    private val repo: String = REPO,
) {

    val installedVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()

    /** The newer release, null when up to date. Throws when GitHub did not answer. */
    suspend fun check(proxyPort: Int?): AppUpdate? = withContext(Dispatchers.IO) {
        val body = open(URL("https://api.github.com/repos/$repo/releases/latest"), proxyPort).use { connection ->
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != 200) throw IOException("GitHub answered ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        }
        parse(body, installedVersion, Build.SUPPORTED_ABIS.toList())
    }

    private fun open(url: URL, proxyPort: Int?): HttpURLConnection {
        val proxy = if (proxyPort != null && proxyPort > 0) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
        } else {
            Proxy.NO_PROXY
        }
        return (url.openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Freeport/$installedVersion")
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    companion object {
        const val REPO = "mhammaddawudi/freeport"

        /** Reads the latest release; null when it is not newer or has no apk for these ABIs. */
        fun parse(json: String, installed: String, abis: List<String>): AppUpdate? {
            val release = JSONObject(json)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
            val tag = release.optString("tag_name")
            if (!isNewer(tag, installed)) return null
            val assets = release.optJSONArray("assets") ?: return null
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk") }
            val chosen = abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.optString("name").contains(abi) } }
                ?: apks.firstOrNull { it.optString("name").contains("universal") }
                ?: return null
            return AppUpdate(
                version = tag.trimStart('v', 'V'),
                title = release.optString("name").ifBlank { tag },
                apkName = chosen.optString("name"),
                apkUrl = chosen.optString("browser_download_url"),
                apkBytes = chosen.optLong("size"),
                pageUrl = release.optString("html_url"),
            )
        }

        /** Compares dotted versions numerically: 2.0 > 1.10 > 1.9. */
        fun isNewer(candidate: String, installed: String): Boolean {
            fun parts(v: String) = v.trim().trimStart('v', 'V').split('.', '-')
                .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(installed)
            if (a.all { it == 0 }) return false
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
