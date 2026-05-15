package com.example.k2ctranslator.modelupdate

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

object BackgroundModelDownloader {
    fun enqueue(context: Context, info: LatestModelInfo): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(info.zipUrl)
        val fileName = "model_bundle_${info.modelVersion.ifBlank { "latest" }}.zip"
        val req = DownloadManager.Request(uri)
            .setTitle("模型下载")
            .setDescription(info.modelVersion.ifBlank { "latest" })
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = dm.enqueue(req)
        ModelDownloadStore.save(
            context,
            ModelDownloadJob(
                downloadId = id,
                modelVersion = info.modelVersion,
                zipUrl = info.zipUrl,
                zipSha256 = info.zipSha256,
            ),
        )
        return id
    }
}

