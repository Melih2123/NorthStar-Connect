package com.northstar.connect

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.Executors

class NorthStarMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val server = SyncConfig.serverUrl(this)
        val room = SyncConfig.roomCode(this)
        val accountToken = IdentityStore.accessToken(this)
        if (server.isBlank() || (room.isBlank() && accountToken.isNullOrBlank())) return
        Executors.newSingleThreadExecutor().execute {
            runCatching {
                if (room.isNotBlank()) ApiClient.registerFcm(server, room, SyncConfig.deviceId(this), token)
                if (!accountToken.isNullOrBlank()) ApiClient.registerAccountDevice(server, accountToken, SyncConfig.deviceId(this), token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val type = message.data["type"] ?: return
        if (type !in setOf("board_updated", "new_drawing", "room_message", "friend_request", "friend_accepted", "peer_invitation")) return

        // Canvas-related pushes refresh the home-screen preview. Social pushes do not.
        if (type in setOf("board_updated", "new_drawing", "room_message")) {
            SyncScheduler.refreshNow(applicationContext)
        }
        createNotificationChannel()
        val notificationText = when (type) {
            "friend_request" -> "Yeni arkadaşlık isteğin var."
            "friend_accepted" -> "Arkadaşlık isteğin kabul edildi."
            "peer_invitation" -> "Bir arkadaşın bağlantı daveti gönderdi."
            "room_message" -> "Yeni oda mesajın var."
            else -> "Yeni resim eklendi"
        }

        val openApp = PendingIntent.getActivity(
            this, 1001, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("NorthStar Connect")
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (notificationsAllowed) {
            NotificationManagerCompat.from(this).notify(
                (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NorthStar çizim güncellemeleri",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Ortak tuval güncellendiğinde bildirim gösterir." }
            )
        }
    }

    companion object { private const val CHANNEL_ID = "northstar_shared_canvas" }
}
