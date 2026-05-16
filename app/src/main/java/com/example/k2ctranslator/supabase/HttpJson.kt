package com.example.k2ctranslator.supabase

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val code: Int, val body: String)

object HttpJson {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        return read(conn)
    }

    fun postJson(url: String, json: JSONObject, headers: Map<String, String> = emptyMap()): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        return read(conn)
    }

    fun postBytes(url: String, bytes: ByteArray, headers: Map<String, String> = emptyMap()): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = true
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        return read(conn)
    }

    fun download(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val bytes = readAll(stream)
        return Pair(code, bytes)
    }

    fun downloadToFile(url: String, outFile: File, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        if (outFile.exists()) outFile.delete()
        outFile.parentFile?.mkdirs()
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val code = conn.responseCode
        val contentType = conn.getHeaderField("Content-Type") ?: ""
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        if (code !in 200..299) {
            stream.close()
            return Pair(code, contentType)
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
        return Pair(code, contentType)
    }

    private fun read(conn: HttpURLConnection): HttpResult {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val body = String(readAll(stream), Charsets.UTF_8)
        return HttpResult(code, body)
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
}
