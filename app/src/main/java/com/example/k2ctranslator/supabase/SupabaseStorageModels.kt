package com.example.k2ctranslator.supabase

import com.example.k2ctranslator.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class LatestModelInfo(
    val modelVersion: String,
    val zipPath: String,
    val zipSha256: String,
    val notes: String?,
)

object SupabaseStorageModels {
    private fun baseUrl(): String = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private fun bucket(): String = BuildConfig.SUPABASE_STORAGE_BUCKET.trim().ifEmpty { "k2c-models" }

    fun isConfigured(): Boolean = baseUrl().isNotEmpty()

    fun latestJsonUrl(): String {
        return "${baseUrl()}/storage/v1/object/public/${bucket()}/latest.json"
    }

    fun objectPublicUrl(path: String): String {
        val p = path.trim().trimStart('/')
        return "${baseUrl()}/storage/v1/object/public/${bucket()}/$p"
    }

    fun fetchLatest(): Pair<LatestModelInfo?, String?> {
        if (!isConfigured()) return Pair(null, "Supabase 未配置")
        val res = HttpJson.get(latestJsonUrl())
        if (res.code !in 200..299) return Pair(null, "读取 latest.json 失败（${res.code}）")
        return try {
            val obj = JSONObject(res.body)
            val v = obj.getString("modelVersion")
            val zipPath = obj.getString("zipPath")
            val sha = obj.getString("zipSha256")
            val notes = obj.optString("notes").takeIf { it.isNotBlank() }
            Pair(LatestModelInfo(v, zipPath, sha, notes), null)
        } catch (t: Throwable) {
            Pair(null, "latest.json 格式错误：${t::class.java.simpleName}")
        }
    }

    fun downloadZip(cacheDir: File, zipPath: String): Pair<File?, String?> {
        val url = objectPublicUrl(zipPath)
        val (code, bytes) = HttpJson.download(url)
        if (code !in 200..299) return Pair(null, "下载失败（${code}）")
        val out = File(cacheDir, "download_model_bundle.zip")
        out.outputStream().use { it.write(bytes) }
        return Pair(out, null)
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val dig = md.digest()
        val sb = StringBuilder(dig.size * 2)
        for (b in dig) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}

