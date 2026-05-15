package com.example.k2ctranslator.translator

import java.io.File

data class UserDictData(
    val tokenOverrides: Map<String, List<String>>,
    val directTranslations: Map<String, String>,
    val replaceRules: Map<String, String>,
    val glossary: Map<String, String>,
    val modelOnlyTerms: Set<String>,
    val knownTerms: Set<String>,
)

object UserDict {
    private val cleanRe = Regex("[^\\w\\s\\uAC00-\\uD7A3\\u4e00-\\u9fa5]")

    fun cleanText(s: String): String {
        return cleanRe.replace(s, "").trim()
    }

    private fun buildKnownTerms(
        tokenOverrides: Map<String, List<String>>,
        directTranslations: Map<String, String>,
        glossary: Map<String, String>,
        modelOnlyTerms: Set<String>,
    ): Set<String> {
        val out = HashSet<String>()
        for (k in glossary.keys) {
            val ck = cleanText(k)
            if (ck.isNotEmpty()) out.add(ck)
        }
        for (k in directTranslations.keys) {
            val ck = cleanText(k)
            if (ck.isNotEmpty()) out.add(ck)
        }
        for (k in tokenOverrides.keys) {
            val ck = cleanText(k)
            if (ck.isNotEmpty()) out.add(ck)
        }
        for (k in modelOnlyTerms) {
            val ck = cleanText(k)
            if (ck.isNotEmpty()) out.add(ck)
        }
        return out
    }

    fun loadFromFile(path: String): UserDictData {
        val file = File(path)
        val tokenOverrides = LinkedHashMap<String, List<String>>()
        val directTranslations = LinkedHashMap<String, String>()
        val replaceRules = LinkedHashMap<String, String>()
        val glossary = LinkedHashMap<String, String>()
        val modelOnlyTerms = LinkedHashSet<String>()

        if (!file.exists()) {
            val known = buildKnownTerms(tokenOverrides, directTranslations, glossary, modelOnlyTerms)
            return UserDictData(tokenOverrides, directTranslations, replaceRules, glossary, modelOnlyTerms, known)
        }

        var section: String? = "glossary"
        file.readLines(Charsets.UTF_8).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("##")) {
                val header = line.trimStart('#').trim()
                section = when {
                    header.contains("分词") -> "tokenize"
                    header.contains("直译") -> "translate"
                    header.contains("替换") -> "replace"
                    header.contains("术语") -> "glossary"
                    else -> null
                }
                return@forEach
            }

            if (line.startsWith("#")) {
                val header = line.trimStart('#').trim()
                if (header.contains("术语")) section = "glossary"
                return@forEach
            }

            if (!line.startsWith("- ")) return@forEach
            val content = line.substring(2)
            val ascii = content.indexOf(':')
            val full = content.indexOf('：')
            val sepPos = listOf(ascii, full).filter { it >= 0 }.minOrNull()
            if (sepPos == null) {
                val rawKo = content.trim()
                val onlyKo = cleanText(rawKo)
                if (onlyKo.isNotEmpty()) {
                    modelOnlyTerms.add(onlyKo)
                    modelOnlyTerms.add(rawKo)
                    if (!glossary.containsKey(onlyKo)) glossary[onlyKo] = ""
                    if (rawKo.isNotEmpty() && rawKo != onlyKo && !glossary.containsKey(rawKo)) glossary[rawKo] = ""
                }
                return@forEach
            }

            val left = content.substring(0, sepPos).trim()
            val right = content.substring(sepPos + 1).trim()
            if (left.isEmpty() || right.isEmpty()) return@forEach

            when (section) {
                "tokenize" -> tokenOverrides[cleanText(left)] = right.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                "translate" -> directTranslations[cleanText(left)] = right
                "replace" -> replaceRules[left] = right
                "glossary" -> {
                    val parts = right.split(':', '：').map { it.trim() }.filter { it.isNotEmpty() }
                    val wrongZh: String
                    val correctZh: String
                    if (parts.size >= 2) {
                        wrongZh = parts[0]
                        correctZh = parts[1]
                    } else {
                        wrongZh = parts[0]
                        correctZh = parts[0]
                    }
                    val koTerm = left
                    glossary[koTerm] = correctZh
                    val koClean = cleanText(koTerm)
                    if (koClean.isNotEmpty() && koClean != koTerm) glossary[koClean] = correctZh
                    if (wrongZh.isNotEmpty()) replaceRules[wrongZh] = correctZh
                }
            }
        }

        val known = buildKnownTerms(tokenOverrides, directTranslations, glossary, modelOnlyTerms)
        return UserDictData(tokenOverrides, directTranslations, replaceRules, glossary, modelOnlyTerms, known)
    }
}

