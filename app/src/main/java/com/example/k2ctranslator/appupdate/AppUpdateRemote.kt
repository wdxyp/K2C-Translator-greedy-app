package com.example.k2ctranslator.appupdate

import com.example.k2ctranslator.BuildConfig
import com.example.k2ctranslator.supabase.HttpJson
import org.json.JSONObject

data class AppLatest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

object AppUpdateRemote {
    fun fetchLatest(): AppLatest? {
        val url = BuildConfig.APP_LATEST_JSON_URL.trim()
        if (url.isBlank()) return null
        val res = HttpJson.get(url)
        if (res.code !in 200..299) return null
        val obj = JSONObject(res.body)
        val versionCode = obj.optInt("versionCode", -1)
        val versionName = obj.optString("versionName", "")
        val apkUrl = obj.optString("apkUrl", "")
        val notes = obj.optString("notes", "")
        if (versionCode <= 0 || apkUrl.isBlank()) return null
        return AppLatest(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            notes = notes,
        )
    }
}

