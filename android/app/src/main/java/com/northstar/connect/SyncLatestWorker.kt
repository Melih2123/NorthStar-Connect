package com.northstar.connect

import android.content.Context
import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class SyncLatestWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val server = SyncConfig.serverUrl(applicationContext)
        val room = SyncConfig.roomCode(applicationContext)
        val prefs = SyncConfig.prefs(applicationContext)

        // Account-level friend requests and peer invites are checked even when
        // no shared room has been selected on this installation.
        runCatching { pollSocialFallback(server, prefs) }
            .onFailure { android.util.Log.w("NorthStarSync", "Social inbox polling failed.", it) }

        if (room.isBlank()) {
            prefs.edit().putString("widget_sync_status", "Hesap bildirimleri kontrol edildi · Henüz oda seçilmedi.").apply()
            runCatching { NorthStarWidget().updateAll(applicationContext) }
            return Result.success()
        }

        prefs.edit().putString("widget_sync_status", "Tuval sunucudan alınıyor…").apply()
        runCatching { NorthStarWidget().updateAll(applicationContext) }

        return try {
            // Notification polling is isolated: a failure here must not block canvas sync.
            runCatching { pollFallbackNotifications(server, room) }
                .onFailure { android.util.Log.w("NorthStarSync", "Notification poll failed; continuing canvas sync.", it) }

            val url = "${server.trimEnd('/')}/api/rooms/${room.uppercase()}/canvas"
            val request = Request.Builder().url(url).get().build()
            val response = http.newCall(request).execute()
            val body = response.use {
                if (!it.isSuccessful) error("Canvas HTTP ${it.code}")
                it.body?.string() ?: error("Empty canvas response")
            }

            val json = JSONObject(body)
            val events = json.optJSONArray("events") ?: org.json.JSONArray()
            val lastId = json.optLong("last_event_id", 0L)
            val previous = prefs.getLong("widget_event_id", -1L)
            val savedPath = prefs.getString("latest_image_path", null).orEmpty()
            val savedImageExists = savedPath.isNotBlank() && File(savedPath).isFile

            if (lastId != previous || !savedImageExists) {
                val bitmap = renderBoard(events, 480, 260, server, room)
                val output = File(applicationContext.filesDir, "northstar_shared_canvas.png")
                FileOutputStream(output).use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        error("Could not write widget preview image.")
                    }
                    stream.flush()
                }
                bitmap.recycle()

                prefs.edit()
                    .putLong("widget_event_id", lastId)
                    .putLong("last_drawing_id", lastId)
                    .putString("latest_image_path", output.absolutePath)
                    .putString("latest_image_time", System.currentTimeMillis().toString())
                    .apply()
            }

            val eventCount = events.length()
            val status = if (eventCount == 0) {
                "Oda bağlı · Tuval henüz boş."
            } else {
                "Güncel · $eventCount tuval olayı"
            }
            prefs.edit()
                .putString("widget_sync_status", status)
                .putLong("widget_last_sync_at", System.currentTimeMillis())
                .remove("widget_sync_error")
                .apply()

            // Always refresh after a successful fetch, even if the event ID is unchanged.
            NorthStarWidget().updateAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            val detail = (e.message ?: e.javaClass.simpleName).take(72)
            prefs.edit()
                .putString("widget_sync_status", "Senkronizasyon hatası: $detail")
                .putString("widget_sync_error", detail)
                .apply()
            runCatching { NorthStarWidget().updateAll(applicationContext) }
            android.util.Log.e("NorthStarSync", "Widget canvas sync failed.", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /** Firebase-free fallback for account-level friend requests and peer invitations. */
    private fun pollSocialFallback(server: String, prefs: android.content.SharedPreferences) {
        val token = IdentityStore.accessToken(applicationContext) ?: return
        // FCM is considered available only if this account/device has a token
        // registered AND the server has valid Firebase Admin credentials.
        if (backendAccountFcmEnabled(server, token, SyncConfig.deviceId(applicationContext))) return
        val requests = ApiClient.getFriendRequests(server, token).optJSONArray("incoming") ?: org.json.JSONArray()
        val invitations = ApiClient.getInvitations(server, token).optJSONArray("invitations") ?: org.json.JSONArray()
        val currentRequests = mutableSetOf<String>()
        val currentInvitations = mutableSetOf<String>()
        for (i in 0 until requests.length()) {
            val item = requests.optJSONObject(i) ?: continue
            currentRequests.add(item.optLong("request_id").toString())
        }
        for (i in 0 until invitations.length()) {
            val item = invitations.optJSONObject(i) ?: continue
            currentInvitations.add(item.optString("invitation_id"))
        }
        val baseline = prefs.getBoolean("social_inbox_initialized", false)
        val oldRequests = prefs.getStringSet("social_friend_request_ids", emptySet())?.toSet() ?: emptySet()
        val oldInvitations = prefs.getStringSet("social_peer_invitation_ids", emptySet())?.toSet() ?: emptySet()
        prefs.edit()
            .putBoolean("social_inbox_initialized", true)
            .putStringSet("social_friend_request_ids", currentRequests)
            .putStringSet("social_peer_invitation_ids", currentInvitations)
            .apply()
        if (baseline && currentRequests.any { it !in oldRequests }) {
            showLocalNotification(6301, "Yeni arkadaşlık isteğin var", "NorthStar Connect arkadaşlık isteklerini kontrol et.")
        }
        if (baseline && currentInvitations.any { it !in oldInvitations }) {
            showLocalNotification(6302, "Yeni bağlantı daveti", "Bir arkadaşın doğrudan bağlantı daveti gönderdi.")
        }
    }

    /**
     * Firebase-free fallback: poll the user's Tailscale Funnel for remote room
     * changes and raise local Android notifications. WorkManager runs no more
     * frequently than Android's 15-minute periodic-work minimum.
     */
    private fun pollFallbackNotifications(server: String, room: String) {
        // Check this room/device registration plus server credentials.
        if (backendRoomFcmEnabled(server, room, SyncConfig.deviceId(applicationContext))) return

        val prefs = SyncConfig.prefs(applicationContext)
        val deviceId = SyncConfig.deviceId(applicationContext)
        val afterEvent = prefs.getLong("fallback_event_id", 0L)
        val afterMessage = prefs.getLong("fallback_message_id", 0L)
        val initializedFor = prefs.getString("fallback_initialized_room", "") ?: ""
        val url = ApiClient.updatesUrl(server, room, afterEvent, afterMessage)
        val request = Request.Builder().url(url).get().build()
        val response = http.newCall(request).execute()
        val body = response.use {
            if (!it.isSuccessful) error("Notification fallback HTTP ${it.code}")
            it.body?.string() ?: error("Empty updates response")
        }
        val json = JSONObject(body)
        val events = json.optJSONArray("events") ?: org.json.JSONArray()
        val messages = json.optJSONArray("messages") ?: org.json.JSONArray()
        val latestEventId = json.optLong("latest_event_id", afterEvent)
        val latestMessageId = json.optLong("latest_message_id", afterMessage)

        if (initializedFor != room) {
            // First run after joining a room establishes a baseline; old content
            // should be restored silently rather than announced as brand new.
            prefs.edit()
                .putString("fallback_initialized_room", room)
                .putLong("fallback_event_id", latestEventId)
                .putLong("fallback_message_id", latestMessageId)
                .apply()
            return
        }

        var newRemoteBoardActivity = false
        var newRemoteMessage = false
        var nextEventId = afterEvent
        var nextMessageId = afterMessage
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            nextEventId = maxOf(nextEventId, event.optLong("event_id", 0L))
            val remote = event.optString("device_id") != deviceId
            val type = event.optString("type")
            if (remote && type in setOf("stroke_end", "clear", "image_add", "image_update", "image_delete")) {
                newRemoteBoardActivity = true
            }
        }
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            nextMessageId = maxOf(nextMessageId, message.optLong("id", 0L))
            if (message.optString("device_id") != deviceId) newRemoteMessage = true
        }

        prefs.edit()
            .putLong("fallback_event_id", nextEventId)
            .putLong("fallback_message_id", nextMessageId)
            .apply()

        if (newRemoteBoardActivity) showLocalNotification(
            5201, "Yeni resim eklendi", "Ortak tuval güncellendi."
        )
        if (newRemoteMessage) showLocalNotification(
            5202, "Yeni oda mesajı var", "NorthStar Connect odanda yeni bir mesaj var."
        )
    }

    private fun backendAccountFcmEnabled(server: String, token: String, deviceId: String): Boolean = runCatching {
        ApiClient.accountNotificationTransport(server, token, deviceId).optBoolean("fcm_enabled", false)
    }.getOrDefault(false)

    private fun backendRoomFcmEnabled(server: String, room: String, deviceId: String): Boolean = runCatching {
        ApiClient.roomNotificationTransport(server, room, deviceId).optBoolean("fcm_enabled", false)
    }.getOrDefault(false)

    private fun showLocalNotification(id: Int, title: String, text: String) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val channelId = "northstar_shared_canvas"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "NorthStar çizim güncellemeleri",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Ortak tuval ve oda mesajı güncellemeleri." }
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private data class LocalStroke(
        val id: String,
        val color: Int,
        val width: Float,
        val tool: String,
        val points: MutableList<PointF> = mutableListOf()
    )

    private data class LocalImage(
        val id: Long,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    private data class SceneEntry(val strokeId: String? = null, val imageId: Long? = null)

    private fun renderBoard(
        events: org.json.JSONArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        server: String,
        room: String
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val strokes = LinkedHashMap<String, LocalStroke>()
        val deletedStrokes = LinkedHashMap<String, LocalStroke>()
        val images = LinkedHashMap<Long, LocalImage>()
        val scene = mutableListOf<SceneEntry>()

        // Replay the same ordered scene operations as the web and Android editors.
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val type = event.optString("type")
            val id = event.optString("stroke_id")
            val payload = event.optJSONObject("payload") ?: JSONObject()
            when (type) {
                "clear" -> {
                    strokes.clear()
                    deletedStrokes.clear()
                    images.clear()
                    scene.clear()
                }
                "stroke_begin" -> {
                    val point = payload.optJSONArray("point") ?: continue
                    if (point.length() < 2) continue
                    val color = runCatching {
                        Color.parseColor(payload.optString("color", "#122632"))
                    }.getOrDefault(Color.BLACK)
                    val stroke = LocalStroke(
                        id,
                        color,
                        payload.optDouble("width", 0.006).toFloat(),
                        payload.optString("tool", "pen")
                    )
                    stroke.points.add(PointF(point.optDouble(0).toFloat(), point.optDouble(1).toFloat()))
                    strokes[id] = stroke
                    scene.add(SceneEntry(strokeId = id))
                    deletedStrokes.clear()
                }
                "stroke_points" -> {
                    val stroke = strokes[id] ?: continue
                    val points = payload.optJSONArray("points") ?: continue
                    for (j in 0 until points.length()) {
                        val p = points.optJSONArray(j) ?: continue
                        if (p.length() >= 2) {
                            stroke.points.add(PointF(p.optDouble(0).toFloat(), p.optDouble(1).toFloat()))
                        }
                    }
                }
                "stroke_delete" -> {
                    val target = payload.optString("target_stroke_id")
                    val removed = strokes.remove(target)
                    if (removed != null) {
                        deletedStrokes[target] = removed
                        scene.removeAll { it.strokeId == target }
                    }
                }
                "stroke_restore" -> {
                    val target = payload.optString("target_stroke_id")
                    val restored = deletedStrokes.remove(target)
                    if (restored != null) {
                        strokes[target] = restored
                        scene.add(SceneEntry(strokeId = target))
                    }
                }
                "image_add" -> {
                    val imageId = payload.optLong("image_id", 0L)
                    if (imageId > 0L) {
                        images[imageId] = LocalImage(
                            imageId,
                            payload.optDouble("x", 0.2).toFloat(),
                            payload.optDouble("y", 0.2).toFloat(),
                            payload.optDouble("width", 0.35).toFloat(),
                            payload.optDouble("height", 0.35).toFloat()
                        )
                        scene.add(SceneEntry(imageId = imageId))
                    }
                }
            }
        }

        val layer = canvas.saveLayer(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (entry in scene) {
            val strokeId = entry.strokeId
            if (strokeId != null) {
                val stroke = strokes[strokeId] ?: continue
                if (stroke.points.isEmpty()) continue
                val path = Path()
                val first = stroke.points.first()
                path.moveTo(first.x * bitmapWidth, first.y * bitmapHeight)
                if (stroke.points.size == 1) {
                    path.lineTo(first.x * bitmapWidth + 0.1f, first.y * bitmapHeight + 0.1f)
                } else {
                    for (point in stroke.points.drop(1)) {
                        path.lineTo(point.x * bitmapWidth, point.y * bitmapHeight)
                    }
                }
                paint.color = stroke.color
                paint.alpha = if (stroke.tool == "marker") 92 else 255
                paint.strokeWidth = (stroke.width * bitmapWidth).coerceAtLeast(1f)
                paint.xfermode = if (stroke.tool == "eraser") {
                    PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                } else null
                canvas.drawPath(path, paint)
                paint.xfermode = null
                paint.alpha = 255
            } else {
                val imageId = entry.imageId ?: continue
                val image = images[imageId] ?: continue
                val bytes = runCatching { ApiClient.downloadDrawing(server, room, imageId) }.getOrNull() ?: continue
                val decoded = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                ) ?: continue
                val dst = android.graphics.RectF(
                    image.x * bitmapWidth,
                    image.y * bitmapHeight,
                    (image.x + image.width) * bitmapWidth,
                    (image.y + image.height) * bitmapHeight
                )
                canvas.drawBitmap(decoded, null, dst, paint)
                decoded.recycle()
            }
        }

        paint.xfermode = null
        canvas.restoreToCount(layer)
        return bitmap
    }

}
