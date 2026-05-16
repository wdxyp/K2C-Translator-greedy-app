package com.example.k2ctranslator.appupdate

import android.content.Context
import com.example.k2ctranslator.supabase.HttpJson
import java.io.File

data class ApkDownloadResult(
    val ok: Boolean,
    val message: String,
    val file: File? = null,
)

object AppApkDownloader {
    fun downloadToCache(context: Context, url: String, versionCode: Int, versionName: String): ApkDownloadResult {
        val u = url.trim()
        if (u.isBlank()) return ApkDownloadResult(false, "下载地址为空")

        val safeV = versionName.trim().ifBlank { versionCode.toString() }
            .replace(Regex("""[^0-9A-Za-z._-]"""), "_")
        val out = File(context.cacheDir, "app_update_${versionCode}_${safeV}_${System.currentTimeMillis()}.apk")

        val (code, contentType) = try {
            HttpJson.downloadToFile(u, out)
        } catch (t: Throwable) {
            if (out.exists()) out.delete()
            return ApkDownloadResult(false, "下载失败：${t::class.java.simpleName}")
        }

        if (code !in 200..299) {
            if (out.exists()) out.delete()
            return ApkDownloadResult(false, "下载失败（$code）")
        }

        val sig = try {
            out.inputStream().use { ins ->
                val b = ByteArray(2)
                val n = ins.read(b)
                if (n < 2) ByteArray(0) else b
            }
        } catch (_: Throwable) {
            ByteArray(0)
        }
        if (sig.size < 2 || sig[0] != 'P'.code.toByte() || sig[1] != 'K'.code.toByte()) {
            if (out.exists()) out.delete()
            val hint = if (contentType.contains("text/html", ignoreCase = true)) {
                "下载返回的不是 APK（疑似网页/跳转页）"
            } else {
                "下载返回的不是 APK"
            }
            return ApkDownloadResult(false, hint)
        }

        return ApkDownloadResult(true, "OK", out)
    }
}
