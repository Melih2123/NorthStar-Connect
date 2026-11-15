package com.northstar.connect

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.min

class RealtimeCanvasClient(
    private val serverUrl: String,
    private val roomCode: String,
    private val deviceId: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var manuallyClosed = false
    private var reconnectAttempt = 0

    init { openSocket() }

    private fun openSocket() {
        if (manuallyClosed) return
        val base = serverUrl.trim().trimEnd('/')
        val wsBase = when {
            base.startsWith("https://", true) -> "wss://" + base.substring(8)
            base.startsWith("http://", true) -> "ws://" + base.substring(7)
            else -> "wss://$base"
        }
        val encodedRoom = URLEncoder.encode(roomCode.trim().uppercase(), "UTF-8")
        val encodedDevice = URLEncoder.encode(deviceId, "UTF-8")
        val request = Request.Builder()
            .url("$wsBase/ws/rooms/$encodedRoom?device_id=$encodedDevice")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                onStatus("Ortak tuvale bağlandı")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }
                    .onSuccess(onMessage)
                    .onFailure { onStatus("Sunucu mesajı okunamadı: ${it.message}") }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("Bağlantı kapatılıyor: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!manuallyClosed) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("Canlı bağlantı kesildi; yeniden bağlanılıyor...")
                if (!manuallyClosed) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        val delayMs = min(30_000L, 1_000L * (1L shl min(reconnectAttempt, 5)))
        reconnectAttempt += 1
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ openSocket() }, delayMs)
    }

    fun send(message: JSONObject) {
        val sent = socket?.send(message.toString()) ?: false
        if (!sent) onStatus("Bağlantı kuruluyor; tekrar dene")
    }

    fun close() {
        manuallyClosed = true
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "Activity closed")
        socket = null
        client.dispatcher.executorService.shutdown()
    }
}
