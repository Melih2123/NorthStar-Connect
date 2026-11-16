package com.northstar.connect

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var serverInput: EditText
    private lateinit var roomInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var friendInput: EditText
    private lateinit var status: TextView
    private lateinit var socialStatus: TextView
    private lateinit var socialList: LinearLayout

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
        usernameInput = editText().apply {
            setText(IdentityStore.username(this@MainActivity))
            hint = "Hesap adı"
        }
        passwordInput = editText().apply {
            hint = "Şifre (en az 10 karakter)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        friendInput = editText().apply { hint = "Arkadaşın kullanıcı adı" }
        status = infoText("Aynı oda kodundaki herkes aynı tuvali canlı düzenler. Hesap sistemi arkadaşlık ve cihazlar arası davet içindir.")
        socialStatus = infoText(if (IdentityStore.isSignedIn(this)) "Hesap: @${IdentityStore.username(this)}" else "Hesapla giriş yap veya hesap oluştur.")
        socialList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = "NORTHSTAR CONNECT"
            textSize = 22f
            setTextColor(Color.rgb(65, 225, 245))
        }
        val subtitle = TextView(this).apply {
            text = "ACCOUNT / FRIENDS / REALTIME CANVAS · v0.6"
            textSize = 11f
            setTextColor(Color.LTGRAY)
        }

        val register = button("HESAP OLUŞTUR")
        val login = button("GİRİŞ YAP")
        val logout = button("ÇIKIŞ YAP")
        val addFriend = button("ARKADAŞLIK İSTEĞİ GÖNDER")
        val refreshSocial = button("ARKADAŞLARI / İSTEKLERİ YENİLE")
        val openBrowserP2p = button("TARAYICIDA DOĞRUDAN P2P AÇ") {
            val base = server()
            if (base.isBlank()) {
                socialStatus.text = "Önce Server URL gir."
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${base.trimEnd('/')}/friends.html")))
            }
        }
        register.setOnClickListener { authenticate(register = true) }
        login.setOnClickListener { authenticate(register = false) }
        logout.setOnClickListener {
            val token = IdentityStore.accessToken(this)
            if (token.isNullOrBlank()) {
                IdentityStore.clear(this)
                socialStatus.text = "Oturum kapatıldı."
            } else {
                val serverUrl = server()
                executor.execute {
                runCatching { ApiClient.unregisterAccountDevice(serverUrl, token, SyncConfig.deviceId(this)) }
                runCatching { ApiClient.logoutAccount(serverUrl, token) }
                runOnUiThread {
                    IdentityStore.clear(this)
                    socialStatus.text = "Oturum kapatıldı."
                    socialList.removeAllViews()
                }
                }
            }
        }
        addFriend.setOnClickListener { addFriend() }
        refreshSocial.setOnClickListener { refreshSocial() }

        val socialHeader = header("HESAP & ARKADAŞLAR")
        val accountButtons = horizontalRow().apply { addView(register, weightedParams()); addView(login, weightedParams()) }

        val create = button("YENİ ORTAK TUVAL OLUŞTUR")
        val join = button("ORTAK TUVALE KATIL")
        val open = button("SON ODAMI AÇ")
        create.setOnClickListener {
            val server = server()
            if (server.isBlank()) { status.text = "Önce Server URL gir."; return@setOnClickListener }
            status.text = "Ortak oda oluşturuluyor..."
            executor.execute {
                runCatching { ApiClient.createRoom(server) }
                    .onSuccess { code -> runOnUiThread { roomInput.setText(code); enterRoom(server, code) } }
                    .onFailure { e -> runOnUiThread { status.text = "Hata: ${e.message}" } }
            }
        }
        join.setOnClickListener {
            val server = server()
            val room = roomInput.text.toString().trim().uppercase()
            if (server.isBlank() || room.isBlank()) { status.text = "Server URL ve oda kodu gerekli."; return@setOnClickListener }
            status.text = "Oda kontrol ediliyor..."
            executor.execute {
                runCatching { ApiClient.checkRoom(server, room) }
                    .onSuccess { runOnUiThread { enterRoom(server, room) } }
                    .onFailure { e -> runOnUiThread { status.text = "Bağlantı hatası: ${e.message}" } }
            }
        }
        open.setOnClickListener {
            val server = server().ifBlank { SyncConfig.serverUrl(this) }
            val room = roomInput.text.toString().trim().uppercase().ifBlank { SyncConfig.roomCode(this) }
            if (room.isBlank()) { status.text = "Önce bir oda seç."; return@setOnClickListener }
            executor.execute {
                runCatching { ApiClient.checkRoom(server, room) }
                    .onSuccess { runOnUiThread { enterRoom(server, room) } }
                    .onFailure { e -> runOnUiThread { status.text = "Oda açılamadı: ${e.message}" } }
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.rgb(5, 17, 26))
            addView(title)
            addView(subtitle)
            addView(socialHeader)
            addView(label("USERNAME")); addView(usernameInput)
            addView(label("PASSWORD")); addView(passwordInput)
            addView(accountButtons)
            addView(logout)
            addView(socialStatus)
            addView(label("ADD FRIEND")); addView(friendInput)
            addView(addFriend); addView(refreshSocial); addView(openBrowserP2p); addView(socialList)
            addView(label("SERVER ADDRESS")); addView(serverInput)
            addView(label("SHARED ROOM CODE")); addView(roomInput)
            addView(create); addView(join); addView(open); addView(status)
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(content) })

        if (IdentityStore.isSignedIn(this)) {
            refreshSocial()
            registerAccountFcmToken()
        }

        // Reopen last room after process restart.
        val savedServer = SyncConfig.serverUrl(this)
        val savedRoom = SyncConfig.roomCode(this)
        if (savedRoom.isNotBlank()) {
            status.text = "Son ortak tuval geri yükleniyor..."
            executor.execute {
                runCatching { ApiClient.checkRoom(savedServer, savedRoom) }
                    .onSuccess { runOnUiThread { enterRoom(savedServer, savedRoom) } }
                    .onFailure { e -> runOnUiThread { status.text = "Son oda açılamadı: ${e.message}" } }
            }
        }
    }

    private fun authenticate(register: Boolean) {
        val server = server()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            socialStatus.text = "Server URL, hesap adı ve şifre gerekli."
            return
        }
        socialStatus.text = if (register) "Hesap oluşturuluyor..." else "Giriş yapılıyor..."
        executor.execute {
            val result = runCatching {
                if (register) ApiClient.registerAccount(server, username, password)
                else ApiClient.loginAccount(server, username, password)
            }
            runOnUiThread {
                result.onSuccess { response ->
                    val account = response.getJSONObject("account")
                    IdentityStore.save(this, account.getString("account_id"), account.getString("username"), response.getString("access_token"))
                    passwordInput.text.clear()
                    socialStatus.text = "Giriş yapıldı: @${account.getString("username")} · ID ${account.getString("account_id").take(8)}…"
                    SyncScheduler.schedule(this)
                    SyncScheduler.refreshNow(this)
                    registerAccountFcmToken()
                    refreshSocial()
                }.onFailure { e -> socialStatus.text = "Hesap hatası: ${e.message}" }
            }
        }
    }

    private fun addFriend() {
        val token = IdentityStore.accessToken(this)
        val username = friendInput.text.toString().trim()
        if (token.isNullOrBlank()) { socialStatus.text = "Önce hesabına giriş yap."; return }
        if (username.isBlank()) { socialStatus.text = "Arkadaş kullanıcı adını yaz."; return }
        socialStatus.text = "Arkadaşlık isteği gönderiliyor..."
        val serverUrl = server()
        executor.execute {
            val result = runCatching { ApiClient.sendFriendRequest(serverUrl, token, username) }
            runOnUiThread {
                result.onSuccess { socialStatus.text = "İstek durumu: ${it.optString("status", "pending")}"; refreshSocial() }
                    .onFailure { socialStatus.text = "İstek gönderilemedi: ${it.message}" }
            }
        }
    }

    private fun refreshSocial() {
        val token = IdentityStore.accessToken(this) ?: run {
            socialStatus.text = "Hesapla giriş yapmadan arkadaşlık özellikleri kullanılamaz."
            return
        }
        socialStatus.text = "Arkadaşlar ve davetler yükleniyor..."
        val serverUrl = server()
        executor.execute {
            val result = runCatching {
                Triple(
                    ApiClient.getFriends(serverUrl, token),
                    ApiClient.getFriendRequests(serverUrl, token),
                    ApiClient.getInvitations(serverUrl, token)
                )
            }
            runOnUiThread {
                result.onSuccess { (friends, requests, invitations) ->
                    socialList.removeAllViews()
                    val friendArray = friends.optJSONArray("friends") ?: org.json.JSONArray()
                    socialList.addView(label("ARKADAŞLAR (${friendArray.length()})"))
                    for (i in 0 until friendArray.length()) {
                        val friend = friendArray.getJSONObject(i)
                        val id = friend.getString("account_id")
                        val name = friend.getString("username")
                        val row = horizontalRow()
                        row.addView(infoText("@$name · ${if (friend.optBoolean("online")) "ONLINE" else "OFFLINE"}"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        row.addView(button("DİREKT DAVET") {
                            inviteFriend(id, null)
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        socialList.addView(row)
                    }

                    val incoming = requests.optJSONArray("incoming") ?: org.json.JSONArray()
                    socialList.addView(label("GELEN ARKADAŞLIK İSTEKLERİ (${incoming.length()})"))
                    for (i in 0 until incoming.length()) {
                        val request = incoming.getJSONObject(i)
                        val id = request.getLong("request_id")
                        val name = request.getString("username")
                        socialList.addView(button("@$name · KABUL ET") { acceptFriend(id) })
                    }

                    val invites = invitations.optJSONArray("invitations") ?: org.json.JSONArray()
                    socialList.addView(label("BAĞLANTI DAVETLERİ (${invites.length()})"))
                    for (i in 0 until invites.length()) {
                        val invite = invites.getJSONObject(i)
                        val id = invite.getString("invitation_id")
                        val name = invite.getString("sender_username")
                        socialList.addView(button("@$name · DAVETİ KABUL ET") { acceptInvite(id) })
                    }
                    socialStatus.text = "Hesap: @${IdentityStore.username(this)} · ${friendArray.length()} arkadaş · ${invites.length()} bekleyen davet"
                }.onFailure { socialStatus.text = "Sosyal liste yüklenemedi: ${it.message}" }
            }
        }
    }

    private fun acceptFriend(requestId: Long) {
        val token = IdentityStore.accessToken(this) ?: return
        val serverUrl = server()
        executor.execute {
            val result = runCatching { ApiClient.acceptFriendRequest(serverUrl, token, requestId) }
            runOnUiThread {
                result.onSuccess { socialStatus.text = "Arkadaşlık kuruldu."; refreshSocial() }
                    .onFailure { socialStatus.text = "İstek kabul edilemedi: ${it.message}" }
            }
        }
    }

    private fun inviteFriend(friendId: String, roomCode: String?) {
        val token = IdentityStore.accessToken(this) ?: return
        val serverUrl = server()
        executor.execute {
            val result = runCatching { ApiClient.inviteFriend(serverUrl, token, friendId, roomCode) }
            runOnUiThread {
                result.onSuccess {
                    val invitationId = it.optString("invitation_id")
                    val online = it.optBoolean("online")
                    socialStatus.text = if (online) {
                        "Arkadaşın tarayıcı sinyalleşmesinde çevrimiçi. Davet kaydedildi; Android yerel P2P aktarımı henüz etkin değil."
                    } else {
                        "Davet sunucuya kaydedildi. Çevrimdışıysa FCM veya arka plan kontrolüyle ulaşacak."
                    }
                }.onFailure { socialStatus.text = "Davet gönderilemedi: ${it.message}" }
            }
        }
    }

    private fun acceptInvite(invitationId: String) {
        val token = IdentityStore.accessToken(this) ?: return
        val serverUrl = server()
        executor.execute {
            val result = runCatching { ApiClient.acceptInvitation(serverUrl, token, invitationId) }
            runOnUiThread {
                result.onSuccess { response ->
                    socialStatus.text = if (response.optString("invite_type") == "room") {
                        val code = response.optString("room_code")
                        roomInput.setText(code)
                        "Oda daveti kabul edildi. Katılmak için ORTAK TUVALE KATIL'a bas."
                    } else "Direkt davet kabul edildi. Tarayıcı P2P testini Arkadaşlar sayfasında başlatabilirsin; native Android P2P sonraki aşama."
                    refreshSocial()
                }.onFailure { socialStatus.text = "Davet kabul edilemedi: ${it.message}" }
            }
        }
    }

    private fun registerAccountFcmToken() {
        val token = IdentityStore.accessToken(this) ?: return
        val server = server()
        if (FirebaseApp.getApps(this).isEmpty()) {
            socialStatus.text = "Hesap açık. Firebase yok; arkadaş davetleri sunucu kutusunda saklanır ve arka plan kontrolüyle alınır."
            return
        }
        FirebaseMessaging.getInstance().token.addOnSuccessListener { fcm ->
            executor.execute { runCatching { ApiClient.registerAccountDevice(server, token, SyncConfig.deviceId(this), fcm) } }
        }
    }

    private fun registerFcmToken() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            status.text = "Firebase bulunamadı. Tailscale üzerinden yedek bildirim kontrolü etkin (yaklaşık 15 dk)."
            return
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                executor.execute {
                    runCatching {
                        ApiClient.registerFcm(SyncConfig.serverUrl(this), SyncConfig.roomCode(this), SyncConfig.deviceId(this), token)
                        IdentityStore.accessToken(this)?.let { ApiClient.registerAccountDevice(SyncConfig.serverUrl(this), it, SyncConfig.deviceId(this), token) }
                    }
                }
            }
            .addOnFailureListener { e -> runOnUiThread { status.text = "FCM token alınamadı: ${e.message}" } }
    }

    private fun enterRoom(server: String, room: String) {
        SyncConfig.save(this, server, room)
        SyncScheduler.schedule(this)
        SyncScheduler.refreshNow(this)
        registerFcmToken()
        startActivity(Intent(this, SharedBoardActivity::class.java))
    }

    private fun server() = serverInput.text.toString().trim().trimEnd('/').ifBlank { SyncConfig.serverUrl(this) }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 300)
        }
    }

    private fun editText() = EditText(this).apply {
        setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 14f
        setPadding(12, 10, 12, 10); setBackgroundColor(Color.rgb(13, 34, 47))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 10f; setTextColor(Color.rgb(65, 225, 245)); setPadding(0, 10, 0, 5)
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Color.rgb(65, 225, 245)); setPadding(0, 24, 0, 8)
    }

    private fun infoText(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, 8, 0, 8)
    }

    private fun button(text: String, action: (() -> Unit)? = null) = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(12, 65, 82))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
        if (action != null) setOnClickListener { action() }
    }

    private fun horizontalRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weightedParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
