package com.example.k2ctranslator.appupdate

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.k2ctranslator.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AppUpdateChecker {
    fun checkAndPrompt(
        activity: AppCompatActivity,
        force: Boolean = false,
        showUpToDate: Boolean = false,
    ) {
        val prefs = activity.getSharedPreferences("app_update", Context.MODE_PRIVATE)
        if (!force) {
            val lastCheckDay = prefs.getString("last_check_day", "")
            val today = java.time.LocalDate.now().toString()
            if (lastCheckDay == today) return
            prefs.edit().putString("last_check_day", today).apply()
        }

        activity.lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) {
                try {
                    AppUpdateRemote.fetchLatest()
                } catch (_: Throwable) {
                    null
                }
            }
            if (latest == null) {
                if (force) {
                    AlertDialog.Builder(activity)
                        .setTitle("检查失败")
                        .setMessage("无法获取最新版本信息，请稍后再试。")
                        .setPositiveButton("确定", null)
                        .show()
                }
                return@launch
            }

            if (latest.versionCode <= BuildConfig.VERSION_CODE) {
                if (showUpToDate) {
                    val msg = buildString {
                        append("已经是最新版本。\n\n")
                        append("当前：v${BuildConfig.VERSION_NAME}\n")
                        if (latest.versionName.isNotBlank()) append("最新：v${latest.versionName}\n")
                        if (latest.notes.isNotBlank()) {
                            append("\n")
                            append(latest.notes)
                        }
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("版本检查")
                        .setMessage(msg)
                        .setPositiveButton("确定", null)
                        .show()
                }
                return@launch
            }

            val msg = buildString {
                append("版本更新了请下载。\n\n")
                append("当前：v${BuildConfig.VERSION_NAME}\n")
                if (latest.versionName.isNotBlank()) append("最新：v${latest.versionName}\n")
                if (latest.notes.isNotBlank()) {
                    append("\n")
                    append(latest.notes)
                }
            }
            AlertDialog.Builder(activity)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("下载") { _, _ ->
                    downloadAndInstall(activity, latest)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun downloadAndInstall(activity: AppCompatActivity, latest: AppLatest) {
        val waiting = AlertDialog.Builder(activity)
            .setTitle("下载中…")
            .setMessage("请稍候")
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                AppApkDownloader.downloadToCache(activity, latest.apkUrl, latest.versionCode, latest.versionName)
            }
            waiting.dismiss()
            if (!r.ok || r.file == null) {
                AlertDialog.Builder(activity)
                    .setTitle("下载失败")
                    .setMessage(r.message)
                    .setPositiveButton("确定", null)
                    .show()
                return@launch
            }
            try {
                AppApkInstaller.installFromFile(activity, r.file)
            } catch (t: Throwable) {
                AlertDialog.Builder(activity)
                    .setTitle("打开安装器失败")
                    .setMessage("${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun openUrl(context: Context, url: String) {
        val u = url.trim()
        if (u.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
