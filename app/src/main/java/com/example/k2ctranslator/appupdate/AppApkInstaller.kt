package com.example.k2ctranslator.appupdate

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.k2ctranslator.BuildConfig
import java.io.File

object AppApkInstaller {
    fun installFromFile(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
