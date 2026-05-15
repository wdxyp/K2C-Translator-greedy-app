package com.example.k2ctranslator.modelupdate

import android.app.DownloadManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.k2ctranslator.translator.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ModelDownloadPromptActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val downloadId = intent?.getLongExtra(EXTRA_DOWNLOAD_ID, -1L) ?: -1L
        if (downloadId <= 0L) {
            finish()
            return
        }

        val job = ModelDownloadStore.load(this, downloadId)
        if (job == null) {
            Toast.makeText(this, "下载任务已过期", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val (status, localUri) = queryDownload(downloadId)
        if (status == DownloadManager.STATUS_FAILED || localUri == null) {
            ModelDownloadStore.remove(this, downloadId)
            AlertDialog.Builder(this)
                .setTitle("模型下载失败")
                .setMessage("请回到「模型更新」重新下载。")
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("模型下载完成")
            .setMessage("版本：${job.modelVersion}\n是否现在安装？")
            .setPositiveButton("继续") { _, _ ->
                install(downloadId, job, localUri)
            }
            .setNegativeButton("取消") { _, _ ->
                cancel(downloadId)
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun install(downloadId: Long, job: ModelDownloadJob, localUri: Uri) {
        lifecycleScope.launch {
            try {
                val label = withContext(Dispatchers.IO) {
                    val file = resolveToFile(localUri)
                    val sha = ModelUpdateRemote.sha256Hex(file)
                    if (!sha.equals(job.zipSha256, ignoreCase = true)) {
                        throw IllegalStateException("sha256 不一致\nexpected=${job.zipSha256}\nactual=$sha")
                    }
                    ModelBundleInstaller.installZipFile(this@ModelDownloadPromptActivity, file)
                }
                EngineProvider.reset()
                ModelDownloadStore.remove(this@ModelDownloadPromptActivity, downloadId)
                Toast.makeText(this@ModelDownloadPromptActivity, "已安装：$label", Toast.LENGTH_LONG).show()
                finish()
            } catch (t: Throwable) {
                AlertDialog.Builder(this@ModelDownloadPromptActivity)
                    .setTitle("安装失败")
                    .setMessage("${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}")
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            }
        }
    }

    private fun cancel(downloadId: Long) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        dm.remove(downloadId)
        ModelDownloadStore.remove(this, downloadId)
    }

    private fun queryDownload(downloadId: Long): Pair<Int, Uri?> {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(downloadId)
        dm.query(q).use { c ->
            if (c == null || !c.moveToFirst()) return DownloadManager.STATUS_FAILED to null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val uriStr = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            val uri = uriStr?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            return status to uri
        }
    }

    private fun resolveToFile(uri: Uri): File {
        if (uri.scheme == "file") {
            val p = uri.path ?: throw IllegalStateException("无效文件路径")
            return File(p)
        }
        val tmp = File(cacheDir, "dm_model_bundle.zip")
        if (tmp.exists()) tmp.delete()
        contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取下载文件")
        return tmp
    }

    companion object {
        const val EXTRA_DOWNLOAD_ID = "download_id"
    }
}

