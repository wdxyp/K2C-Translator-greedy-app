package com.example.k2ctranslator.log

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TranslationLogEntry(
    val timestampMs: Long,
    val durationMs: Long,
    val inputChars: Int,
    val outputChars: Int,
    val unkCount: Int,
    val modelLabel: String,
    val inputText: String,
    val outputText: String,
)

object TranslationLogStorage {
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private const val HEADER = "timestamp,timestamp_local,duration_ms,input_chars,output_chars,unk_count,model,input_text,output_text"

    fun logFile(context: Context): File {
        return File(context.filesDir, "logs/translations.csv")
    }

    fun append(context: Context, entry: TranslationLogEntry) {
        val f = logFile(context)
        f.parentFile?.mkdirs()
        ensureHeader(f)
        FileOutputStream(f, true).buffered().use { os ->
            val tsLocal = dateFmt.format(Date(entry.timestampMs))
            val row = buildString {
                append(entry.timestampMs)
                append(',')
                append(csvEscape(tsLocal))
                append(',')
                append(entry.durationMs)
                append(',')
                append(entry.inputChars)
                append(',')
                append(entry.outputChars)
                append(',')
                append(entry.unkCount)
                append(',')
                append(csvEscape(entry.modelLabel))
                append(',')
                append(csvEscape(entry.inputText))
                append(',')
                append(csvEscape(entry.outputText))
                append('\n')
            }
            os.write(row.toByteArray(Charsets.UTF_8))
        }
        trimToMaxLines(f, 2000)
    }

    fun readLastLines(context: Context, maxLines: Int = 50): List<String> {
        val f = logFile(context)
        if (!f.exists()) return emptyList()
        val lines = f.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return emptyList()
        val data = if (lines.firstOrNull()?.startsWith("timestamp,") == true) lines.drop(1) else lines
        return if (data.size <= maxLines) data else data.takeLast(maxLines)
    }

    fun readLastEntries(context: Context, maxLines: Int = 50): List<TranslationLogEntry> {
        val lines = readLastLines(context, maxLines)
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<TranslationLogEntry>()
        for (ln in lines) {
            val parts = parseCsvLine(ln)
            if (parts.size < 8) continue
            val ts = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val duration = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val inChars = parts.getOrNull(3)?.toIntOrNull() ?: 0
            val outChars = parts.getOrNull(4)?.toIntOrNull() ?: 0
            val unk = parts.getOrNull(5)?.toIntOrNull() ?: 0
            val model = parts.getOrNull(6) ?: ""
            val inputText = parts.getOrNull(7) ?: ""
            val outputText = parts.getOrNull(8) ?: ""
            out.add(
                TranslationLogEntry(
                    timestampMs = ts,
                    durationMs = duration,
                    inputChars = inChars,
                    outputChars = outChars,
                    unkCount = unk,
                    modelLabel = model,
                    inputText = inputText,
                    outputText = outputText,
                ),
            )
        }
        return out
    }

    fun readAllBytesWithUtf8Bom(context: Context): ByteArray? {
        val f = logFile(context)
        if (!f.exists()) return null
        ensureHeader(f)
        val content = f.readBytes()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        return bom + content
    }

    fun clear(context: Context) {
        val f = logFile(context)
        if (f.exists()) f.delete()
    }

    fun summarize(lines: List<String>): Summary {
        var count = 0
        var totalChars = 0
        var totalDuration = 0L
        var totalUnk = 0
        for (ln in lines) {
            val parts = parseCsvLine(ln)
            if (parts.size < 8) continue
            count += 1
            totalDuration += parts[2].toLongOrNull() ?: 0L
            totalChars += parts[3].toIntOrNull() ?: 0
            totalUnk += parts[5].toIntOrNull() ?: 0
        }
        return Summary(count, totalChars, totalDuration, totalUnk)
    }

    data class Summary(
        val count: Int,
        val totalInputChars: Int,
        val totalDurationMs: Long,
        val totalUnkCount: Int,
    )

    private fun csvEscape(s: String): String {
        val needs = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
        if (!needs) return s
        return "\"" + s.replace("\"", "\"\"") + "\""
    }

    private fun ensureHeader(f: File) {
        if (!f.exists() || f.length() == 0L) {
            f.writeText(HEADER + "\n", Charsets.UTF_8)
            return
        }
        val first = try {
            f.bufferedReader(Charsets.UTF_8).use { it.readLine() }
        } catch (_: Throwable) {
            null
        }
        if (first == null) {
            f.writeText(HEADER + "\n", Charsets.UTF_8)
            return
        }
        if (first.startsWith("timestamp,")) return
        val existing = try {
            f.readText(Charsets.UTF_8).trimEnd()
        } catch (_: Throwable) {
            ""
        }
        if (existing.isBlank()) {
            f.writeText(HEADER + "\n", Charsets.UTF_8)
        } else {
            f.writeText(HEADER + "\n" + existing + "\n", Charsets.UTF_8)
        }
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

    private fun trimToMaxLines(f: File, maxLines: Int) {
        if (!f.exists()) return
        val lines = f.readLines(Charsets.UTF_8)
        if (lines.isEmpty()) return
        val hasHeader = lines.firstOrNull()?.startsWith("timestamp,") == true
        val data = if (hasHeader) lines.drop(1) else lines
        if (data.size <= maxLines) return
        val header = if (hasHeader) lines.first() else HEADER
        val tail = data.takeLast(maxLines)
        val out = StringBuilder()
        out.append(header).append('\n')
        for (ln in tail) out.append(ln).append('\n')
        f.writeText(out.toString(), Charsets.UTF_8)
    }
}
