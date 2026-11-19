package io.github.mhammaddawudi.freeport.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.mhammaddawudi.freeport.MainActivity
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.ConnectionState
import java.util.Locale

object VpnNotifications {

    const val CHANNEL_ID = "tunnel"
    const val NOTIFICATION_ID = 1819

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, state: ConnectionState): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            context,
            1,
            Intent(context, WarpVpnService::class.java).setAction(WarpVpnService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val reshow = PendingIntent.getService(
            context,
            2,
            Intent(context, WarpVpnService::class.java).setAction(WarpVpnService.ACTION_RESHOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_warp)
            .setColor(BRAND)
            .setContentTitle(context.getString(titleOf(state)))
            .setContentText(detailOf(context, state))
            .setContentIntent(open)
            .setOngoing(true)
            // Android 14+ lets the person swipe even an ongoing notification
            // away; while connected the service shows it again at once.
            .setDeleteIntent(reshow)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stat_warp, context.getString(R.string.action_disconnect), disconnect)

        if (state is ConnectionState.Connected) {
            builder.setWhen(state.sinceMillis).setShowWhen(true).setUsesChronometer(true)
        } else {
            // Still on its way: a moving bar says so at a glance.
            builder.setShowWhen(false).setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** Whether [state] is a session worth a notification at all. */
    fun shows(state: ConnectionState): Boolean = when (state) {
        ConnectionState.Idle, ConnectionState.Disconnecting, is ConnectionState.Failed -> false
        else -> true
    }

    private fun titleOf(state: ConnectionState): Int = when (state) {
        ConnectionState.Idle, ConnectionState.Disconnecting -> R.string.state_idle
        is ConnectionState.Preparing -> R.string.state_preparing
        is ConnectionState.Scanning -> R.string.state_scanning
        is ConnectionState.Connecting -> if (state.relaying) R.string.state_relaying else R.string.state_connecting
        is ConnectionState.Connected -> R.string.state_connected
        is ConnectionState.Reconnecting -> R.string.state_reconnecting
        is ConnectionState.Failed -> R.string.state_failed
    }

    /**
     * The protocol, and once connected where traffic leaves. Never an edge's
     * address or a raw reason: a notification shows on the lock screen and in
     * screenshots, and an IP there says nothing to the person anyway.
     */
    private fun detailOf(context: Context, state: ConnectionState): String = when (state) {
        ConnectionState.Idle, ConnectionState.Disconnecting, is ConnectionState.Failed -> context.getString(R.string.app_name)
        is ConnectionState.Preparing -> state.protocol.displayName
        is ConnectionState.Scanning -> state.protocol.displayName
        is ConnectionState.Connecting ->
            if (state.viaAuto) context.getString(R.string.auto_via, state.protocol.displayName) else state.protocol.displayName
        is ConnectionState.Connected -> {
            val carrier = if (state.viaAuto) context.getString(R.string.auto_via, state.protocol.displayName) else state.protocol.displayName
            countryName(context, state.exitCountry)?.let { "$carrier · $it" } ?: carrier
        }
        is ConnectionState.Reconnecting -> state.protocol.displayName
    }

    /** In the app's own language, which may differ from the phone's. */
    private fun countryName(context: Context, code: String?): String? {
        if (code.isNullOrBlank()) return null
        val language = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return Locale("", code).getDisplayCountry(language).ifBlank { code }
    }

    private const val BRAND = 0xFFFCBF11.toInt()
}
