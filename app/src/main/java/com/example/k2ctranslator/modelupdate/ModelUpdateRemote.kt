package com.example.k2ctranslator.modelupdate

import com.example.k2ctranslator.BuildConfig
import com.example.k2ctranslator.supabase.HttpJson
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object ModelUpdateRemote {
    fun latestJsonUrl(): String = BuildConfig.MODEL_LATEST_JSON_URL.trim()

    fun fetchLatest(): Pair<LatestModelInfo?, String?> {
        val url = latestJsonUrl()
        if (url.isBlank()) return Pair(null, "未配置 latest.json 地址")
        val res = HttpJson.get(url)
        if (res.code !in 200..299) return Pair(null, "读取 latest.json 失败（${res.code}）")
        return try {
            val obj = JSONObject(res.body)
            val v = obj.getString("modelVersion")
            val zipUrl = obj.optString("zipUrl").ifBlank { obj.optString("zip_path") }.trim()
            val sha = obj.getString("zipSha256")
            val notes = obj.optString("notes").takeIf { it.isNotBlank() }
            if (zipUrl.isBlank()) return Pair(null, "latest.json 缺少 zipUrl")
            Pair(LatestModelInfo(v, zipUrl, sha, notes), null)
        } catch (t: Throwable) {
            Pair(null, "latest.json 格式错误：${t::class.java.simpleName}")
        }
    }

    fun downloadZip(cacheDir: File, zipUrl: String): Pair<File?, String?> {
        val url = zipUrl.trim()
        val isDrive = url.contains("drive.google.com", ignoreCase = true) || url.contains("drive.usercontent.google.com", ignoreCase = true)
        val (code, bytes) = if (isDrive) {
            GoogleDriveDownloader.download(url)
        } else {
            HttpJson.download(url)
        }
        if (code !in 200..299) return Pair(null, "下载失败（${code}）")
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            val preview = try {
                bytes.copyOfRange(0, minOf(bytes.size, 400)).toString(Charsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
            val hint = if (preview.contains("Google Drive", ignoreCase = true) || preview.contains("drive.google.com", ignoreCase = true)) {
                "下载返回的不是 ZIP（疑似 Drive 确认页/权限页）。请把该文件分享权限设为“任何拥有链接的人可查看”，并重试。"
            } else {
                "下载返回的不是 ZIP（长度=${bytes.size}）。"
            }
            return Pair(null, hint)
        }
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
