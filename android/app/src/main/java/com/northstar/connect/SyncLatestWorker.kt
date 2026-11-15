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
import com.google.firebase.FirebaseApp
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
        if (server.isBlank() || room.isBlank()) return Result.failure()

        return try {
            pollFallbackNotifications(server, room)

            val url = "${server.trimEnd('/')}/api/rooms/${room.uppercase()}/canvas"
            val request = Request.Builder().url(url).get().build()
            val response = http.newCall(request).execute()
            val body = response.use {
                if (!it.isSuccessful) error("Canvas sync HTTP ${it.code}")
                it.body?.string() ?: error("Empty canvas response")
            }
            val json = JSONObject(body)
            val events = json.optJSONArray("events") ?: org.json.JSONArray()
            val lastId = json.optLong("last_event_id", 0L)
            val prefs = SyncConfig.prefs(applicationContext)
            val previous = prefs.getLong("widget_event_id", -1L)

            if (lastId != previous || !File(prefs.getString("latest_image_path", "") ?: "").isFile) {
                val bitmap = renderBoard(events, 480, 260, server, room)
                val output = File(applicationContext.filesDir, "northstar_shared_canvas.png")
                FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                bitmap.recycle()
                prefs.edit()
                    .putLong("widget_event_id", lastId)
                    .putLong("last_drawing_id", lastId)
                    .putString("latest_image_path", output.absolutePath)
                    .putString("latest_image_time", System.currentTimeMillis().toString())
                    .apply()
                NorthStarWidget().updateAll(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /**
     * Firebase-free fallback: poll the user's Tailscale Funnel for remote room
     * changes and raise local Android notifications. WorkManager runs no more
     * frequently than Android's 15-minute periodic-work minimum.
     */
    private fun pollFallbackNotifications(server: String, room: String) {
        // When the Firebase SDK is configured, FCM owns notification delivery.
        // This avoids duplicate local notifications on devices with working FCM.
        if (runCatching { FirebaseApp.getApps(applicationContext).isNotEmpty() }.getOrDefault(false)) return

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
