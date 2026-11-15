package com.northstar.connect

import android.content.Context
import android.graphics.BitmapFactory
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

class NorthStarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = SyncConfig.prefs(context)
        val imagePath = prefs.getString("latest_image_path", null)
        val bitmap = imagePath?.let { path ->
            runCatching {
                if (File(path).isFile) {
                    BitmapFactory.decodeFile(
                        path,
                        BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
                    )
                } else null
            }.getOrNull()
        }
        val roomCode = SyncConfig.roomCode(context)
        val roomLabel = if (roomCode.isNotBlank()) "SON ODA  •  $roomCode" else "HENÜZ ODA SEÇİLMEDİ"

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(R.color.northstar_bg))
                    .clickable(actionStartActivity<SharedBoardActivity>())
                    .padding(12.dp)
            ) {
                Text(
                    text = "NORTHSTAR CONNECT",
                    style = TextStyle(
                        color = ColorProvider(R.color.northstar_cyan),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = roomLabel,
                    style = TextStyle(
                        color = ColorProvider(R.color.northstar_muted),
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.sp
                    )
                )
                Spacer(modifier = GlanceModifier.height(6.dp))
                if (bitmap != null) {
                    Image(
                        provider = ImageProvider(bitmap),
                        contentDescription = "Son eklenen çizim",
                        modifier = GlanceModifier.fillMaxWidth().height(82.dp)
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = "Yeni resim eklendi",
                        style = TextStyle(
                            color = ColorProvider(R.color.northstar_white),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                } else {
                    Text(
                        text = "Henüz resim yok",
                        style = TextStyle(
                            color = ColorProvider(R.color.northstar_white),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    )
                    Spacer(modifier = GlanceModifier.height(6.dp))
                    Text(
                        text = if (roomCode.isBlank()) "Uygulamadan bir odaya katıl." else "Oda tuvali yükleniyor. Açmak için dokun.",
                        style = TextStyle(
                            color = ColorProvider(R.color.northstar_muted),
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }
    }
}

class NorthStarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NorthStarWidget()
}
