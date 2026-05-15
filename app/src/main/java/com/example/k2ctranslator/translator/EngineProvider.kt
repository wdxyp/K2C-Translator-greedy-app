package com.example.k2ctranslator.translator

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object EngineProvider {
    private val mutex = Mutex()
    @Volatile
    private var engine: TranslatorEngine? = null

    suspend fun get(context: Context): TranslatorEngine {
        val cur = engine
        if (cur != null) return cur
        return mutex.withLock {
            val cur2 = engine
            if (cur2 != null) return cur2
            val created = TranslatorEngine.create(context.applicationContext)
            engine = created
            created
        }
    }

    suspend fun reset() {
        mutex.withLock {
            engine = null
        }
    }

    fun isModelInstalled(context: Context): Boolean {
        val base = File(context.filesDir, "translator")
        val enc = File(base, "encoder.ptl")
        val dec = File(base, "decoder.ptl")
        val ko = File(base, "ko_vocab.json")
        val zh = File(base, "zh_ivocab.json")
        val cfg = File(base, "config.json")
        return enc.exists() && dec.exists() && ko.exists() && zh.exists() && cfg.exists()
    }

    fun isBundledModelAvailable(context: Context): Boolean {
        return try {
            context.assets.openFd("translator/encoder.ptl").close()
            context.assets.openFd("translator/decoder.ptl").close()
            context.assets.openFd("translator/ko_vocab.json").close()
            context.assets.openFd("translator/zh_ivocab.json").close()
            context.assets.openFd("translator/config.json").close()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isModelAvailable(context: Context): Boolean {
        return isModelInstalled(context) || isBundledModelAvailable(context)
    }

    fun currentModelLabel(context: Context): String {
        if (isModelInstalled(context)) {
            val file = File(context.filesDir, "translator/manifest.json")
            if (!file.exists()) return "custom"
            return try {
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                obj.optString("modelVersion", "custom")
            } catch (_: Throwable) {
                "custom"
            }
        }
        if (isBundledModelAvailable(context)) return "builtin"
        val file = File(context.filesDir, "translator/manifest.json")
        if (!file.exists()) return "未安装"
        return "custom"
    }
}
