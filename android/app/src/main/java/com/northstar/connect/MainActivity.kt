package com.northstar.connect

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var serverInput: EditText
    private lateinit var roomInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()

        val prefs = SyncConfig.prefs(this)
        serverInput = editText().apply {
            setText(SyncConfig.serverUrl(this@MainActivity))
            hint = "https://your-funnel-name.ts.net"
        }
        roomInput = editText().apply {
            setText(prefs.getString("room_code", ""))
            hint = "12 karakterli oda kodu"
        }
        status = TextView(this).apply {
            text = "Aynı oda kodundaki herkes aynı tuvali canlı olarak düzenler. Firebase yoksa Tailscale yedek bildirimleri kullanılır."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 16, 0, 16)
        }

        val title = TextView(this).apply {
            text = "NORTHSTAR CONNECT"
            textSize = 22f
            setTextColor(Color.rgb(65, 225, 245))
        }
        val subtitle = TextView(this).apply {
            text = "REALTIME SHARED CANVAS / v0.5"
            textSize = 11f
            setTextColor(Color.LTGRAY)
        }
        val create = button("YENİ ORTAK TUVAL OLUŞTUR")
        val join = button("ORTAK TUVALE KATIL")
        val open = button("SON ODAMI AÇ")

        create.setOnClickListener {
            val server = serverInput.text.toString().trim().trimEnd('/')
            if (server.isBlank()) {
                status.text = "Önce Server URL gir."
                return@setOnClickListener
            }
            status.text = "Ortak oda oluşturuluyor..."
            executor.execute {
                runCatching { ApiClient.createRoom(server) }
                    .onSuccess { code -> runOnUiThread { roomInput.setText(code); enterRoom(server, code) } }
                    .onFailure { e -> runOnUiThread { status.text = "Hata: ${e.message}" } }
            }
        }

        join.setOnClickListener {
            val server = serverInput.text.toString().trim().trimEnd('/')
            val room = roomInput.text.toString().trim().uppercase()
            if (server.isBlank() || room.isBlank()) {
                status.text = "Server URL ve oda kodu gerekli."
                return@setOnClickListener
            }
            status.text = "Oda kontrol ediliyor..."
            executor.execute {
                runCatching { ApiClient.checkRoom(server, room) }
                    .onSuccess { runOnUiThread { enterRoom(server, room) } }
                    .onFailure { e -> runOnUiThread { status.text = "Bağlantı hatası: ${e.message}" } }
            }
        }

        open.setOnClickListener {
            val server = serverInput.text.toString().trim().trimEnd('/').ifBlank { SyncConfig.serverUrl(this) }
            val room = roomInput.text.toString().trim().uppercase().ifBlank { SyncConfig.roomCode(this) }
            if (server.isBlank() || room.isBlank()) {
                status.text = "Önce bir sunucu ve oda seç."
            } else {
                executor.execute {
                    runCatching { ApiClient.checkRoom(server, room) }
                        .onSuccess { runOnUiThread { enterRoom(server, room) } }
                        .onFailure { e -> runOnUiThread { status.text = "Oda açılamadı: ${e.message}" } }
                }
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.rgb(5, 17, 26))
            addView(title)
            addView(subtitle)
            addView(label("SERVER ADDRESS"))
            addView(serverInput)
            addView(label("SHARED ROOM CODE"))
            addView(roomInput)
            addView(create)
            addView(join)
            addView(open)
            addView(status)
        }
        setContentView(layout)

        // Reopen the last room automatically after an app/process restart.
        val savedServer = SyncConfig.serverUrl(this)
        val savedRoom = SyncConfig.roomCode(this)
        if (savedServer.isNotBlank() && savedRoom.isNotBlank()) {
            status.text = "Son ortak tuval geri yükleniyor..."
            executor.execute {
                runCatching { ApiClient.checkRoom(savedServer, savedRoom) }
                    .onSuccess { runOnUiThread { enterRoom(savedServer, savedRoom) } }
                    .onFailure { e -> runOnUiThread { status.text = "Son oda açılamadı: ${e.message}. İstersen tekrar katılabilirsin." } }
            }
        }
    }

    private fun enterRoom(server: String, room: String) {
        SyncConfig.save(this, server, room)
        SyncScheduler.schedule(this)
        SyncScheduler.refreshNow(this)
        registerFcmToken()
        startActivity(Intent(this, SharedBoardActivity::class.java))
    }

    private fun registerFcmToken() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            status.text = "Firebase bulunamadı. Tailscale üzerinden yedek bildirim kontrolü etkin (yaklaşık 15 dk)."
            // SyncScheduler is already scheduled for the current room; its worker
            // posts local notifications when it detects remote events/messages.
            return
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Thread {
                    runCatching {
                        ApiClient.registerFcm(
                            SyncConfig.serverUrl(this),
                            SyncConfig.roomCode(this),
                            SyncConfig.deviceId(this),
                            token
                        )
                    }
                }.start()
            }
            .addOnFailureListener { e -> runOnUiThread { status.text = "FCM token alınamadı: ${e.message}" } }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 300)
        }
    }

    private fun editText() = EditText(this).apply {
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        textSize = 14f
        setPadding(12, 10, 12, 10)
        setBackgroundColor(Color.rgb(13, 34, 47))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 10 }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 10f
        setTextColor(Color.rgb(65, 225, 245))
        setPadding(0, 10, 0, 5)
    }

    private fun button(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(12, 65, 82))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = 8 }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
