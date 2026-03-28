package org.amnezia.vpn

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat.Builder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Action
import androidx.core.app.NotificationCompat.InboxStyle
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import org.amnezia.vpn.protocol.ProtocolState
import org.amnezia.vpn.protocol.ProtocolState.CONNECTED
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.Status
import org.amnezia.vpn.protocol.StatusStep
import org.amnezia.vpn.protocol.StatusStepState
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.net.TrafficStats.TrafficData

private const val TAG = "ServiceNotification"

private const val OLD_NOTIFICATION_CHANNEL_ID: String = "org.amnezia.vpn.notification"
private const val NOTIFICATION_CHANNEL_ID: String = "org.amnezia.vpn.notifications"
const val NOTIFICATION_ID = 1337

private const val GET_ACTIVITY_REQUEST_CODE = 0
private const val CONNECT_REQUEST_CODE = 1
private const val DISCONNECT_REQUEST_CODE = 2

class ServiceNotification(private val context: Context) {

    private var currentServerName: String? = null
    private var currentProtocol: String? = null
    private var currentStatus: Status = Status.build { setState(DISCONNECTED) }

    private val upDownSymbols = when (Build.BRAND) {
        "Infinix" -> '˅' to '˄'
        else -> '↓' to '↑'
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    private val notificationBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
        .setShowWhen(false)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                GET_ACTIVITY_REQUEST_CODE,
                Intent(context, AmneziaActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

    private val zeroSpeed: String = with(TrafficData.ZERO) {
        formatSpeedString(rxString, txString)
    }

    fun buildNotification(serverName: String?, protocol: String?, status: Status): Notification {
        val speedString = if (status.state == CONNECTED) zeroSpeed else null
        currentServerName = serverName
        currentProtocol = protocol
        currentStatus = status

        Log.v(TAG, "Build notification: $serverName, ${status.state}")

        return notificationBuilder
            .setSmallIcon(R.drawable.ic_amnezia_round)
            .setLargeIcon(ContextCompat.getDrawable(context, R.drawable.ic_amnezia_round)?.toBitmap())
            .setContentTitle((serverName ?: "AmneziaVPN") + (protocol?.let { " $it" } ?: ""))
            .setContentText(status.message.ifBlank { context.getString(status.state) })
            .setSubText(speedString)
            .setWhen(System.currentTimeMillis())
            .setColor(0xFFFFD54F.toInt())
            .setColorized(status.state == CONNECTED)
            .clearActions()
            .apply {
                buildStatusStyle(status)?.let(::setStyle)
                getAction(status.state)?.let {
                    addAction(it)
                }
            }
            .build()
    }

    private fun buildNotification(speed: TrafficData): Notification =
        buildNotification(currentServerName, currentProtocol, currentStatus).let {
            notificationBuilder
                .setWhen(System.currentTimeMillis())
                .setSubText(getSpeedString(speed))
                .apply {
                    buildStatusStyle(currentStatus)?.let(::setStyle)
                }
                .build()
        }

    private fun buildStatusStyle(status: Status): InboxStyle? {
        if (status.steps.isEmpty()) {
            return null
        }

        return InboxStyle().also { style ->
            status.steps.forEach { step ->
                style.addLine(formatStep(step))
            }
        }
    }

    private fun formatStep(step: StatusStep): String {
        val prefix = when (step.state) {
            StatusStepState.SUCCESS -> "[OK]"
            StatusStepState.FAILURE -> "[X]"
            StatusStepState.ACTIVE -> "[...]"
            StatusStepState.PENDING -> "[ ]"
        }
        return "$prefix ${labelForStep(step.key)}"
    }

    private fun labelForStep(stepKey: String): String =
        when (stepKey) {
            "openvpn" -> "OpenVPN"
            "xray" -> "Xray"
            "readmcu" -> "readmcu.com"
            "speedtest" -> "speedtest.net"
            "healthcheck" -> "health check"
            else -> stepKey
        }

    fun isNotificationEnabled(): Boolean {
        if (!context.isNotificationPermissionGranted()) return false
        if (!notificationManager.areNotificationsEnabled()) return false
        return notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)?.let {
            it.importance != NotificationManager.IMPORTANCE_NONE
        } ?: true
    }

    @SuppressLint("MissingPermission")
    fun updateNotification(serverName: String?, protocol: String?, status: Status) {
        if (context.isNotificationPermissionGranted()) {
            Log.v(TAG, "Update notification: $serverName, ${status.state}")
            notificationManager.notify(NOTIFICATION_ID, buildNotification(serverName, protocol, status))
        }
    }

    @SuppressLint("MissingPermission")
    fun updateSpeed(speed: TrafficData) {
        if (context.isNotificationPermissionGranted()) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(speed))
        }
    }

    private fun getSpeedString(traffic: TrafficData) =
        if (traffic == TrafficData.ZERO) zeroSpeed
        else formatSpeedString(traffic.rxString, traffic.txString)

    private fun formatSpeedString(rx: String, tx: String) = with(upDownSymbols) { "$first $rx  $second $tx" }

    private fun getAction(state: ProtocolState): Action? {
        return when (state) {
            CONNECTED -> {
                Action(
                    0, context.getString(R.string.disconnect),
                    PendingIntent.getBroadcast(
                        context,
                        DISCONNECT_REQUEST_CODE,
                        Intent(ACTION_DISCONNECT).apply {
                            setPackage(context.packageName)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }

            DISCONNECTED -> {
                Action(
                    0, context.getString(R.string.connect),
                    PendingIntent.getBroadcast(
                        context,
                        CONNECT_REQUEST_CODE,
                        Intent(ACTION_CONNECT).apply {
                            setPackage(context.packageName)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }

            else -> null
        }
    }

    companion object {
        fun createNotificationChannel(context: Context) {
            with(NotificationManagerCompat.from(context)) {
                deleteNotificationChannel(OLD_NOTIFICATION_CHANNEL_ID)
                createNotificationChannel(
                    Builder(NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                        .setShowBadge(false)
                        .setSound(null, null)
                        .setVibrationEnabled(false)
                        .setLightsEnabled(false)
                        .setName("AmneziaVPN")
                        .setDescription(context.resources.getString(R.string.notificationChannelDescription))
                        .build()
                )
            }
        }
    }
}

fun Context.isNotificationPermissionGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
