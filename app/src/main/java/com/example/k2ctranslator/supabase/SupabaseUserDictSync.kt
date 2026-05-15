package com.example.k2ctranslator.supabase

import android.content.Context
import com.example.k2ctranslator.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

data class UserDictSyncResult(val ok: Boolean, val message: String)

object SupabaseUserDictSync {
    private val tables = listOf("user_dicts", "user_dicts")

    fun upload(context: Context, content: String): UserDictSyncResult {
        if (!SupabaseAuth.isConfigured()) return UserDictSyncResult(false, "Supabase 未配置")
        val session = SupabaseAuth.ensureValidSession(context) ?: return UserDictSyncResult(false, "请先邮箱登录")

        val headers = mapOf(
            "apikey" to BuildConfig.SUPABASE_ANON_KEY.trim(),
            "Authorization" to "Bearer ${session.accessToken}",
            "Content-Type" to "application/json",
            "Prefer" to "resolution=merge-duplicates,return=minimal",
        )
        val row = JSONObject()
        row.put("user_id", session.userId)
        row.put("content", content)
        val arr = JSONArray().put(row)
        for (table in tables) {
            val url = BuildConfig.SUPABASE_URL.trim().trimEnd('/') + "/rest/v1/$table?on_conflict=user_id"
            val res = HttpJson.postBytes(url, arr.toString().toByteArray(Charsets.UTF_8), headers)
            if (res.code in 200..299) return UserDictSyncResult(true, "已上传到云端")
        }
        val lastUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/') + "/rest/v1/${tables.first()}?on_conflict=user_id"
        val lastRes = HttpJson.postBytes(lastUrl, arr.toString().toByteArray(Charsets.UTF_8), headers)
        return UserDictSyncResult(false, "上传失败（${lastRes.code}）：${lastRes.body.take(200)}")
    }

    fun download(context: Context): Pair<String?, UserDictSyncResult> {
        if (!SupabaseAuth.isConfigured()) return Pair(null, UserDictSyncResult(false, "Supabase 未配置"))
        val session = SupabaseAuth.ensureValidSession(context) ?: return Pair(null, UserDictSyncResult(false, "请先邮箱登录"))

        val headers = mapOf(
            "apikey" to BuildConfig.SUPABASE_ANON_KEY.trim(),
            "Authorization" to "Bearer ${session.accessToken}",
        )
        for (table in tables) {
            val url = BuildConfig.SUPABASE_URL.trim().trimEnd('/') +
                "/rest/v1/$table?select=content&user_id=eq.${session.userId}&limit=1"
            val res = HttpJson.get(url, headers)
            if (res.code !in 200..299) continue
            return try {
                val arr = JSONArray(res.body)
                val obj = arr.optJSONObject(0) ?: return Pair(null, UserDictSyncResult(false, "云端暂无词典"))
                val content = obj.optString("content", "")
                Pair(content, UserDictSyncResult(true, "已从云端下载"))
            } catch (t: Throwable) {
                Pair(null, UserDictSyncResult(false, "解析失败：${t::class.java.simpleName}"))
            }
        }
        val lastUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/') +
            "/rest/v1/${tables.first()}?select=content&user_id=eq.${session.userId}&limit=1"
        val lastRes = HttpJson.get(lastUrl, headers)
        return Pair(null, UserDictSyncResult(false, "下载失败（${lastRes.code}）：${lastRes.body.take(200)}"))
    }
}

