package io.github.mhammaddawudi.freeport.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

/**
 * Names the network the phone is on, so edges are remembered per operator.
 *
 * An edge that flies on one operator is often throttled or dead on another,
 * and the same phone moves between Wi-Fi and mobile data all day. Mobile data
 * is named by its MCC+MNC (`m43211` for MCI), which needs no permission.
 * Wi-Fi has no such name without location access, so it is one bucket; the
 * shared board tells Wi-Fi networks apart by their ASN on its side.
 */
object NetworkKey {

    fun current(context: Context): String = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        when {
            caps == null -> UNKNOWN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellular(context)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> WIFI
            else -> UNKNOWN
        }
    }.getOrDefault(UNKNOWN)

    private fun cellular(context: Context): String {
        val operator = context.getSystemService(TelephonyManager::class.java)?.networkOperator.orEmpty()
        return if (operator.length in 5..6 && operator.all(Char::isDigit)) "m$operator" else "m"
    }

    const val WIFI = "w"
    const val UNKNOWN = "x"
}
