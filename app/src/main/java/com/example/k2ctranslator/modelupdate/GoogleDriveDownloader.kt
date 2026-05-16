package com.example.k2ctranslator.modelupdate

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object GoogleDriveDownloader {
    private val confirmRegex = Regex("""confirm=([0-9A-Za-z_-]+)""")
    private val idInUrlRegex = Regex("""[?&]id=([0-9A-Za-z_-]+)""")
    private val filePathRegex = Regex("""/file/d/([0-9A-Za-z_-]+)""")
    private val downloadUrlRegex = Regex("""https?://drive\.usercontent\.google\.com/download\?[^"'<>]+""")
    private val ucUrlRegex = Regex("""https?://drive\.google\.com/uc\?[^"'<>]+""")
    private val ucRelativeRegex = Regex("""href=["'](/uc\?[^"'<>]+)["']""")
    private val downloadRelativeRegex = Regex("""href=["'](/download\?[^"'<>]+)["']""")

    fun download(url: String): Pair<Int, ByteArray> {
        val fileId = extractFileId(url)
        val firstUrl = if (fileId != null) {
            "https://drive.google.com/uc?export=download&id=$fileId"
        } else {
            url
        }
        return downloadWithConfirm(firstUrl)
    }

    data class DownloadToFileResult(
        val code: Int,
        val contentType: String,
        val previewBytes: ByteArray?,
    )

    fun downloadToFile(url: String, outFile: File): DownloadToFileResult {
        val fileId = extractFileId(url)
        val firstUrl = if (fileId != null) {
            "https://drive.google.com/uc?export=download&id=$fileId"
        } else {
            url
        }
        return downloadToFileWithConfirm(firstUrl, outFile)
    }

    private fun extractFileId(url: String): String? {
        val m1 = idInUrlRegex.find(url)?.groupValues?.getOrNull(1)
        if (!m1.isNullOrBlank()) return m1
        val m2 = filePathRegex.find(url)?.groupValues?.getOrNull(1)
        if (!m2.isNullOrBlank()) return m2
        return null
    }

    private fun downloadWithConfirm(initialUrl: String): Pair<Int, ByteArray> {
        val rootFileId = extractFileId(initialUrl)
        var cookie: String? = null
        var url = initialUrl
        repeat(5) {
            val (code, contentType, bytes, nextCookie) = httpGet(url, cookie)
            if (!nextCookie.isNullOrBlank()) cookie = mergeCookies(cookie, nextCookie)
            if (code !in 200..299) return Pair(code, bytes)
            if (!contentType.contains("text/html", ignoreCase = true)) return Pair(code, bytes)

            val html = bytes.toString(Charsets.UTF_8)
            val decoded = html.replace("&amp;", "&")

            val direct = downloadUrlRegex.find(decoded)?.value
                ?: ucUrlRegex.find(decoded)?.value
                ?: ucRelativeRegex.find(decoded)?.groupValues?.getOrNull(1)?.let { "https://drive.google.com$it" }
                ?: downloadRelativeRegex.find(decoded)?.groupValues?.getOrNull(1)?.let { "https://drive.usercontent.google.com$it" }
            if (!direct.isNullOrBlank()) {
                url = direct
                return@repeat
            }

            val token = confirmRegex.find(decoded)?.groupValues?.getOrNull(1)
            val fileId = rootFileId ?: extractFileId(url) ?: extractFileId(decoded)
            if (!token.isNullOrBlank() && !fileId.isNullOrBlank()) {
                url = "https://drive.google.com/uc?export=download&confirm=$token&id=$fileId"
                return@repeat
            }

            if (!fileId.isNullOrBlank()) {
                url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                return@repeat
            }

            return Pair(code, bytes)
        }
        val (code, _, bytes, _) = httpGet(url, cookie)
        return Pair(code, bytes)
    }

    private fun downloadToFileWithConfirm(initialUrl: String, outFile: File): DownloadToFileResult {
        if (outFile.exists()) outFile.delete()
        outFile.parentFile?.mkdirs()

        val rootFileId = extractFileId(initialUrl)
        var cookie: String? = null
        var url = initialUrl
        repeat(5) {
            val res = httpGetForDecision(url, cookie, outFile)
            if (!res.cookie.isNullOrBlank()) cookie = mergeCookies(cookie, res.cookie)
            if (res.code !in 200..299) {
                return DownloadToFileResult(res.code, res.contentType, res.previewBytes)
            }
            if (!res.contentType.contains("text/html", ignoreCase = true)) {
                return DownloadToFileResult(res.code, res.contentType, null)
            }

            val html = (res.previewBytes ?: ByteArray(0)).toString(Charsets.UTF_8)
            val decoded = html.replace("&amp;", "&")

            val direct = downloadUrlRegex.find(decoded)?.value
                ?: ucUrlRegex.find(decoded)?.value
                ?: ucRelativeRegex.find(decoded)?.groupValues?.getOrNull(1)?.let { "https://drive.google.com$it" }
                ?: downloadRelativeRegex.find(decoded)?.groupValues?.getOrNull(1)?.let { "https://drive.usercontent.google.com$it" }
            if (!direct.isNullOrBlank()) {
                url = direct
                return@repeat
            }

            val token = confirmRegex.find(decoded)?.groupValues?.getOrNull(1)
            val fileId = rootFileId ?: extractFileId(url) ?: extractFileId(decoded)
            if (!token.isNullOrBlank() && !fileId.isNullOrBlank()) {
                url = "https://drive.google.com/uc?export=download&confirm=$token&id=$fileId"
                return@repeat
            }

            if (!fileId.isNullOrBlank()) {
                url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                return@repeat
            }

            return DownloadToFileResult(res.code, res.contentType, res.previewBytes)
        }

        val res = httpGetForDecision(url, cookie, outFile)
        if (res.code in 200..299 && !res.contentType.contains("text/html", ignoreCase = true)) {
            return DownloadToFileResult(res.code, res.contentType, null)
        }
        return DownloadToFileResult(res.code, res.contentType, res.previewBytes)
    }

    private data class HttpGetResult(val code: Int, val contentType: String, val bytes: ByteArray, val cookie: String?)

    private fun httpGet(url: String, cookie: String?): HttpGetResult {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val bytes = readAll(stream)
        val contentType = conn.getHeaderField("Content-Type") ?: ""
        val setCookies = conn.headerFields["Set-Cookie"]?.filterNotNull().orEmpty()
        val newCookie = setCookies.joinToString("; ") { it.substringBefore(';') }.takeIf { it.isNotBlank() }
        return HttpGetResult(code, contentType, bytes, newCookie)
    }

    private fun mergeCookies(old: String?, newCookie: String): String {
        if (old.isNullOrBlank()) return newCookie
        return old + "; " + newCookie
    }

    private fun readAll(input: InputStream): ByteArray {
        input.use { ins ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    private data class HttpGetForDecisionResult(
        val code: Int,
        val contentType: String,
        val previewBytes: ByteArray?,
        val cookie: String?,
    )

    private fun httpGetForDecision(url: String, cookie: String?, outFile: File): HttpGetForDecisionResult {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
        val code = conn.responseCode
        val contentType = conn.getHeaderField("Content-Type") ?: ""
        val setCookies = conn.headerFields["Set-Cookie"]?.filterNotNull().orEmpty()
        val newCookie = setCookies.joinToString("; ") { it.substringBefore(';') }.takeIf { it.isNotBlank() }

        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        if (code !in 200..299) {
            val preview = readUpTo(stream, 400)
            return HttpGetForDecisionResult(code, contentType, preview, newCookie)
        }

        if (contentType.contains("text/html", ignoreCase = true)) {
            val preview = readUpTo(stream, 1024 * 1024)
            return HttpGetForDecisionResult(code, contentType, preview, newCookie)
        }

        stream.use { ins ->
            outFile.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        return HttpGetForDecisionResult(code, contentType, null, newCookie)
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        input.use { ins ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var remaining = limit
            while (remaining > 0) {
                val n = ins.read(buf, 0, minOf(buf.size, remaining))
                if (n <= 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
            return out.toByteArray()
        }
    }
}
