package com.northstar.connect

import android.content.Context
import java.util.UUID

object SyncConfig {
    private const val PREFS = "northstar_sync"
    // Personal Tailscale Funnel endpoint used automatically when no server is configured.
    const val DEFAULT_SERVER_URL = "https://melih-uzun.taild8cc3a.ts.net"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun serverUrl(context: Context): String {
        val configured = prefs(context).getString("server_url", "")?.trim()?.trimEnd('/').orEmpty()
        return configured.ifBlank { DEFAULT_SERVER_URL }
    }

    fun roomCode(context: Context): String =
        prefs(context).getString("room_code", "")?.trim()?.uppercase() ?: ""

    fun deviceId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString("device_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        p.edit().putString("device_id", created).apply()
        return created
    }

    fun save(context: Context, serverUrl: String, roomCode: String) {
        val normalizedServer = serverUrl.trim().trimEnd('/').ifBlank { DEFAULT_SERVER_URL }
        val normalizedRoom = roomCode.trim().uppercase()
        val p = prefs(context)
        val roomChanged = p.getString("room_code", "")?.uppercase() != normalizedRoom ||
            p.getString("server_url", "")?.trim()?.trimEnd('/') != normalizedServer
        val editor = p.edit()
            .putString("server_url", normalizedServer)
            .putString("room_code", normalizedRoom)
        if (roomChanged) {
            editor.remove("fallback_initialized_room")
                .remove("fallback_event_id")
                .remove("fallback_message_id")
                .remove("widget_event_id")
        }
        editor.apply()
    }
}
