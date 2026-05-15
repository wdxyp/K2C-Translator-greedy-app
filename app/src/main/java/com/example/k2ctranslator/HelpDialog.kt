package com.example.k2ctranslator

import android.app.Dialog
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.fragment.app.DialogFragment

class HelpDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_help, null, false)
        val tv = v.findViewById<TextView>(R.id.helpText)
        tv.text = HtmlCompat.fromHtml(helpHtml(), HtmlCompat.FROM_HTML_MODE_COMPACT)
        tv.movementMethod = LinkMovementMethod.getInstance()
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_help)
            .setView(v)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    private fun helpHtml(): String {
        return """
            <b>快速使用</b><br/>
            <ul>
              <li><b>输入原文</b>：在「原文」输入框里输入韩文或混排文本。</li>
              <li><b>开始翻译</b>：点击「翻译」。翻译完成后在下方「译文」查看结果。</li>
              <li><b>复制结果</b>：点击「复制结果」将译文复制到剪贴板。</li>
            </ul>
            <b>词典 user_dict</b><br/>
            <ul>
              <li>进入「词典」页，默认只读。</li>
              <li>点击「编辑」后才能修改，修改完成点击「保存」。</li>
              <li>点击「恢复默认」可回到内置词典。</li>
            </ul>
            <b>模型与更新</b><br/>
            <ul>
              <li>模型来自训练产物导出后的移动端模型包。</li>
              <li>未安装模型时会提示去下载并安装模型。</li>
            </ul>
            <b>日志与导出</b><br/>
            <ul>
              <li>每次翻译会记录基本统计信息（不默认保存原文）。</li>
              <li>在「统计」页可导出 CSV（Excel 可直接打开）。</li>
            </ul>
        """.trimIndent()
    }
}
