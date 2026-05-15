package com.example.k2ctranslator.translator

import android.content.Context
import java.io.File

object UserDictStorage {
    fun userDictFile(context: Context, baseAssetDir: String = "translator"): File {
        return File(context.filesDir, "$baseAssetDir/user_dict.md")
    }

    fun readUserDict(context: Context, baseAssetDir: String = "translator"): String {
        val file = userDictFile(context, baseAssetDir)
        if (!file.exists()) return ""
        return TextIO.readText(file)
    }

    fun writeUserDict(context: Context, content: String, baseAssetDir: String = "translator") {
        val file = userDictFile(context, baseAssetDir)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    fun resetToDefault(context: Context, baseAssetDir: String = "translator") {
        val bytes = context.assets.open("$baseAssetDir/user_dict.md").use { it.readBytes() }
        val content = TextIO.decode(bytes)
        writeUserDict(context, content, baseAssetDir)
    }
}
