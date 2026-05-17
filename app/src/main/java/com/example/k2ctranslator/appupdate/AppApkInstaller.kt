package com.example.k2ctranslator.appupdate

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.k2ctranslator.BuildConfig
import java.io.File

object AppApkInstaller {
    fun installFromFile(context: Context, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = context.packageManager
            if (!pm.canRequestPackageInstalls()) {
                val pkgUri = Uri.parse("package:${context.packageName}")
                val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
                throw IllegalStateException("请先在系统设置中允许本应用安装未知应用")
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile,
        )
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("apk", uri)
        }

        val resolved = viewIntent.resolveActivity(context.packageManager)
        if (resolved != null) {
            context.startActivity(viewIntent)
            return
        }

        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("apk", uri)
        }
        context.startActivity(installIntent)
    }
}
