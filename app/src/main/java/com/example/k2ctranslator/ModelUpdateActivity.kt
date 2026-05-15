package com.example.k2ctranslator

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.k2ctranslator.modelupdate.LatestModelInfo
import com.example.k2ctranslator.modelupdate.BackgroundModelDownloader
import com.example.k2ctranslator.modelupdate.ModelBundleInstaller
import com.example.k2ctranslator.modelupdate.ModelUpdateRemote
import com.example.k2ctranslator.translator.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ModelUpdateActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var resetButton: Button
    private var latest: LatestModelInfo? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            installZip(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_update)

        statusView = findViewById(R.id.modelUpdateStatus)
        findViewById<Button>(R.id.checkLatestButton).setOnClickListener {
            lifecycleScope.launch { checkLatest() }
        }
        findViewById<Button>(R.id.downloadLatestButton).setOnClickListener {
            lifecycleScope.launch { downloadAndInstallLatest() }
        }
        findViewById<Button>(R.id.importZipButton).setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        resetButton = findViewById(R.id.resetBuiltinButton)
        resetButton.text = if (EngineProvider.isBundledModelAvailable(this)) "恢复内置模型" else "删除本地模型"
        resetButton.setOnClickListener {
            lifecycleScope.launch { resetToBuiltin() }
        }

        refreshStatus()
    }

    private fun refreshStatus() {
        statusView.text = "当前模型：${EngineProvider.currentModelLabel(this)}"
    }

    private suspend fun checkLatest() {
        statusView.text = "检查中…"
        val (info, err) = withContext(Dispatchers.IO) { ModelUpdateRemote.fetchLatest() }
        if (err != null) {
            statusView.text = err
            latest = null
            return
        }
        latest = info
        statusView.text = buildString {
            append("当前模型：").append(EngineProvider.currentModelLabel(this@ModelUpdateActivity))
            append("\n最新模型：").append(info?.modelVersion ?: "unknown")
            if (!info?.notes.isNullOrBlank()) {
                append("\n说明：").append(info?.notes)
            }
        }
    }

    private suspend fun downloadAndInstallLatest() {
        try {
            val info = latest
            if (info == null) {
                checkLatest()
            }
            val info2 = latest ?: run {
                statusView.text = "未获取到最新信息"
                return
            }
            val url = info2.zipUrl.trim()
            val isDrive = url.contains("drive.google.com", ignoreCase = true) || url.contains("drive.usercontent.google.com", ignoreCase = true)
            if (isDrive) {
                statusView.text = "下载中…"
                val (zip, err) = withContext(Dispatchers.IO) { ModelUpdateRemote.downloadZip(cacheDir, url) }
                if (err != null || zip == null) {
                    statusView.text = err ?: "下载失败"
                    return
                }
                val actual = withContext(Dispatchers.IO) { ModelUpdateRemote.sha256Hex(zip) }
                val expected = info2.zipSha256.trim().lowercase()
                if (expected.isNotBlank() && actual != expected) {
                    statusView.text = "安装失败：sha256 不一致\nexpected=$expected\nactual=$actual"
                    return
                }
                val result = withContext(Dispatchers.IO) { ModelBundleInstaller.installZipFile(this@ModelUpdateActivity, zip) }
                EngineProvider.reset()
                statusView.text = "安装成功：$result"
                refreshStatus()
                return
            }

            val id = withContext(Dispatchers.IO) { BackgroundModelDownloader.enqueue(this@ModelUpdateActivity, info2) }
            statusView.text = "已开始后台下载：${info2.modelVersion}\n下载完成后将提示是否安装（继续/取消）\n任务ID：$id"
        } catch (t: Throwable) {
            statusView.text = "更新失败：${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
        }
    }

    private suspend fun installZip(uri: Uri) {
        statusView.text = "导入中…"
        val result = withContext(Dispatchers.IO) {
            ModelBundleInstaller.installZipUri(this@ModelUpdateActivity, uri)
        }

        EngineProvider.reset()
        statusView.text = "导入成功：$result"
    }

    private suspend fun resetToBuiltin() {
        val hasBuiltin = EngineProvider.isBundledModelAvailable(this)
        statusView.text = if (hasBuiltin) "恢复中…" else "删除中…"
        withContext(Dispatchers.IO) {
            val base = File(filesDir, "translator")
            File(base, "encoder.ptl").delete()
            File(base, "decoder.ptl").delete()
            File(base, "ko_vocab.json").delete()
            File(base, "zh_ivocab.json").delete()
            File(base, "config.json").delete()
            File(base, "manifest.json").delete()
        }
        EngineProvider.reset()
        statusView.text = if (hasBuiltin) "已恢复内置模型" else "已删除本地模型"
        refreshStatus()
    }
}
