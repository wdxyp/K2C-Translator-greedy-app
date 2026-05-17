package com.example.k2ctranslator.appupdate

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("apk", uri)
        }

        val pm = context.packageManager
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved.isEmpty()) {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("apk", uri)
            }
            context.startActivity(fallback)
            return
        }

        context.startActivity(intent)
    }
}
