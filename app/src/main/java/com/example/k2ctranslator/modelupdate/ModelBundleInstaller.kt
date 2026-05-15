package com.example.k2ctranslator.modelupdate

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

object ModelBundleInstaller {
    fun installZipUri(context: Context, uri: Uri): String {
        val tmpZip = File(context.cacheDir, "download_model_bundle.zip")
        if (tmpZip.exists()) tmpZip.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmpZip.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取下载文件")
        return installZipFile(context, tmpZip)
    }

    fun installZipFile(context: Context, zip: File): String {
        val targetDir = File(context.filesDir, "translator")
        targetDir.mkdirs()
        ZipFile(zip).use { zf ->
            val required = listOf(
                "manifest.json",
                "encoder.ptl",
                "decoder.ptl",
                "ko_vocab.json",
                "zh_ivocab.json",
                "config.json",
            )
            for (name in required) {
                if (zf.getEntry(name) == null) throw IllegalStateException("模型包缺少文件：$name")
            }

            fun extract(name: String) {
                val e = zf.getEntry(name) ?: return
                val out = File(targetDir, name)
                val tmp = File(targetDir, "$name.tmp")
                if (tmp.exists()) tmp.delete()
                zf.getInputStream(e).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
            }

            for (name in required) extract(name)
        }
        val manifestFile = File(File(context.filesDir, "translator"), "manifest.json")
        return try {
            JSONObject(manifestFile.readText(Charsets.UTF_8)).optString("modelVersion").ifBlank { "custom" }
        } catch (_: Throwable) {
            "custom"
        }
    }
}
