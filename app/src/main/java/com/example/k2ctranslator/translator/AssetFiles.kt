package com.example.k2ctranslator.translator

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object AssetFiles {
    private fun isValidLitePtl(file: File): Boolean {
        return try {
            ZipFile(file).use {
                it.getEntry("archive/bytecode.pkl") != null ||
                    it.getEntry("bytecode.pkl") != null
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun looksLikeJson(file: File): Boolean {
        return try {
            file.inputStream().buffered().use { input ->
                while (true) {
                    val b = input.read()
                    if (b == -1) return@use false
                    val c = b.toChar()
                    if (c.isWhitespace()) continue
                    return@use (c == '{' || c == '[')
                }
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        val expectedLen = try {
            val len = context.assets.openFd(assetName).length
            if (len > 0L) len else null
        } catch (_: Throwable) {
            null
        }

        if (file.exists() && file.length() > 0L) {
            if (assetName.endsWith(".ptl")) {
                if (isValidLitePtl(file)) return file.absolutePath
            } else if (assetName.endsWith(".json")) {
                if (looksLikeJson(file)) return file.absolutePath
            } else {
                return file.absolutePath
            }
        }

        file.parentFile?.mkdirs()

        val tmp = File(file.parentFile, "${file.name}.tmp")
        if (tmp.exists()) tmp.delete()
        context.assets.open(assetName).use { input ->
            FileOutputStream(tmp).use { output ->
                input.copyTo(output, bufferSize = 1024 * 1024)
                output.flush()
                output.fd.sync()
            }
        }
        if (expectedLen != null && tmp.length() != expectedLen) {
            tmp.delete()
            throw IllegalStateException("Asset copy incomplete for $assetName (expected=$expectedLen, got=${tmp.length()})")
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            FileOutputStream(file).use { output ->
                tmp.inputStream().use { input ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                    output.flush()
                    output.fd.sync()
                }
            }
            tmp.delete()
        }
        if (assetName.endsWith(".ptl") && !isValidLitePtl(file)) {
            file.delete()
            throw IllegalStateException("Invalid lite model archive for $assetName (bytecode.pkl missing)")
        }
        if (assetName.endsWith(".json") && !looksLikeJson(file)) {
            file.delete()
            throw IllegalStateException("Invalid json file for $assetName")
        }
        return file.absolutePath
    }
}
