package com.example.k2ctranslator.translator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TranslatorConfig(
    val koSosId: Long,
    val koEosId: Long,
    val koUnkId: Long,
    val zhSosId: Long,
    val zhEosId: Long,
    val zhUnkId: Long,
    val zhPadId: Long,
    val maxLenToken: Int,
    val repeatPenalty: Float,
)

data class TranslatorAssets(
    val koVocab: Map<String, Long>,
    val zhIvocab: List<String>,
    val config: TranslatorConfig,
)

object Vocabs {
    private fun readTextFile(path: String): String {
        return java.io.File(path).readText(Charsets.UTF_8)
    }

    fun load(context: Context, baseAssetDir: String = "translator"): TranslatorAssets {
        val koPath = AssetFiles.assetFilePath(context, "$baseAssetDir/ko_vocab.json")
        val zhPath = AssetFiles.assetFilePath(context, "$baseAssetDir/zh_ivocab.json")
        val cfgPath = AssetFiles.assetFilePath(context, "$baseAssetDir/config.json")

        val koText = readTextFile(koPath)
        val zhText = readTextFile(zhPath)
        val cfgText = readTextFile(cfgPath)

        val koObj = JSONObject(koText)
        val koMap = HashMap<String, Long>(koObj.length())
        val koKeys = koObj.keys()
        while (koKeys.hasNext()) {
            val k = koKeys.next()
            koMap[k] = koObj.getLong(k)
        }

        val zhArr = JSONArray(zhText)
        val zhList = ArrayList<String>(zhArr.length())
        for (i in 0 until zhArr.length()) {
            zhList.add(zhArr.optString(i, ""))
        }

        val cfgObj = JSONObject(cfgText)
        val koCfg = cfgObj.getJSONObject("ko")
        val zhCfg = cfgObj.getJSONObject("zh")
        val decodeCfg = cfgObj.getJSONObject("decode")
        val config = TranslatorConfig(
            koSosId = koCfg.getLong("sos_id"),
            koEosId = koCfg.getLong("eos_id"),
            koUnkId = koCfg.getLong("unk_id"),
            zhSosId = zhCfg.getLong("sos_id"),
            zhEosId = zhCfg.getLong("eos_id"),
            zhUnkId = zhCfg.getLong("unk_id"),
            zhPadId = zhCfg.optLong("pad_id", 0L),
            maxLenToken = decodeCfg.getInt("max_len_token"),
            repeatPenalty = decodeCfg.getDouble("repeat_penalty").toFloat(),
        )
        return TranslatorAssets(koMap, zhList, config)
    }
}
