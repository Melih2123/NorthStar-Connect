package com.northstar.connect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max

class SharedCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Stroke(
        val id: String,
        val color: Int,
        val width: Float,
        val tool: String,
        val owner: String,
        val points: MutableList<PointF> = mutableListOf()
    )

    var deviceId: String = "android"
    var onSendEvent: ((JSONObject) -> Unit)? = null

    private val strokes = LinkedHashMap<String, Stroke>()
    private val deletedStrokes = LinkedHashMap<String, Stroke>()
    private val ownStrokeIds = mutableListOf<String>()
    private val redoStrokeIds = mutableListOf<String>()
    private val seenEventIds = mutableSetOf<Long>()
    private val sharedImages = LinkedHashMap<Long, SharedImage>()
    private val imagePositions = LinkedHashMap<Long, FloatArray>()
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private data class SharedImage(val bitmap: Bitmap, var x: Float, var y: Float, var width: Float, var height: Float)
    private var activeStroke: Stroke? = null
    private var pendingPoints = JSONArray()
    private var selectedColor = Color.rgb(17, 24, 39)
    private var selectedTool = "pen"
    private var brushSizePx = 8f
    private var photoEditMode = false
    private var selectedImageId: Long? = null
    private var movingImageId: Long? = null
    private var imageTouchOffsetX = 0f
    private var imageTouchOffsetY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setPenColor(color: Int) {
        selectedColor = color
        selectedTool = "pen"
        invalidate()
    }

    fun setPen() {
        selectedTool = "pen"
        invalidate()
    }

    fun setMarker() {
        selectedTool = "marker"
        invalidate()
    }

    fun setEraser() {
        selectedTool = "eraser"
        invalidate()
    }

    fun setBrushSize(px: Float) {
        brushSizePx = px.coerceIn(1f, 64f)
    }

    fun setPhotoEditMode(enabled: Boolean) {
        photoEditMode = enabled
        if (!enabled) { selectedImageId = null; movingImageId = null }
        invalidate()
    }

    fun deleteSelectedImage() {
        val id = selectedImageId ?: return
        val image = sharedImages.remove(id)
        if (image != null && !image.bitmap.isRecycled) image.bitmap.recycle()
        selectedImageId = null
        onSendEvent?.invoke(JSONObject().put("type", "image_delete").put("payload", JSONObject().put("image_id", id)))
        invalidate()
    }

    fun undoOwnLastStroke() {
        val id = ownStrokeIds.lastOrNull() ?: return
        ownStrokeIds.removeAt(ownStrokeIds.lastIndex)
        strokes.remove(id)?.let { deletedStrokes[id] = it }
        redoStrokeIds.add(id)
        onSendEvent?.invoke(JSONObject().put("type", "stroke_delete")
            .put("payload", JSONObject().put("target_stroke_id", id)))
        invalidate()
    }

    fun redoOwnLastStroke() {
        val id = redoStrokeIds.lastOrNull() ?: return
        val stroke = deletedStrokes[id] ?: return
        redoStrokeIds.removeAt(redoStrokeIds.lastIndex)
        deletedStrokes.remove(id)
        strokes[id] = stroke
        ownStrokeIds.add(id)
        onSendEvent?.invoke(JSONObject().put("type", "stroke_restore")
            .put("payload", JSONObject().put("target_stroke_id", id)))
        invalidate()
    }

    fun clearSharedBoard() {
        strokes.clear()
        deletedStrokes.clear()
        ownStrokeIds.clear()
        redoStrokeIds.clear()
        sharedImages.values.forEach { it.bitmap.recycle() }
        sharedImages.clear()
        activeStroke = null
        pendingPoints = JSONArray()
        onSendEvent?.invoke(JSONObject().put("type", "clear").put("payload", JSONObject()))
        invalidate()
    }

    fun loadLocalImage(imageId: Long, x: Float, y: Float, imageWidth: Float, imageHeight: Float) {
        if (imageId <= 0 || sharedImages.containsKey(imageId)) return
        imagePositions[imageId] = floatArrayOf(x, y, imageWidth, imageHeight)
        imageExecutor.execute {
            runCatching { ApiClient.downloadDrawing(SyncConfig.serverUrl(context), SyncConfig.roomCode(context), imageId) }
                .onSuccess { bytes ->
                    val bitmap = decodeCanvasImage(bytes)
                    if (bitmap != null) post {
                        sharedImages[imageId] = SharedImage(bitmap, x, y, imageWidth, imageHeight)
                        invalidate()
                    }
                }
        }
    }

    fun applyServerMessage(message: JSONObject) {
        when (message.optString("type")) {
            "history" -> {
                strokes.clear()
                deletedStrokes.clear()
                ownStrokeIds.clear()
                redoStrokeIds.clear()
                sharedImages.values.forEach { it.bitmap.recycle() }
                sharedImages.clear()
                imagePositions.clear()
                seenEventIds.clear()
                activeStroke = null
                pendingPoints = JSONArray()
                val events = message.optJSONArray("events") ?: JSONArray()
                for (i in 0 until events.length()) applyEvent(events.optJSONObject(i))
                postInvalidate()
            }
            "event" -> {
                val event = message.optJSONObject("event") ?: return
                if (event.optString("device_id") == deviceId) return
                applyEvent(event)
                postInvalidate()
            }
        }
    }

    private fun applyEvent(event: JSONObject?) {
        if (event == null) return
        val eventId = event.optLong("event_id", -1L)
        if (eventId > 0 && !seenEventIds.add(eventId)) return
        val type = event.optString("type")
        val id = event.optString("stroke_id")
        val payload = event.optJSONObject("payload") ?: JSONObject()
        val owner = event.optString("device_id")
        when (type) {
            "clear" -> {
                strokes.clear()
                deletedStrokes.clear()
                ownStrokeIds.clear()
                redoStrokeIds.clear()
                sharedImages.values.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                sharedImages.clear()
                imagePositions.clear()
                activeStroke = null
                pendingPoints = JSONArray()
            }
            "stroke_begin" -> {
                val point = payload.optJSONArray("point") ?: JSONArray()
                if (point.length() >= 2) {
                    val stroke = Stroke(
                        id = id,
                        color = parseColor(payload.optString("color", "#111827")),
                        width = payload.optDouble("width", 0.006).toFloat(),
                        tool = payload.optString("tool", "pen"),
                        owner = owner
                    )
                    stroke.points.add(PointF(point.optDouble(0).toFloat(), point.optDouble(1).toFloat()))
                    strokes[id] = stroke
                    if (owner == deviceId) ownStrokeIds.add(id)
                    redoStrokeIds.clear()
                }
            }
            "stroke_points" -> {
                val stroke = strokes[id] ?: return
                val points = payload.optJSONArray("points") ?: JSONArray()
                for (i in 0 until points.length()) {
                    val point = points.optJSONArray(i) ?: continue
                    if (point.length() >= 2) {
                        stroke.points.add(PointF(point.optDouble(0).toFloat(), point.optDouble(1).toFloat()))
                    }
                }
            }
            "stroke_delete" -> {
                val target = payload.optString("target_stroke_id")
                val removed = strokes.remove(target)
                if (removed != null) {
                    deletedStrokes[target] = removed
                    if (removed.owner == deviceId && !redoStrokeIds.contains(target)) {
                        redoStrokeIds.add(target)
                    }
                }
                ownStrokeIds.remove(target)
            }
            "stroke_restore" -> {
                val target = payload.optString("target_stroke_id")
                val restored = deletedStrokes.remove(target)
                if (restored != null) {
                    strokes[target] = restored
                    if (restored.owner == deviceId && !ownStrokeIds.contains(target)) {
                        ownStrokeIds.add(target)
                    }
                }
                redoStrokeIds.remove(target)
            }
            "image_update" -> {
                val imageId = payload.optLong("image_id", -1L)
                val position = floatArrayOf(
                    payload.optDouble("x", 0.2).toFloat(), payload.optDouble("y", 0.2).toFloat(),
                    payload.optDouble("width", 0.35).toFloat(), payload.optDouble("height", 0.35).toFloat()
                )
                imagePositions[imageId] = position
                sharedImages[imageId]?.let { image ->
                    image.x = position[0]; image.y = position[1]; image.width = position[2]; image.height = position[3]
                }
            }
            "image_delete" -> {
                val imageId = payload.optLong("image_id", -1L)
                sharedImages.remove(imageId)?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                imagePositions.remove(imageId)
                if (selectedImageId == imageId) selectedImageId = null
            }
            "image_add" -> {
                val imageId = payload.optLong("image_id", -1L)
                val imageUrl = payload.optString("image_url")
                if (imageId > 0 && imageUrl.isNotBlank() && !sharedImages.containsKey(imageId)) {
                    val x = payload.optDouble("x", 0.2).toFloat()
                    val y = payload.optDouble("y", 0.2).toFloat()
                    val imageWidth = payload.optDouble("width", 0.35).toFloat()
                    val imageHeight = payload.optDouble("height", 0.35).toFloat()
                    imagePositions[imageId] = floatArrayOf(x, y, imageWidth, imageHeight)
                    imageExecutor.execute {
                        runCatching { ApiClient.downloadDrawing(SyncConfig.serverUrl(context), SyncConfig.roomCode(context), imageId) }
                            .onSuccess { bytes ->
                                val bitmap = decodeCanvasImage(bytes)
                                if (bitmap != null) post {
                                    val current = imagePositions[imageId]
                                    if (current == null) { if (!bitmap.isRecycled) bitmap.recycle() }
                                    else {
                                        sharedImages[imageId] = SharedImage(bitmap, current[0], current[1], current[2], current[3])
                                        invalidate()
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    private fun decodeCanvasImage(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1200) {
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

    private fun parseColor(raw: String): Int =
        try { Color.parseColor(raw) } catch (_: Exception) { Color.BLACK }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        for (stroke in strokes.values) drawStroke(canvas, stroke)
        for (image in sharedImages.values) {
            val dst = android.graphics.RectF(
                image.x * width, image.y * height,
                (image.x + image.width) * width, (image.y + image.height) * height
            )
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(image.bitmap, null, dst, imagePaint)
            if (sharedImages.entries.firstOrNull { it.value === image }?.key == selectedImageId) {
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 169, 199); style = Paint.Style.STROKE; strokeWidth = 3f }
                canvas.drawRect(dst, border)
            }
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        val path = Path()
        val first = stroke.points.first()
        val firstX = first.x * width
        val firstY = first.y * height
        path.moveTo(firstX, firstY)
        if (stroke.points.size == 1) {
            path.lineTo(firstX + 0.1f, firstY + 0.1f)
        } else {
            for (i in 1 until stroke.points.size) {
                val point = stroke.points[i]
                path.lineTo(point.x * width, point.y * height)
            }
        }
        paint.strokeWidth = max(1f, stroke.width * width)
        paint.color = stroke.color
        paint.alpha = if (stroke.tool == "marker") 92 else 255
        paint.xfermode = if (stroke.tool == "eraser") PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
        canvas.drawPath(path, paint)
        paint.xfermode = null
        paint.alpha = 255
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x / max(1f, width.toFloat())).coerceIn(0f, 1f)
        val y = (event.y / max(1f, height.toFloat())).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (photoEditMode) {
                    val hit = sharedImages.entries.toList().asReversed().firstOrNull { (_, image) ->
                        x >= image.x && x <= image.x + image.width && y >= image.y && y <= image.y + image.height
                    }
                    selectedImageId = hit?.key
                    movingImageId = hit?.key
                    hit?.value?.let { image -> imageTouchOffsetX = x - image.x; imageTouchOffsetY = y - image.y }
                    invalidate()
                    return true
                }
                val id = UUID.randomUUID().toString()
                val widthFraction = brushSizePx / max(1f, width.toFloat())
                val stroke = Stroke(id, selectedColor, widthFraction, selectedTool, deviceId)
                stroke.points.add(PointF(x, y))
                strokes[id] = stroke
                ownStrokeIds.add(id)
                redoStrokeIds.clear()
                activeStroke = stroke
                pendingPoints = JSONArray()
                val payload = JSONObject()
                    .put("point", JSONArray().put(x).put(y))
                    .put("color", String.format("#%06X", 0xFFFFFF and selectedColor))
                    .put("width", widthFraction)
                    .put("tool", selectedTool)
                onSendEvent?.invoke(JSONObject().put("type", "stroke_begin").put("stroke_id", id).put("payload", payload))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (photoEditMode) {
                    val image = movingImageId?.let { sharedImages[it] } ?: return true
                    image.x = (x - imageTouchOffsetX).coerceIn(0f, 1f - image.width)
                    image.y = (y - imageTouchOffsetY).coerceIn(0f, 1f - image.height)
                    invalidate()
                    return true
                }
                val stroke = activeStroke ?: return true
                for (i in 0 until event.historySize) {
                    appendPoint(stroke, event.getHistoricalX(i) / max(1f, width.toFloat()), event.getHistoricalY(i) / max(1f, height.toFloat()))
                }
                appendPoint(stroke, x, y)
                flushPointsIfNeeded(false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (photoEditMode) {
                    val id = movingImageId
                    val image = id?.let { sharedImages[it] }
                    if (id != null && image != null) {
                        onSendEvent?.invoke(JSONObject().put("type", "image_update").put("payload", JSONObject()
                            .put("image_id", id).put("x", image.x).put("y", image.y).put("width", image.width).put("height", image.height)))
                    }
                    movingImageId = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (activeStroke != null) {
                    flushPointsIfNeeded(true)
                    onSendEvent?.invoke(JSONObject().put("type", "stroke_end").put("stroke_id", activeStroke!!.id).put("payload", JSONObject()))
                    activeStroke = null
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun appendPoint(stroke: Stroke, rawX: Float, rawY: Float) {
        val x = rawX.coerceIn(0f, 1f)
        val y = rawY.coerceIn(0f, 1f)
        stroke.points.add(PointF(x, y))
        pendingPoints.put(JSONArray().put(x).put(y))
    }

    fun dispose() {
        imageExecutor.shutdown()
        sharedImages.values.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        sharedImages.clear()
        imagePositions.clear()
    }

    private fun flushPointsIfNeeded(force: Boolean) {
        val stroke = activeStroke ?: return
        while (pendingPoints.length() >= 6 || (force && pendingPoints.length() > 0)) {
            val batch = JSONArray()
            val count = minOf(64, pendingPoints.length())
            for (i in 0 until count) batch.put(pendingPoints.get(i))
            val remaining = JSONArray()
            for (i in count until pendingPoints.length()) remaining.put(pendingPoints.get(i))
            pendingPoints = remaining
            onSendEvent?.invoke(JSONObject()
                .put("type", "stroke_points")
                .put("stroke_id", stroke.id)
                .put("payload", JSONObject().put("points", batch)))
        }
    }
}
