package com.example.k2ctranslator.modelupdate

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.k2ctranslator.K2CApp

class ModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId <= 0L) return
        val job = ModelDownloadStore.load(context, downloadId) ?: return

        val (status, _) = queryDownload(context, downloadId)
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            ModelDownloadStore.remove(context, downloadId)
            ModelDownloadNotifier.notifyDownloadFinished(context, downloadId, "模型下载失败", "请回到「模型更新」重新下载")
            return
        }

        val app = context.applicationContext
        val foreground = (app as? K2CApp)?.isAppInForeground() == true
        if (foreground) {
            val i = Intent(context, ModelDownloadPromptActivity::class.java).apply {
                putExtra(ModelDownloadPromptActivity.EXTRA_DOWNLOAD_ID, downloadId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(i)
        } else {
            ModelDownloadNotifier.notifyDownloadFinished(context, downloadId, "模型下载完成", "版本：${job.modelVersion}，点击安装")
        }
    }

    private fun queryDownload(context: Context, downloadId: Long): Pair<Int, String?> {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(downloadId)
        dm.query(q).use { c ->
            if (c == null || !c.moveToFirst()) return DownloadManager.STATUS_FAILED to null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val uriStr = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            return status to uriStr
        }
    }
}
