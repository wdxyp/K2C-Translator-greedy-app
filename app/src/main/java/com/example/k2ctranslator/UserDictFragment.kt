package com.example.k2ctranslator

import android.net.Uri
import android.os.Bundle
import android.view.ViewTreeObserver
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.k2ctranslator.supabase.SupabaseUserDictSync
import com.example.k2ctranslator.supabase.SupabaseAuth
import com.example.k2ctranslator.translator.UserDictStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class UserDictFragment : Fragment(R.layout.fragment_user_dict) {
    private lateinit var statusView: TextView
    private lateinit var contentView: EditText
    private lateinit var editButton: Button
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button
    private lateinit var importButton: Button
    private lateinit var downloadButton: Button
    private var editable: Boolean = false
    private var lastSyncedSha: String? = null
    private var pendingExportText: String? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            importDictFile(uri)
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) {
            pendingExportText = null
            setLoadingState(false, "已取消")
            return@registerForActivityResult
        }
        val text = pendingExportText
        pendingExportText = null
        if (text == null) {
            setLoadingState(false, "导出失败：无内容")
            return@registerForActivityResult
        }
        viewLifecycleOwner.lifecycleScope.launch {
            setLoadingState(true, "导出中…")
            try {
                withContext(Dispatchers.IO) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    requireContext().contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                        ?: throw IllegalStateException("无法写入文件")
                }
                setLoadingState(false, "已导出词典文件")
            } catch (t: Throwable) {
                setLoadingState(false, "导出失败：${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}")
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusView = view.findViewById(R.id.dictStatusText)
        contentView = view.findViewById(R.id.dictContentText)
        editButton = view.findViewById(R.id.dictEditButton)
        saveButton = view.findViewById(R.id.dictSaveButton)
        resetButton = view.findViewById(R.id.dictResetButton)
        importButton = view.findViewById(R.id.dictImportButton)
        downloadButton = view.findViewById(R.id.dictDownloadButton)

        editButton.setOnClickListener { toggleEdit(true) }
        saveButton.setOnClickListener { onSave() }
        resetButton.setOnClickListener { onReset() }
        importButton.setOnClickListener { importLauncher.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream")) }
        downloadButton.setOnClickListener { exportLatestDictAsFile() }

        contentView.doAfterTextChanged {
            if (editable) ensureCursorVisible()
        }
        contentView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && editable) ensureCursorVisible()
        }
        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (editable) ensureCursorVisible()
        }.also { l ->
            view.viewTreeObserver.addOnGlobalLayoutListener(l)
        }

        setLoadingState(true, "正在加载词典…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    if (!UserDictStorage.userDictFile(requireContext()).exists()) {
                        UserDictStorage.resetToDefault(requireContext())
                    }
                    UserDictStorage.readUserDict(requireContext())
                }
                contentView.setText(content)
                toggleEdit(false)
                lastSyncedSha = sha256Hex(content)
                setLoadingState(false, "就绪")
                syncFromCloud(force = false)
            } catch (t: Throwable) {
                setLoadingState(false, "加载失败：${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}")
            }
        }
    }

    private fun toggleEdit(enabled: Boolean) {
        editable = enabled
        contentView.isEnabled = enabled
        saveButton.isEnabled = enabled
        editButton.isEnabled = !enabled
        if (enabled) {
            statusView.text = "编辑中…"
            contentView.requestFocus()
        }
    }

    private fun setLoadingState(loading: Boolean, status: String) {
        statusView.text = status
        resetButton.isEnabled = !loading
        importButton.isEnabled = !loading
        downloadButton.isEnabled = !loading
        if (loading) {
            editButton.isEnabled = false
            saveButton.isEnabled = false
            contentView.isEnabled = false
        } else {
            editButton.isEnabled = !editable
            saveButton.isEnabled = editable
            contentView.isEnabled = editable
        }
    }

    override fun onResume() {
        super.onResume()
        syncFromCloud(force = false)
    }

    override fun onDestroyView() {
        globalLayoutListener?.let { l ->
            view?.viewTreeObserver?.removeOnGlobalLayoutListener(l)
        }
        globalLayoutListener = null
        pendingExportText = null
        super.onDestroyView()
    }

    private fun onSave() {
        if (!editable) return
        val text = contentView.text?.toString().orEmpty()
        setLoadingState(true, "保存中…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    UserDictStorage.writeUserDict(requireContext(), text)
                }
                toggleEdit(false)
                val savedSha = sha256Hex(text)
                lastSyncedSha = savedSha

                val uploadMsg = withContext(Dispatchers.IO) {
                    if (!SupabaseAuth.isConfigured()) {
                        "已保存（Supabase 未配置）"
                    } else if (SupabaseAuth.ensureValidSession(requireContext()) == null) {
                        "已保存（未邮箱登录）"
                    } else {
                        val r = SupabaseUserDictSync.upload(requireContext(), text)
                        if (r.ok) "已保存并同步到云端" else "已保存（云端同步失败）"
                    }
                }
                setLoadingState(false, uploadMsg)
            } catch (t: Throwable) {
                setLoadingState(false, "保存失败：${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}")
            }
        }
    }

    private fun onReset() {
        setLoadingState(true, "恢复默认…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    UserDictStorage.resetToDefault(requireContext())
                    UserDictStorage.readUserDict(requireContext())
                }
                contentView.setText(content)
                toggleEdit(false)
                lastSyncedSha = sha256Hex(content)
                val uploadMsg = withContext(Dispatchers.IO) {
                    if (!SupabaseAuth.isConfigured()) {
                        "已恢复默认（Supabase 未配置）"
                    } else if (SupabaseAuth.ensureValidSession(requireContext()) == null) {
                        "已恢复默认（未邮箱登录）"
                    } else {
                        val r = SupabaseUserDictSync.upload(requireContext(), content)
                        if (r.ok) "已恢复默认并同步到云端" else "已恢复默认（云端同步失败）"
                    }
                }
                setLoadingState(false, uploadMsg)
            } catch (t: Throwable) {
                setLoadingState(false, "恢复失败：${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}")
            }
        }
    }

    private fun syncFromCloud(force: Boolean) {
        if (editable) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (cloudContent, r) = withContext(Dispatchers.IO) { SupabaseUserDictSync.download(requireContext()) }
                if (!r.ok || cloudContent == null) {
                    if (force) setLoadingState(false, r.message)
                    return@launch
                }
                val cloudSha = sha256Hex(cloudContent)
                if (!force && cloudSha == lastSyncedSha) return@launch
                val local = contentView.text?.toString().orEmpty()
                if (!force && sha256Hex(local) == cloudSha) {
                    lastSyncedSha = cloudSha
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    UserDictStorage.writeUserDict(requireContext(), cloudContent)
                }
                contentView.setText(cloudContent)
                toggleEdit(false)
                lastSyncedSha = cloudSha
                setLoadingState(false, "已从云端同步")
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun importDictFile(uri: Uri) {
        setLoadingState(true, "导入中…")
        try {
            val text = withContext(Dispatchers.IO) { readUtf8(requireContext().contentResolver.openInputStream(uri)) }
            withContext(Dispatchers.IO) { UserDictStorage.writeUserDict(requireContext(), text) }
            contentView.setText(text)
            toggleEdit(false)
            lastSyncedSha = sha256Hex(text)

            val uploadMsg = withContext(Dispatchers.IO) {
                if (!SupabaseAuth.isConfigured()) {
                    "已导入（Supabase 未配置）"
                } else if (SupabaseAuth.ensureValidSession(requireContext()) == null) {
                    "已导入（未邮箱登录）"
                } else {
                    val r = SupabaseUserDictSync.upload(requireContext(), text)
                    if (r.ok) "已导入并同步到云端" else "已导入（云端同步失败）"
                }
            }
            setLoadingState(false, uploadMsg)
        } catch (t: Throwable) {
            setLoadingState(false, "导入失败：${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}")
        }
    }

    private fun readUtf8(input: java.io.InputStream?): String {
        input?.use { ins ->
            val bytes = ins.readBytes()
            return bytes.toString(Charsets.UTF_8)
        }
        throw IllegalStateException("无法读取文件")
    }

    private fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = text.toByteArray(Charsets.UTF_8)
        md.update(bytes)
        val dig = md.digest()
        val sb = StringBuilder(dig.size * 2)
        for (b in dig) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    private fun ensureCursorVisible() {
        val pos = contentView.selectionStart.coerceAtLeast(0)
        contentView.post {
            try {
                contentView.bringPointIntoView(pos)
            } catch (_: Throwable) {
            }
        }
    }

    private fun exportLatestDictAsFile() {
        if (editable) return
        setLoadingState(true, "准备导出…")
        viewLifecycleOwner.lifecycleScope.launch {
            val (cloudContent, r) = withContext(Dispatchers.IO) { SupabaseUserDictSync.download(requireContext()) }
            val text = if (r.ok && cloudContent != null) {
                cloudContent
            } else {
                contentView.text?.toString().orEmpty()
            }
            pendingExportText = text
            exportLauncher.launch("user_dict.md")
        }
    }
}
