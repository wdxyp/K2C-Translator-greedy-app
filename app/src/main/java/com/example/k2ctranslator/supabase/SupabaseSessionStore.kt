package com.example.k2ctranslator.supabase

import android.content.Context
import org.json.JSONObject

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String?,
)

object SupabaseSessionStore {
    private const val PREF = "supabase_session"
    private const val KEY = "session_json"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(context: Context): SupabaseSession? {
        val raw = prefs(context).getString(KEY, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            SupabaseSession(
                accessToken = obj.getString("access_token"),
                refreshToken = obj.getString("refresh_token"),
                expiresAt = obj.getLong("expires_at"),
                userId = obj.getString("user_id"),
                email = obj.optString("email").takeIf { it.isNotBlank() },
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun save(context: Context, s: SupabaseSession) {
        val obj = JSONObject()
        obj.put("access_token", s.accessToken)
        obj.put("refresh_token", s.refreshToken)
        obj.put("expires_at", s.expiresAt)
        obj.put("user_id", s.userId)
        if (s.email != null) obj.put("email", s.email)
        prefs(context).edit().putString(KEY, obj.toString()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
