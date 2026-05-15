package com.example.k2ctranslator.supabase

import android.content.Context
import com.example.k2ctranslator.BuildConfig
import org.json.JSONObject

data class CloudAuthResult(val ok: Boolean, val message: String, val session: SupabaseSession? = null)

object SupabaseAuth {
    private fun baseUrl(): String {
        return BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    }

    private fun anonKey(): String {
        return BuildConfig.SUPABASE_ANON_KEY.trim()
    }

    fun isConfigured(): Boolean {
        return baseUrl().isNotEmpty() && anonKey().isNotEmpty()
    }

    fun currentSession(context: Context): SupabaseSession? {
        return SupabaseSessionStore.load(context)
    }

    fun logout(context: Context) {
        SupabaseSessionStore.clear(context)
    }

    fun signUpEmail(context: Context, emailRaw: String, password: String): CloudAuthResult {
        if (!isConfigured()) return CloudAuthResult(false, "Supabase 未配置")
        val email = emailRaw.trim()
        if (email.isEmpty()) return CloudAuthResult(false, "请输入邮箱")
        if (password.length < 6) return CloudAuthResult(false, "密码至少 6 位")

        val url = baseUrl() + "/auth/v1/signup"
        val body = JSONObject()
        body.put("email", email)
        body.put("password", password)

        val headers = mapOf(
            "apikey" to anonKey(),
            "Authorization" to "Bearer ${anonKey()}",
        )
        val res = HttpJson.postJson(url, body, headers)
        if (res.code !in 200..299) {
            return CloudAuthResult(false, parseError(res.body) ?: "注册失败（${res.code}）")
        }
        val session = parseSession(res.body) ?: return CloudAuthResult(false, "注册失败：无会话信息")
        SupabaseSessionStore.save(context, session)
        return CloudAuthResult(true, "注册成功", session)
    }

    fun signInEmail(context: Context, emailRaw: String, password: String): CloudAuthResult {
        if (!isConfigured()) return CloudAuthResult(false, "Supabase 未配置")
        val email = emailRaw.trim()
        if (email.isEmpty()) return CloudAuthResult(false, "请输入邮箱")
        if (password.isEmpty()) return CloudAuthResult(false, "请输入密码")

        val url = baseUrl() + "/auth/v1/token?grant_type=password"
        val body = JSONObject()
        body.put("email", email)
        body.put("password", password)

        val headers = mapOf(
            "apikey" to anonKey(),
            "Authorization" to "Bearer ${anonKey()}",
        )
        val res = HttpJson.postJson(url, body, headers)
        if (res.code !in 200..299) {
            return CloudAuthResult(false, parseError(res.body) ?: "登录失败（${res.code}）")
        }
        val session = parseSession(res.body) ?: return CloudAuthResult(false, "登录失败：无会话信息")
        SupabaseSessionStore.save(context, session)
        return CloudAuthResult(true, "登录成功", session)
    }

    fun ensureValidSession(context: Context): SupabaseSession? {
        val s = SupabaseSessionStore.load(context) ?: return null
        val now = System.currentTimeMillis() / 1000
        if (s.expiresAt - now > 60) return s
        return refresh(context, s.refreshToken)
    }

    private fun refresh(context: Context, refreshToken: String): SupabaseSession? {
        if (!isConfigured()) return null
        val url = baseUrl() + "/auth/v1/token?grant_type=refresh_token"
        val body = JSONObject()
        body.put("refresh_token", refreshToken)
        val headers = mapOf(
            "apikey" to anonKey(),
            "Authorization" to "Bearer ${anonKey()}",
        )
        val res = HttpJson.postJson(url, body, headers)
        if (res.code !in 200..299) return null
        val session = parseSession(res.body) ?: return null
        SupabaseSessionStore.save(context, session)
        return session
    }

    private fun parseSession(json: String): SupabaseSession? {
        return try {
            val obj = JSONObject(json)
            val accessToken = obj.getString("access_token")
            val refreshToken = obj.getString("refresh_token")
            val expiresIn = obj.optLong("expires_in", 0L)
            val now = System.currentTimeMillis() / 1000
            val expiresAt = if (expiresIn > 0) now + expiresIn else obj.optLong("expires_at", now)
            val user = obj.optJSONObject("user")
            val userId = user?.optString("id", "") ?: ""
            val email = user?.optString("email")?.takeIf { it.isNotBlank() }
            if (userId.isEmpty()) return null
            SupabaseSession(accessToken, refreshToken, expiresAt, userId, email)
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseError(body: String): String? {
        return try {
            val obj = JSONObject(body)
            obj.optString("msg").takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: obj.optString("error_description").takeIf { it.isNotBlank() }
                ?: obj.optString("error").takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}
