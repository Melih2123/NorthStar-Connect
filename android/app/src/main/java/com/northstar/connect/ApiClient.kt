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


    fun registerAccount(serverUrl: String, username: String, password: String): JSONObject =
        postJson(serverUrl, "/api/accounts/register", JSONObject().put("username", username).put("password", password))

    fun loginAccount(serverUrl: String, username: String, password: String): JSONObject =
        postJson(serverUrl, "/api/accounts/login", JSONObject().put("username", username).put("password", password))

    fun logoutAccount(serverUrl: String, token: String): JSONObject =
        postAuthorized(serverUrl, "/api/accounts/logout", JSONObject(), token)

    fun sendFriendRequest(serverUrl: String, token: String, username: String): JSONObject =
        postAuthorized(serverUrl, "/api/friends/requests", JSONObject().put("username", username), token)

    fun getFriends(serverUrl: String, token: String): JSONObject = getAuthorized(serverUrl, "/api/friends", token)

    fun getFriendRequests(serverUrl: String, token: String): JSONObject = getAuthorized(serverUrl, "/api/friends/requests", token)

    fun acceptFriendRequest(serverUrl: String, token: String, requestId: Long): JSONObject =
        postAuthorized(serverUrl, "/api/friends/requests/$requestId/accept", JSONObject(), token)

    fun inviteFriend(serverUrl: String, token: String, friendAccountId: String, roomCode: String? = null): JSONObject {
        val body = JSONObject().put("invite_type", if (roomCode.isNullOrBlank()) "direct" else "room")
        if (!roomCode.isNullOrBlank()) body.put("room_code", roomCode)
        return postAuthorized(serverUrl, "/api/friends/$friendAccountId/invite", body, token)
    }

    fun getInvitations(serverUrl: String, token: String): JSONObject = getAuthorized(serverUrl, "/api/invitations", token)

    fun acceptInvitation(serverUrl: String, token: String, invitationId: String): JSONObject =
        postAuthorized(serverUrl, "/api/invitations/$invitationId/accept", JSONObject(), token)

    fun registerAccountDevice(serverUrl: String, token: String, deviceId: String, fcmToken: String?): JSONObject {
        val body = JSONObject().put("device_id", deviceId)
        if (!fcmToken.isNullOrBlank()) body.put("fcm_token", fcmToken)
        return postAuthorized(serverUrl, "/api/accounts/devices", body, token)
    }

    fun unregisterAccountDevice(serverUrl: String, token: String, deviceId: String): JSONObject {
        val request = Request.Builder().url("${serverUrl.trimEnd('/')}/api/accounts/devices/$deviceId")
            .header("Authorization", "Bearer $token").delete().build()
        return JSONObject(execute(request))
    }

    fun createSignalingTicket(serverUrl: String, token: String): JSONObject =
        postAuthorized(serverUrl, "/api/signaling/ticket", JSONObject(), token)

    fun getIceConfig(serverUrl: String, token: String): JSONObject =
        getAuthorized(serverUrl, "/api/network/ice-config", token)

    fun accountNotificationTransport(serverUrl: String, token: String, deviceId: String): JSONObject =
        getAuthorized(serverUrl, "/api/accounts/notifications/transport?device_id=${Uri.encode(deviceId)}", token)

    fun roomNotificationTransport(serverUrl: String, roomCode: String, deviceId: String): JSONObject {
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/rooms/${roomCode.trim().uppercase()}/notifications/transport?device_id=${Uri.encode(deviceId)}")
            .get()
            .build()
        return JSONObject(execute(request))
    }

    private fun postJson(serverUrl: String, path: String, json: JSONObject): JSONObject {
        val request = Request.Builder().url("${serverUrl.trimEnd('/')}$path")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return JSONObject(execute(request))
    }

    private fun postAuthorized(serverUrl: String, path: String, json: JSONObject, token: String): JSONObject {
        val request = Request.Builder().url("${serverUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer $token")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return JSONObject(execute(request))
    }

    private fun getAuthorized(serverUrl: String, path: String, token: String): JSONObject {
        val request = Request.Builder().url("${serverUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer $token").get().build()
        return JSONObject(execute(request))
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
