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
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.text.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class NorthStarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // v0.1: local demo content. Remote drawing sync will replace this.
        val sender = "NORTHSTAR CONNECT"
        val message = "Merhaba!"
        val timestamp = "İlk demo mesajı"

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(android.graphics.Color.rgb(7, 23, 34)))
                    .padding(14.dp)
            ) {
                Text(
                    text = sender,
                    style = TextStyle(
                        color = ColorProvider(android.graphics.Color.rgb(65, 225, 245)),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                )
                Spacer(modifier = GlanceModifier.height(12.dp))
                Text(
                    text = message,
                    style = TextStyle(
                        color = ColorProvider(android.graphics.Color.WHITE),
                        fontWeight = FontWeight.Bold,
                        fontSize = 23.sp
                    )
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
                Text(
                    text = timestamp,
                    style = TextStyle(
                        color = ColorProvider(android.graphics.Color.LTGRAY),
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
