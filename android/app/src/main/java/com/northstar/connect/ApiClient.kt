package com.northstar.connect

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun createRoom(serverUrl: String): String {
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/rooms")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val body = execute(request)
        return JSONObject(body).getString("room_code")
    }

    fun checkRoom(serverUrl: String, roomCode: String) {
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/rooms/${roomCode.trim().uppercase()}")
            .get()
            .build()
        execute(request)
    }

    fun registerFcm(serverUrl: String, roomCode: String, deviceId: String, token: String) {
        val json = JSONObject()
            .put("device_id", deviceId)
            .put("fcm_token", token)
            .toString()
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/rooms/${roomCode.trim().uppercase()}/devices")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        execute(request)
    }

    fun uploadDrawing(context: Context, serverUrl: String, roomCode: String, uri: Uri, deviceId: String): Long {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        if (mime !in setOf("image/png", "image/jpeg", "image/webp")) {
            throw IOException("Only PNG, JPEG and WebP are supported.")
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not read selected image.")
        if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) {
            throw IOException("Image must be no larger than 5 MB.")
        }
        val filename = when (mime) {
            "image/png" -> "northstar-image.png"
            "image/webp" -> "northstar-image.webp"
            else -> "northstar-image.jpg"
        }
        val filePart = bytes.toRequestBody(mime.toMediaType())
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, filePart)
            .addFormDataPart("device_id", deviceId)
            .build()
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/rooms/${roomCode.trim().uppercase()}/drawings")
            .post(body)
            .build()
        val response = execute(request)
        return org.json.JSONObject(response).getLong("id")
    }

    fun latestDrawingsUrl(serverUrl: String, roomCode: String, afterId: Long): String =
        "${serverUrl.trimEnd('/')}/api/rooms/${roomCode.trim().uppercase()}/drawings?after_id=$afterId&limit=50"

    fun updatesUrl(serverUrl: String, roomCode: String, afterEventId: Long, afterMessageId: Long): String =
        "${serverUrl.trimEnd('/')}/api/rooms/${roomCode.trim().uppercase()}/updates?after_event_id=$afterEventId&after_message_id=$afterMessageId&limit=100"

    fun downloadDrawing(serverUrl: String, roomCode: String, id: Long): ByteArray {
        val url = "${serverUrl.trimEnd('/')}/api/rooms/${roomCode.trim().uppercase()}/drawings/$id/image"
        val request = Request.Builder().url(url).get().build()
        return executeBytes(request)
    }

    private fun execute(request: Request): String = executeBytes(request).toString(Charsets.UTF_8)

    private fun executeBytes(request: Request): ByteArray {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty()
                throw IOException("HTTP ${response.code}: ${detail.take(240)}")
            }
            return response.body?.bytes() ?: throw IOException("Empty server response.")
        }
    }
}
