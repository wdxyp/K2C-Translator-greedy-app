package com.example.k2ctranslator.supabase

import android.content.Context
import com.example.k2ctranslator.BuildConfig
import com.example.k2ctranslator.log.TranslationLogStorage
import org.json.JSONArray
import org.json.JSONObject

data class LogSyncResult(val ok: Boolean, val message: String, val uploaded: Int = 0)
data class LogExportResult(val ok: Boolean, val message: String, val csvBytes: ByteArray? = null)

object SupabaseLogSync {
    private const val PREF = "supabase_log_sync"
    private const val KEY_LAST_TS = "last_ts"

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun sync(context: Context): LogSyncResult {
        if (!SupabaseAuth.isConfigured()) return LogSyncResult(false, "Supabase 未配置")
        val session = SupabaseAuth.ensureValidSession(context) ?: return LogSyncResult(false, "请先邮箱登录")

        val lastTs = prefs(context).getLong(KEY_LAST_TS, 0L)
        val lines = TranslationLogStorage.readLastLines(context, Int.MAX_VALUE)
        if (lines.isEmpty()) return LogSyncResult(true, "暂无记录", 0)

        val toUpload = ArrayList<JSONObject>()
        var maxTs = lastTs
        for (ln in lines) {
            val parts = parseCsvLine(ln)
            if (parts.size < 8) continue
            val ts = parts[0].toLongOrNull() ?: continue
            if (ts <= lastTs) continue
            val duration = parts[2].toLongOrNull() ?: 0L
            val inChars = parts[3].toIntOrNull() ?: 0
            val outChars = parts[4].toIntOrNull() ?: 0
            val unk = parts[5].toIntOrNull() ?: 0
            val model = parts[6]
            val inputText = parts.getOrNull(7) ?: ""
            val outputText = parts.getOrNull(8) ?: ""

            val row = JSONObject()
            row.put("user_id", session.userId)
            row.put("timestamp_ms", ts)
            row.put("duration_ms", duration)
            row.put("input_chars", inChars)
            row.put("output_chars", outChars)
            row.put("unk_count", unk)
            row.put("model_version", model)
            row.put("input_text", inputText)
            row.put("output_text", outputText)
            toUpload.add(row)
            if (ts > maxTs) maxTs = ts
        }

        if (toUpload.isEmpty()) return LogSyncResult(true, "无需同步", 0)

        val url = BuildConfig.SUPABASE_URL.trim().trimEnd('/') + "/rest/v1/translation_logs?on_conflict=user_id,timestamp_ms"
        val headers = mapOf(
            "apikey" to BuildConfig.SUPABASE_ANON_KEY.trim(),
            "Authorization" to "Bearer ${session.accessToken}",
            "Content-Type" to "application/json",
            "Prefer" to "resolution=merge-duplicates,return=minimal",
        )
        val arr = JSONArray()
        for (o in toUpload) arr.put(o)
        val bodyBytes = arr.toString().toByteArray(Charsets.UTF_8)
        val res = HttpJson.postBytes(url, bodyBytes, headers)
        if (res.code !in 200..299) {
            return LogSyncResult(false, "同步失败（${res.code}）：${res.body.take(200)}", 0)
        }

        prefs(context).edit().putLong(KEY_LAST_TS, maxTs).apply()
        return LogSyncResult(true, "同步成功", toUpload.size)
    }

    fun exportCsvRange(context: Context, startMs: Long, endMs: Long): LogExportResult {
        if (!SupabaseAuth.isConfigured()) return LogExportResult(false, "Supabase 未配置")
        val session = SupabaseAuth.ensureValidSession(context) ?: return LogExportResult(false, "请先邮箱登录")
        if (endMs < startMs) return LogExportResult(false, "时间区间不正确")

        val base = BuildConfig.SUPABASE_URL.trim().trimEnd('/') + "/rest/v1/translation_logs"
        val select = "timestamp_ms,duration_ms,input_chars,output_chars,unk_count,model_version,input_text,output_text"
        val url =
            base +
                "?select=$select" +
                "&user_id=eq.${session.userId}" +
                "&timestamp_ms=gte.$startMs" +
                "&timestamp_ms=lte.$endMs" +
                "&order=timestamp_ms.asc"

        val headers = mapOf(
            "apikey" to BuildConfig.SUPABASE_ANON_KEY.trim(),
            "Authorization" to "Bearer ${session.accessToken}",
            "Accept" to "application/json",
        )
        val res = HttpJson.get(url, headers)
        if (res.code !in 200..299) {
            return LogExportResult(false, "获取失败（${res.code}）：${res.body.take(200)}")
        }

        val arr = try {
            JSONArray(res.body)
        } catch (t: Throwable) {
            return LogExportResult(false, "解析失败：${t::class.java.simpleName}")
        }

        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append(
            "timestamp_ms,duration_ms,input_chars,output_chars,unk_count,model_version,input_text,output_text\n"
        )
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ts = o.optLong("timestamp_ms", 0L)
            val dur = o.optLong("duration_ms", 0L)
            val inChars = o.optInt("input_chars", 0)
            val outChars = o.optInt("output_chars", 0)
            val unk = o.optInt("unk_count", 0)
            val model = o.optString("model_version", "")
            val inputText = o.optString("input_text", "")
            val outputText = o.optString("output_text", "")

            sb.append(ts).append(',')
            sb.append(dur).append(',')
            sb.append(inChars).append(',')
            sb.append(outChars).append(',')
            sb.append(unk).append(',')
            sb.append(csvEscape(model)).append(',')
            sb.append(csvEscape(inputText)).append(',')
            sb.append(csvEscape(outputText))
            sb.append('\n')
        }

        return LogExportResult(true, "OK", sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var inQuotes = false
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    val next = i + 1
                    if (next < line.length && line[next] == '"') {
                        sb.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    i += 1
                    continue
                }
                sb.append(c)
                i += 1
                continue
            }
            when (c) {
                ',' -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                    i += 1
                }
                '"' -> {
                    inQuotes = true
                    i += 1
                }
                else -> {
                    sb.append(c)
                    i += 1
                }
            }
        }
        out.add(sb.toString())
        return out
    }

    private fun csvEscape(text: String): String {
        val t = text.replace("\r\n", "\n").replace("\r", "\n")
        val needQuotes = t.contains(',') || t.contains('"') || t.contains('\n')
        val escaped = t.replace("\"", "\"\"")
        return if (needQuotes) "\"$escaped\"" else escaped
    }
}
