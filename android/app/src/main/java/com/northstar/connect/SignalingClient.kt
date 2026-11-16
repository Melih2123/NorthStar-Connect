package com.northstar.connect

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Authenticated, short-ticket signaling transport.
 * SDP/ICE messages are forwarded only between accepted friends. Canvas data
 * remains on the existing room socket until a WebRTC data channel is attached.
 */
class SignalingClient(
    private val serverUrl: String,
    private val accessToken: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit
) : Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false

    fun connect() {
        executor.execute {
            if (closed) return@execute
            try {
                val ticket = ApiClient.createSignalingTicket(serverUrl, accessToken).getString("ticket")
                val base = serverUrl.trim().trimEnd('/')
                val wsBase = when {
                    base.startsWith("https://", true) -> "wss://" + base.substring(8)
                    base.startsWith("http://", true) -> "ws://" + base.substring(7)
                    else -> "wss://$base"
                }
                val request = Request.Builder().url("$wsBase/ws/signaling?ticket=$ticket").build()
                socket = http.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        onStatus("Arkadaş sinyalleşmesi çevrimiçi")
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching { JSONObject(text) }.onSuccess(onMessage)
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        onStatus("Sinyalleşme bağlantısı kesildi: ${t.message ?: "ağ hatası"}")
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!closed) onStatus("Sinyalleşme bağlantısı kapandı")
                    }
                })
            } catch (e: Exception) {
                onStatus("Sinyalleşme başlatılamadı: ${e.message}")
            }
        }
    }

    fun send(type: String, targetAccountId: String, payload: JSONObject = JSONObject()): Boolean {
        if (type !in setOf("call_invite", "offer", "answer", "ice_candidate", "call_reject", "call_end")) return false
        val message = JSONObject()
            .put("type", type)
            .put("target_account_id", targetAccountId)
            .put("payload", payload)
        return socket?.send(message.toString()) == true
    }

    override fun close() {
        closed = true
        socket?.close(1000, "NorthStar signaling stopped")
        socket = null
        executor.shutdown()
        http.dispatcher.executorService.shutdown()
    }
}
