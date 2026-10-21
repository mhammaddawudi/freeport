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
import io.github.mhammaddawudi.freeport.core.ScanProgress

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
            Intent(context, AetherVpnService::class.java).setAction(AetherVpnService.ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aether)
            .setColor(0xFFFCBF11.toInt())
            .setContentTitle(context.getString(titleOf(state)))
            .setContentText(detailOf(context, state))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.action_disconnect), disconnect)

        if (state is ConnectionState.Connected) {
            builder.setWhen(state.sinceMillis).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    private fun titleOf(state: ConnectionState): Int = when (state) {
        ConnectionState.Idle, ConnectionState.Disconnecting -> R.string.state_idle
        is ConnectionState.Preparing -> R.string.state_preparing
        is ConnectionState.Scanning -> R.string.state_scanning
        is ConnectionState.Connecting -> R.string.state_connecting
        is ConnectionState.Connected -> R.string.state_connected
        is ConnectionState.Reconnecting -> R.string.state_reconnecting
        is ConnectionState.Failed -> R.string.state_failed
    }

    private fun detailOf(context: Context, state: ConnectionState): String = when (state) {
        ConnectionState.Idle, ConnectionState.Disconnecting -> context.getString(R.string.app_name)
        is ConnectionState.Preparing -> state.protocol.displayName
        is ConnectionState.Scanning -> {
            val p = state.progress
            if (p.phase == ScanProgress.Phase.Probing && p.total > 0) {
                "${state.protocol.displayName} · ${p.probed}/${p.total}"
            } else {
                state.protocol.displayName
            }
        }
        is ConnectionState.Connecting -> state.endpoint?.socket ?: state.protocol.displayName
        is ConnectionState.Connected -> {
            val carrier = if (state.viaAuto) context.getString(R.string.auto_via, state.protocol.displayName) else state.protocol.displayName
            state.endpoint?.let { "$carrier · ${it.socket}" } ?: carrier
        }
        is ConnectionState.Reconnecting -> "${state.protocol.displayName} · ${state.reason}"
        is ConnectionState.Failed -> state.message
    }
}
