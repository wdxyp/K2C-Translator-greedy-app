package com.example.k2ctranslator

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.k2ctranslator.auth.AuthStore
import com.example.k2ctranslator.log.TranslationLogStorage
import com.example.k2ctranslator.supabase.SupabaseAuth
import com.example.k2ctranslator.supabase.SupabaseLogSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class StatsFragment : Fragment(R.layout.fragment_stats) {
    private lateinit var summaryView: TextView
    private lateinit var historyView: TextView
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val bytes = TranslationLogStorage.readAllBytesWithUtf8Bom(requireContext()) ?: return@withContext
                requireContext().contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            }
            refresh()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        summaryView = view.findViewById(R.id.statsSummaryText)
        historyView = view.findViewById(R.id.statsHistoryText)

        view.findViewById<Button>(R.id.openModelUpdateButton).setOnClickListener {
            startActivity(Intent(requireContext(), ModelUpdateActivity::class.java))
        }

        view.findViewById<Button>(R.id.exportCsvButton).setOnClickListener {
            exportLauncher.launch("k2c_translations.csv")
        }

        view.findViewById<Button>(R.id.clearLogsButton).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { TranslationLogStorage.clear(requireContext()) }
                refresh()
            }
        }

        view.findViewById<Button>(R.id.syncCloudButton).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                summaryView.text = "同步中…"
                val r = withContext(Dispatchers.IO) { SupabaseLogSync.sync(requireContext()) }
                summaryView.text = if (r.ok) "同步成功：${r.uploaded} 条" else r.message
                refresh()
            }
        }

        view.findViewById<Button>(R.id.logoutButton).setOnClickListener {
            AuthStore.logout(requireContext())
            SupabaseAuth.logout(requireContext())
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) { TranslationLogStorage.readLastLines(requireContext(), 50) }
            val entries = withContext(Dispatchers.IO) { TranslationLogStorage.readLastEntries(requireContext(), 50) }
            val summary = TranslationLogStorage.summarize(lines)

            summaryView.text = buildString {
                append("次数：").append(summary.count)
                append("  总字数：").append(summary.totalInputChars)
                append("  总耗时：").append(summary.totalDurationMs).append("ms")
                append("  <unk>：").append(summary.totalUnkCount)
            }
            historyView.text = if (entries.isEmpty()) {
                "暂无记录"
            } else {
                entries.asReversed().joinToString("\n\n") { e ->
                    buildString {
                        append("时间：").append(dateFmt.format(Date(e.timestampMs)))
                        append("  耗时：").append(e.durationMs).append("ms")
                        append("\n原文：").append(e.inputText)
                        append("\n译文：").append(e.outputText)
                    }
                }
            }
        }
    }
}
