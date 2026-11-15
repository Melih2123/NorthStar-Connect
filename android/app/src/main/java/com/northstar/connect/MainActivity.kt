package com.northstar.connect

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.view.Gravity
import android.graphics.Color
import android.widget.LinearLayout

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "NORTHSTAR CONNECT"
            textSize = 22f
            setTextColor(Color.rgb(35, 220, 245))
        }

        val body = TextView(this).apply {
            text = "Widget prototipi kuruldu.\n\nAna ekrana uzun bas → Widget'lar → NorthStar Connect.\n\nBu ilk sürüm yerel demo mesajını gösterir. Cihazlar arası senkronizasyon sonraki aşamada eklenecek."
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 0)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(5, 17, 26))
            addView(title)
            addView(body)
        }

        setContentView(layout)
    }
}
