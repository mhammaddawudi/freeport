package io.github.mhammaddawudi.freeport.core

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches an update inside the app and leaves it in Downloads.
 *
 * The app never asks to install anything itself: an app that fetches apks
 * and holds REQUEST_INSTALL_PACKAGES is what Play Protect flags. So the apk
 * is downloaded here (through the tunnel when one is up, where GitHub is
 * reachable), checked to be signed with this app's own key, and published
 * to the public Downloads folder. Android's Downloads screen then installs
 * it on a tap, as the source that already may.
 */
class UpdateDownloader(private val context: Context) {

    /** Whether this Android can take the apk into Downloads without a storage permission. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Downloads [update], checks it, and returns where it sits in Downloads.
     * [onProgress] gets 0..1. Throws [IOException] with a short reason.
     */
    suspend fun fetch(update: AppUpdate, proxyPort: Int?, onProgress: (Float) -> Unit): Uri =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, safeName(update.apkName))
            try {
                download(URL(update.apkUrl), proxyPort, apk, update.apkBytes, onProgress)
                if (update.apkBytes > 0 && apk.length() != update.apkBytes) {
                    throw IOException("the file arrived incomplete")
                }
                if (!signedLikeThisApp(apk)) throw IOException("the file is not signed by Freeport")
                publish(apk, safeName(update.apkName))
            } finally {
                apk.delete()
            }
        }

    private suspend fun download(url: URL, proxyPort: Int?, into: File, expected: Long, onProgress: (Float) -> Unit) {
        val connection = open(url, proxyPort)
        try {
            if (connection.responseCode != 200) throw IOException("${url.host} answered ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expected
            var done = 0L
            var shown = -1
            connection.inputStream.use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL, proxyPort: Int?): HttpURLConnection {
        val proxy = if (proxyPort != null && proxyPort > 0) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
        } else {
            Proxy.NO_PROXY
        }
        return (url.openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Freeport")
        }
    }

    /** True when [apk] carries the same signing certificate as the installed app. */
    private fun signedLikeThisApp(apk: File): Boolean {
        val installed = certificates(
            runCatching { context.packageManager.getPackageInfo(context.packageName, signingFlag()) }.getOrNull(),
        )
        val downloaded = certificates(context.packageManager.getPackageArchiveInfo(apk.path, signingFlag()))
        return installed.isNotEmpty() && installed == downloaded
    }

    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo?): Set<String> {
        info ?: return emptySet()
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Copies [apk] into the public Downloads folder, replacing an older copy of the same name. */
    private fun publish(apk: File, name: String): Uri {
        if (!supported) throw IOException("this Android needs the download in the browser")
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(collection, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Downloads did not take the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Downloads did not take the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"

        /** Keeps a release asset's name usable as a file name. */
        fun safeName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "freeport.apk" }
                .let { if (it.endsWith(".apk")) it else "$it.apk" }
    }
}
