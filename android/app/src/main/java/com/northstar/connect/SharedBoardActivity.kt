package com.northstar.connect

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class SharedBoardActivity : Activity() {
    private var realtime: RealtimeCanvasClient? = null
    private lateinit var board: SharedCanvasView
    private lateinit var connectionLabel: TextView
    private lateinit var sizeLabel: TextView
    private val chatHistory = mutableListOf<JSONObject>()
    private var chatDialog: AlertDialog? = null
    private var chatFeed: LinearLayout? = null
    private var pendingChatText: EditText? = null
    private val chatImageExecutor = Executors.newSingleThreadExecutor()
    private val chatImageCache = mutableMapOf<Long, Bitmap>()
    private val accent = Color.rgb(69, 228, 250)

    private val palette = listOf(
        Color.rgb(17, 24, 39), Color.WHITE, Color.rgb(239, 68, 68),
        Color.rgb(249, 115, 22), Color.rgb(245, 158, 11),
        Color.rgb(132, 204, 22), Color.rgb(34, 197, 94),
        Color.rgb(20, 184, 166), Color.rgb(6, 182, 212),
        Color.rgb(59, 130, 246), Color.rgb(99, 102, 241),
        Color.rgb(168, 85, 247), Color.rgb(236, 72, 153),
        Color.rgb(146, 64, 14), Color.rgb(100, 116, 139)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Widget launches this Activity directly; refresh the saved room snapshot.
        SyncScheduler.schedule(this)
        SyncScheduler.refreshNow(this)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val deviceId = SyncConfig.deviceId(this)
        board = SharedCanvasView(this).apply {
            this.deviceId = deviceId
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 7, 12, 5)
        }
        val title = TextView(this).apply {
            text = "✳  NORTHSTAR CONNECT"
            textSize = 13f
            setTextColor(accent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        connectionLabel = TextView(this).apply {
            text = "BAĞLANIYOR"
            setTextColor(Color.LTGRAY)
            textSize = 10f
        }
        header.addView(title)
        header.addView(connectionLabel)
        header.addView(toolButton("CHAT") { showChatDialog() })

        val paletteScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val paletteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 5, 10, 6)
        }
        palette.forEachIndexed { index, color ->
            val chip = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(if (index == 0) dp(3) else dp(1), if (color == Color.WHITE) Color.LTGRAY else Color.argb(110, 230, 250, 255))
                }
                contentDescription = "Renk ${index + 1}"
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    marginEnd = dp(9)
                }
                setOnClickListener { board.setPenColor(color) }
            }
            paletteRow.addView(chip)
        }
        paletteScroll.addView(paletteRow)

        val toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 2, 8, 4)
        }
        tools.addView(toolButton("KALEM") { board.setPen() })
        tools.addView(toolButton("FOSFORLU") { board.setMarker() })
        tools.addView(toolButton("SİLGİ") { board.setEraser() })
        tools.addView(toolButton("GERİ AL") { board.undoOwnLastStroke() })
        tools.addView(toolButton("YİNELE") { board.redoOwnLastStroke() })
        tools.addView(toolButton("FOTO EKLE") { pickImageForCanvas() })
        tools.addView(toolButton("FOTO SEÇ / TAŞI") { board.setPhotoEditMode(true); connectionLabel.text = "FOTOĞRAF DÜZENLEME" })
        tools.addView(toolButton("FOTO SİL") { board.deleteSelectedImage() })
        tools.addView(toolButton("KALEME DÖN") { board.setPhotoEditMode(false); board.setPen(); connectionLabel.text = "CANLI" })
        tools.addView(toolButton("TEMİZLE") { board.clearSharedBoard() })
        toolsScroll.addView(tools)

        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 0, 12, 4)
        }
        val sizeTitle = TextView(this).apply {
            text = "BOYUT"
            textSize = 9f
            setTextColor(Color.rgb(159, 196, 206))
        }
        sizeLabel = TextView(this).apply {
            text = "8 PX"
            textSize = 10f
            setTextColor(accent)
            gravity = Gravity.END
        }
        val sizeBar = SeekBar(this).apply {
            max = 63
            progress = 7
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(8)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val px = progress + 1
                    board.setBrushSize(px.toFloat())
                    sizeLabel.text = "$px PX"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        sizeRow.addView(sizeTitle)
        sizeRow.addView(sizeBar)
        sizeRow.addView(sizeLabel)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 17, 26))
            addView(header)
            addView(paletteScroll)
            addView(toolsScroll)
            addView(sizeRow)
            addView(board)
        }
        setContentView(root)

        board.onSendEvent = { realtime?.send(it) }
        realtime = RealtimeCanvasClient(
            SyncConfig.serverUrl(this),
            SyncConfig.roomCode(this),
            deviceId,
            onMessage = { message ->
                runOnUiThread {
                    when (message.optString("type")) {
                        "presence" -> connectionLabel.text = "CANLI · ${message.optInt("participants", 0)}"
                        "error" -> connectionLabel.text = "HATA"
                        "history" -> {
                            board.applyServerMessage(message)
                            chatHistory.clear()
                            val messages = message.optJSONArray("messages") ?: JSONArray()
                            for (i in 0 until messages.length()) messages.optJSONObject(i)?.let { chatHistory.add(it) }
                            refreshChatFeed()
                        }
                        "chat_message" -> {
                            message.optJSONObject("message")?.let { chatHistory.add(it); if (chatHistory.size > 100) chatHistory.removeAt(0); refreshChatFeed() }
                        }
                        else -> board.applyServerMessage(message)
                    }
                }
            },
            onStatus = { text -> runOnUiThread { connectionLabel.text = text } }
        )
    }

    private fun showChatDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(290))
        }
        chatFeed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(chatFeed)
        val composer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(this).apply {
            hint = "Odaya mesaj yaz..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(13, 34, 47))
            maxLines = 4
        }
        pendingChatText = input
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(toolButton("FOTO") { pickImageForChat() })
        buttons.addView(toolButton("GÖNDER") { sendChatMessage(input.text.toString(), null) })
        composer.addView(input)
        composer.addView(buttons)
        root.addView(scroll)
        root.addView(composer)
        chatDialog = AlertDialog.Builder(this)
            .setTitle("NORTHSTAR ROOM CHAT")
            .setView(root)
            .setNegativeButton("KAPAT", null)
            .create()
        chatDialog?.show()
        refreshChatFeed()
    }

    private fun refreshChatFeed() {
        val feed = chatFeed ?: return
        feed.removeAllViews()
        if (chatHistory.isEmpty()) {
            feed.addView(TextView(this).apply { text = "Henüz mesaj yok."; setTextColor(Color.LTGRAY); setPadding(4, 12, 4, 12) })
        } else {
            chatHistory.takeLast(100).forEach { message ->
                val sender = message.optString("sender", "ROOM MEMBER")
                val text = message.optString("text", "")
                val attachment = if (message.isNull("attachment_id")) "" else "\n[Fotoğraf eklendi]"
                val bubble = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(9), dp(8), dp(9), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(9).toFloat()
                        setColor(Color.rgb(13, 38, 52))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }
                bubble.addView(TextView(this).apply {
                    this.text = "$sender\n$text$attachment"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                })
                val attachmentId = message.optLong("attachment_id", 0L)
                if (attachmentId > 0L) {
                    val preview = ImageView(this).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        maxHeight = dp(180)
                        contentDescription = "Sohbette paylaşılan fotoğraf"
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
                        ).apply { topMargin = dp(6) }
                    }
                    bubble.addView(preview)
                    val cached = chatImageCache[attachmentId]
                    if (cached != null && !cached.isRecycled) {
                        preview.setImageBitmap(cached)
                    } else {
                        chatImageExecutor.execute {
                            val bitmap = runCatching {
                                val bytes = ApiClient.downloadDrawing(
                                    SyncConfig.serverUrl(this),
                                    SyncConfig.roomCode(this),
                                    attachmentId
                                )
                                decodeChatThumbnail(bytes)
                            }.getOrNull()
                            if (bitmap != null) {
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) {
                                        chatImageCache[attachmentId] = bitmap
                                        preview.setImageBitmap(bitmap)
                                    } else if (!bitmap.isRecycled) {
                                        bitmap.recycle()
                                    }
                                }
                            }
                        }
                    }
                }
                feed.addView(bubble)
            }
        }
    }

    private fun decodeChatThumbnail(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1000 || bounds.outHeight / sample > 800) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    private fun sendChatMessage(text: String, attachmentId: Long?) {
        val payload = JSONObject()
            .put("sender", "Android")
            .put("text", text.trim())
        if (attachmentId != null) payload.put("attachment_id", attachmentId)
        if (text.isBlank() && attachmentId == null) return
        realtime?.send(JSONObject().put("type", "chat_message").put("payload", payload))
        pendingChatText?.setText("")
    }

    private fun pickImageForChat() {
        pendingImageTarget = "chat"
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(Intent.createChooser(intent, "Odaya fotoğraf ekle"), IMAGE_REQUEST_CODE)
    }

    private fun pickImageForCanvas() {
        pendingImageTarget = "canvas"
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(Intent.createChooser(intent, "Tuvale fotoğraf ekle"), IMAGE_REQUEST_CODE)
    }

    private var pendingImageTarget: String = "chat"

    @Deprecated("Use Activity Result APIs in a future cleanup")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMAGE_REQUEST_CODE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val server = SyncConfig.serverUrl(this)
        val room = SyncConfig.roomCode(this)
        val device = SyncConfig.deviceId(this)
        Thread {
            runCatching { ApiClient.uploadDrawing(this, server, room, uri, device) }
                .onSuccess { imageId ->
                    runOnUiThread {
                        if (pendingImageTarget == "canvas") {
                            val payload = JSONObject().put("image_id", imageId).put("x", 0.25).put("y", 0.22).put("width", 0.45).put("height", 0.45)
                            board.loadLocalImage(imageId, 0.25f, 0.22f, 0.45f, 0.45f)
                            realtime?.send(JSONObject().put("type", "image_add").put("payload", payload))
                        } else {
                            sendChatMessage(pendingChatText?.text?.toString().orEmpty(), imageId)
                        }
                    }
                }
                .onFailure { error -> runOnUiThread { connectionLabel.text = "FOTO HATASI: ${error.message?.take(50)}" } }
        }.start()
    }

    companion object { private const val IMAGE_REQUEST_CODE = 4102 }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toolButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 9f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.rgb(12, 48, 65))
            setStroke(dp(1), Color.rgb(38, 104, 125))
        }
        setPadding(dp(8), dp(4), dp(8), dp(4))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
        ).apply { marginEnd = dp(6) }
        setOnClickListener { action() }
    }

    override fun onDestroy() {
        board.dispose()
        chatImageExecutor.shutdownNow()
        chatImageCache.values.forEach { if (!it.isRecycled) it.recycle() }
        chatImageCache.clear()
        chatDialog?.dismiss()
        realtime?.close()
        realtime = null
        super.onDestroy()
    }
}
