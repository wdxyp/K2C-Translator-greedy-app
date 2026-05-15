package com.example.k2ctranslator.translator

import java.io.File
import java.nio.charset.Charset

object TextIO {
    private val candidates: List<Charset> = listOf(
        Charsets.UTF_8,
        Charset.forName("UTF-16LE"),
        Charset.forName("UTF-16BE"),
        Charset.forName("GBK"),
    )

    fun decode(bytes: ByteArray): String {
        var last: Throwable? = null
        for (cs in candidates) {
            try {
                return String(bytes, cs)
            } catch (t: Throwable) {
                last = t
            }
        }
        if (last != null) throw last
        return String(bytes)
    }

    fun readText(file: File): String {
        return decode(file.readBytes())
    }

    fun readLines(file: File): List<String> {
        return readText(file).split('\n').map { it.trimEnd('\r') }
    }
}

