package com.example.k2ctranslator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.k2ctranslator.log.TranslationLogEntry
import com.example.k2ctranslator.log.TranslationLogStorage
import com.example.k2ctranslator.supabase.SupabaseLogSync
import com.example.k2ctranslator.translator.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslateFragment : Fragment(R.layout.fragment_translate) {
    private lateinit var statusView: TextView
    private lateinit var modelInfoView: TextView
    private lateinit var inputView: EditText
    private lateinit var outputView: TextView
    private lateinit var translateButton: Button
    private lateinit var copyButton: Button
    private lateinit var helpButton: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusView = view.findViewById(R.id.statusText)
        modelInfoView = view.findViewById(R.id.modelInfoText)
        inputView = view.findViewById(R.id.inputText)
        outputView = view.findViewById(R.id.outputText)
        translateButton = view.findViewById(R.id.translateButton)
        copyButton = view.findViewById(R.id.copyButton)
        helpButton = view.findViewById(R.id.helpButton)

        outputView.movementMethod = ScrollingMovementMethod.getInstance()

        modelInfoView.text = "模型：${EngineProvider.currentModelLabel(requireContext())}"

        translateButton.setOnClickListener { onTranslate() }
        copyButton.setOnClickListener { onCopy() }
        helpButton.setOnClickListener {
            HelpDialog().show(parentFragmentManager, "help")
        }

        if (!EngineProvider.isModelAvailable(requireContext())) {
            setModelMissingState()
            return
        }

        setLoadingState(true, "正在初始化模型…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { EngineProvider.get(requireContext()) }
                modelInfoView.text = "模型：${EngineProvider.currentModelLabel(requireContext())}"
                setLoadingState(false, "就绪")
            } catch (t: Throwable) {
                setLoadingState(false, "初始化失败：${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}")
            }
        }
    }

    private fun setModelMissingState() {
        statusView.text = "未安装模型，请先下载"
        modelInfoView.text = "模型：未安装"
        translateButton.isEnabled = false
        copyButton.isEnabled = false
        inputView.isEnabled = true
    }

    private fun setLoadingState(loading: Boolean, status: String) {
        statusView.text = status
        translateButton.isEnabled = !loading
        copyButton.isEnabled = !loading && outputView.text.isNotEmpty()
        inputView.isEnabled = !loading
    }

    private fun onTranslate() {
        val text = inputView.text?.toString().orEmpty()
        if (!EngineProvider.isModelAvailable(requireContext())) {
            setModelMissingState()
            Toast.makeText(requireContext(), "请先下载模型", Toast.LENGTH_LONG).show()
            return
        }
        setLoadingState(true, "翻译中…")
        val start = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Toast.makeText(requireContext(), "翻译中…", Toast.LENGTH_SHORT).show()
                val e = withContext(Dispatchers.IO) { EngineProvider.get(requireContext()) }
                val result = withContext(Dispatchers.Default) { e.translateSentence(text) }
                outputView.text = result
                val duration = System.currentTimeMillis() - start
                withContext(Dispatchers.IO) {
                    val unkCount = Regex("<unk>").findAll(result).count()
                    TranslationLogStorage.append(
                        requireContext(),
                        TranslationLogEntry(
                            timestampMs = System.currentTimeMillis(),
                            durationMs = duration,
                            inputChars = text.length,
                            outputChars = result.length,
                            unkCount = unkCount,
                            modelLabel = EngineProvider.currentModelLabel(requireContext()),
                            inputText = text,
                            outputText = result,
                        ),
                    )
                }
                setLoadingState(false, "完成")
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            SupabaseLogSync.sync(requireContext())
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "翻译失败：${t.message ?: t::class.java.simpleName}", Toast.LENGTH_LONG).show()
                setLoadingState(false, "翻译失败：${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}")
            }
        }
    }

    private fun onCopy() {
        val out = outputView.text?.toString().orEmpty()
        if (out.isEmpty()) {
            setLoadingState(false, statusView.text.toString())
            return
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("translation", out))
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
        setLoadingState(false, "已复制")
    }
}
