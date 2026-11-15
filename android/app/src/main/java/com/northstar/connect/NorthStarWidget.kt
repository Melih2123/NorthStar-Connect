package com.northstar.connect

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class NorthStarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // First milestone: a local demo message. Remote sync comes later.
        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(R.color.northstar_bg))
                    .padding(14.dp)
            ) {
                Text(
                    text = "NORTHSTAR CONNECT",
                    style = TextStyle(
                        color = ColorProvider(R.color.northstar_cyan),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                )
                Spacer(modifier = GlanceModifier.height(12.dp))
                Text(
                    text = "Merhaba!",
                    style = TextStyle(
                        color = ColorProvider(R.color.northstar_white),
                        fontWeight = FontWeight.Bold,
                        fontSize = 23.sp
                    )
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
                Text(
                    text = "İlk demo mesajı",
                    style = TextStyle(
                        color = ColorProvider(R.color.northstar_muted),
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
}

class NorthStarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NorthStarWidget()
}
