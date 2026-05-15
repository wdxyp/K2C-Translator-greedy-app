package com.example.k2ctranslator.translator

import android.content.Context
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor

class TranslatorEngine private constructor(
    private val assets: TranslatorAssets,
    private val encoder: Module,
    private val decoder: Module,
    private val userDictPath: String,
) {
    private val koreanBlockRe = Regex("^[\\uAC00-\\uD7A3]+$")
    private val mixedPieceRe = Regex("\\s+|[A-Za-z][A-Za-z0-9/_\\-]*|\\d+(?:\\.\\d+)?%?|[\\uAC00-\\uD7A3]+|.")
    private val tokenDisplayRe = Regex("[A-Za-z][A-Za-z0-9/_\\-]*|[\\uAC00-\\uD7A3]+|\\d+(?:\\.\\d+)?%?")
    private val modelTokenRe = Regex("[A-Za-z][A-Za-z0-9/_\\-]*|\\d+(?:\\.\\d+)?%?|[\\uAC00-\\uD7A3]+")
    private val parenSplitRe = Regex("(\\([^()]*\\)|（[^（）]*）)")
    private val numberParenAsciiRe = Regex("^\\(\\s*\\d+\\s*\\)$")
    private val numberParenFullRe = Regex("^（\\s*\\d+\\s*）$")

    private val simpleParticles = setOf("이", "의", "는", "를")

    @Volatile
    private var cachedUserDict: UserDictData? = null
    @Volatile
    private var cachedUserDictMtime: Long? = null

    private fun loadUserDict(): UserDictData {
        val file = java.io.File(userDictPath)
        val mtime = if (file.exists()) file.lastModified() else -1L
        val cur = cachedUserDict
        val curM = cachedUserDictMtime
        if (cur != null && curM != null && curM == mtime) return cur
        val next = UserDict.loadFromFile(userDictPath)
        cachedUserDict = next
        cachedUserDictMtime = mtime
        return next
    }

    private fun splitByParentheses(text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        val parts = ArrayList<String>()
        var last = 0
        for (m in parenSplitRe.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start > last) {
                val p = text.substring(last, start)
                if (p.isNotEmpty()) parts.add(p)
            }
            val g = text.substring(start, end)
            if (g.isNotEmpty()) parts.add(g)
            last = end
        }
        if (last < text.length) {
            val p = text.substring(last)
            if (p.isNotEmpty()) parts.add(p)
        }
        return parts.ifEmpty { listOf("") }
    }

    private fun dedupeRepeatedCjk(text: String): String {
        var s = text
        if (s.isEmpty()) return s
        for (n in 1..6) {
            val re = Regex("([\\u4e00-\\u9fa5]{${n}})(?:\\1)+")
            s = re.replace(s, "$1")
        }
        s = Regex("\\s{2,}").replace(s, " ").trim()
        return s
    }

    private fun applyReplacements(text: String, replaceRules: Map<String, String>): String {
        var out = text
        for ((src, dst) in replaceRules) {
            if (src.isNotEmpty()) out = out.replace(src, dst)
        }
        return out
    }

    private fun splitSimpleParticle(token: String): List<String> {
        if (token.length <= 1) return listOf(token)
        if (!koreanBlockRe.matches(token)) return listOf(token)
        val last = token.last().toString()
        return if (simpleParticles.contains(last) && token.length > 1) listOf(token.dropLast(1), last) else listOf(token)
    }

    private fun splitByUserTerms(token: String, userDict: UserDictData): List<String> {
        if (token.length <= 1) return listOf(token)
        if (!koreanBlockRe.matches(token)) return listOf(token)
        val s = UserDict.cleanText(token)
        if (s.isEmpty()) return listOf(token)
        if (userDict.knownTerms.contains(s)) return listOf(token)
        var bestPrefix: String? = null
        for (j in s.length downTo 2) {
            val cand = s.substring(0, j)
            if (cand.length >= 2 && userDict.knownTerms.contains(cand)) {
                bestPrefix = cand
                break
            }
        }
        if (bestPrefix == null) return listOf(token)
        val remainder = s.substring(bestPrefix.length)
        if (remainder.isEmpty()) return listOf(bestPrefix)
        return listOf(bestPrefix, remainder)
    }

    private fun tokenizeForDisplay(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return tokenDisplayRe.findAll(text).map { it.value }.toList()
    }

    private fun extractKoreanTokensForModel(text: String, userDict: UserDictData): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (piece in modelTokenRe.findAll(text).map { it.value }) {
            if (koreanBlockRe.matches(piece)) {
                for (p1 in splitByUserTerms(piece, userDict)) {
                    for (t in splitSimpleParticle(p1)) {
                        if (t.isNotEmpty()) out.add(t)
                    }
                }
            }
        }
        return out
    }

    private fun argmaxWithPenalty(logits: FloatArray, seen: Set<Long>, repeatPenalty: Float): Long {
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            val raw = logits[i]
            val v = if (repeatPenalty > 1.0f && seen.contains(i.toLong())) raw / repeatPenalty else raw
            if (v > bestVal) {
                bestVal = v
                bestIdx = i
            }
        }
        return bestIdx.toLong()
    }

    private fun translateKoreanToken(token: String, userDict: UserDictData, cache: MutableMap<String, String>): String {
        val key = UserDict.cleanText(token)
        if (key.isEmpty()) return ""
        val cached = cache[key]
        if (cached != null) return cached

        val modelOnly = userDict.modelOnlyTerms.contains(key)
        if (!modelOnly) {
            val direct = userDict.directTranslations[key]
            if (direct != null) {
                val out = applyReplacements(direct, userDict.replaceRules)
                cache[key] = out
                return out
            }
            val g = userDict.glossary[key]
            if (g != null && g.isNotEmpty()) {
                val out = applyReplacements(g, userDict.replaceRules)
                cache[key] = out
                return out
            }
        }

        val override = userDict.tokenOverrides[key]
        if (override != null) {
            val out = override.joinToString("") { translateKoreanToken(it, userDict, cache) }
            cache[key] = out
            return out
        }

        val ko = assets.koVocab
        val cfg = assets.config
        val tokId = ko[key] ?: cfg.koUnkId
        val indices = longArrayOf(cfg.koSosId, tokId, cfg.koEosId)
        val srcTensor = Tensor.fromBlob(indices, longArrayOf(3, 1))
        val srcLenTensor = Tensor.fromBlob(longArrayOf(3), longArrayOf(1))

        val encTuple = encoder.forward(IValue.from(srcTensor), IValue.from(srcLenTensor)).toTuple()
        val encoderOutputs = encTuple[0].toTensor()
        var hiddenTensor = encTuple[1].toTensor()

        val trg = ArrayList<Long>(cfg.maxLenToken + 2)
        trg.add(cfg.zhSosId)
        val seen = HashSet<Long>()
        for (step in 0 until cfg.maxLenToken) {
            val lastId = trg.last()
            val inpTensor = Tensor.fromBlob(longArrayOf(lastId), longArrayOf(1))
            val decTuple = decoder.forward(IValue.from(inpTensor), IValue.from(hiddenTensor), IValue.from(encoderOutputs)).toTuple()
            val pred = decTuple[0].toTensor()
            val newHidden = decTuple[1].toTensor()
            val logits = pred.dataAsFloatArray
            val nextId = argmaxWithPenalty(logits, seen, cfg.repeatPenalty)
            trg.add(nextId)
            if (trg.size > 1) seen.add(nextId)
            hiddenTensor = newHidden
            if (nextId == cfg.zhEosId) break
        }

        val ivocab = assets.zhIvocab
        val sb = StringBuilder()
        for (id in trg) {
            if (id == cfg.zhSosId || id == cfg.zhEosId || id == cfg.zhPadId) continue
            val i = id.toInt()
            val t = if (i >= 0 && i < ivocab.size) ivocab[i] else "<unk>"
            if (t != "<sos>" && t != "<eos>" && t != "<pad>") sb.append(t)
        }
        var out = sb.toString()
        out = applyReplacements(out, userDict.replaceRules)
        if (out.isBlank()) out = "<unk>"
        cache[key] = out
        return out
    }

    private fun translateMixedTextWordByWord(text: String, userDict: UserDictData, cache: MutableMap<String, String>): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder()
        for (m in mixedPieceRe.findAll(text)) {
            val piece = m.value
            if (piece.isEmpty()) continue
            if (koreanBlockRe.matches(piece)) {
                for (p1 in splitByUserTerms(piece, userDict)) {
                    for (t in splitSimpleParticle(p1)) {
                        if (t.isNotEmpty()) out.append(translateKoreanToken(t, userDict, cache))
                    }
                }
            } else {
                out.append(piece)
            }
        }
        return out.toString()
    }

    fun translateSentence(text: String, showTokens: Boolean = false): String {
        val userDict = loadUserDict()
        val raw = text.trim()
        val key = UserDict.cleanText(raw)

        val modelOnly = userDict.modelOnlyTerms.contains(key)
        if (!modelOnly) {
            val direct = userDict.directTranslations[key]
            if (direct != null) return applyReplacements(direct, userDict.replaceRules)
            val g = userDict.glossary[key]
            if (g != null && g.isNotEmpty()) return applyReplacements(g, userDict.replaceRules)
        }

        if (showTokens) {
            tokenizeForDisplay(raw)
            extractKoreanTokensForModel(raw, userDict)
        }

        val cache = HashMap<String, String>()
        val parts = splitByParentheses(raw)
        val translated = if (parts.size > 1) {
            val sb = StringBuilder()
            for (p in parts) {
                if (p.startsWith("(") && p.endsWith(")")) {
                    if (numberParenAsciiRe.matches(p)) {
                        sb.append(p)
                    } else {
                        val inner = p.substring(1, p.length - 1)
                        sb.append("(").append(translateSentence(inner, false)).append(")")
                    }
                    continue
                }
                if (p.startsWith("（") && p.endsWith("）")) {
                    if (numberParenFullRe.matches(p)) {
                        sb.append(p)
                    } else {
                        val inner = p.substring(1, p.length - 1)
                        sb.append("（").append(translateSentence(inner, false)).append("）")
                    }
                    continue
                }
                sb.append(translateMixedTextWordByWord(p, userDict, cache))
            }
            sb.toString()
        } else {
            translateMixedTextWordByWord(raw, userDict, cache)
        }

        var out = applyReplacements(translated, userDict.replaceRules)
        out = dedupeRepeatedCjk(out)
        return out
    }

    companion object {
        fun create(context: Context, baseAssetDir: String = "translator"): TranslatorEngine {
            val assets = Vocabs.load(context, baseAssetDir)
            val encPath = AssetFiles.assetFilePath(context, "$baseAssetDir/encoder.ptl")
            val decPath = AssetFiles.assetFilePath(context, "$baseAssetDir/decoder.ptl")
            val encoder = LiteModuleLoader.load(encPath)
            val decoder = LiteModuleLoader.load(decPath)

            val userDictAsset = "$baseAssetDir/user_dict.md"
            val userDictPath = AssetFiles.assetFilePath(context, userDictAsset)

            return TranslatorEngine(assets, encoder, decoder, userDictPath)
        }
    }
}

