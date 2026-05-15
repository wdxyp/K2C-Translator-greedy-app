package com.example.k2ctranslator.modelupdate

import android.content.Context
import org.json.JSONObject

data class ModelDownloadJob(
    val downloadId: Long,
    val modelVersion: String,
    val zipUrl: String,
    val zipSha256: String,
)

object ModelDownloadStore {
    private const val PREF = "model_download_store"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun save(context: Context, job: ModelDownloadJob) {
        val obj = JSONObject()
        obj.put("downloadId", job.downloadId)
        obj.put("modelVersion", job.modelVersion)
        obj.put("zipUrl", job.zipUrl)
        obj.put("zipSha256", job.zipSha256)
        prefs(context).edit().putString("job_${job.downloadId}", obj.toString()).apply()
    }

    fun load(context: Context, downloadId: Long): ModelDownloadJob? {
        val raw = prefs(context).getString("job_$downloadId", null) ?: return null
        return try {
            val obj = JSONObject(raw)
            ModelDownloadJob(
                downloadId = obj.optLong("downloadId", downloadId),
                modelVersion = obj.optString("modelVersion", ""),
                zipUrl = obj.optString("zipUrl", ""),
                zipSha256 = obj.optString("zipSha256", ""),
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun remove(context: Context, downloadId: Long) {
        prefs(context).edit().remove("job_$downloadId").apply()
    }
}

