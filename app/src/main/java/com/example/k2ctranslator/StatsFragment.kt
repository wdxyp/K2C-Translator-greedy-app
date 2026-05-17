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
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class StatsFragment : Fragment(R.layout.fragment_stats) {
    private lateinit var summaryView: TextView
    private lateinit var historyView: TextView
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private var pendingExportRange: Pair<Long, Long>? = null

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val range = pendingExportRange
                val bytes = if (range != null) {
                    val session = SupabaseAuth.ensureValidSession(requireContext())
                        ?: throw IllegalStateException("请先邮箱登录")
                    val r = SupabaseLogSync.exportCsvRange(requireContext(), range.first, range.second)
                    if (!r.ok || r.csvBytes == null) throw IllegalStateException(r.message)
                    r.csvBytes
                } else {
                    TranslationLogStorage.readAllBytesWithUtf8Bom(requireContext()) ?: return@withContext
                }
                requireContext().contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            }
            pendingExportRange = null
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
            if (!SupabaseAuth.isConfigured()) {
                summaryView.text = "Supabase 未配置"
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                summaryView.text = "同步中…"
                val session = withContext(Dispatchers.IO) {
                    try {
                        SupabaseAuth.ensureValidSession(requireContext())
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (session == null) {
                    summaryView.text = "请先邮箱登录"
                    return@launch
                }
                val r = withContext(Dispatchers.IO) { SupabaseLogSync.sync(requireContext()) }
                summaryView.text = if (r.ok) "同步成功：${r.uploaded} 条" else r.message

                val zone = ZoneId.systemDefault()
                val endLocal = LocalDate.now(zone)
                val startLocal = endLocal.minusDays(6)
                val startMsDefault = startLocal.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val endMsDefault = endLocal.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

                val picker = MaterialDatePicker.Builder.dateRangePicker()
                    .setTitleText("选择导出日期区间")
                    .setSelection(androidx.core.util.Pair(startMsDefault, endMsDefault))
                    .build()
                picker.addOnPositiveButtonClickListener { sel ->
                    if (sel == null) return@addOnPositiveButtonClickListener
                    val startUtcMs = sel.first ?: return@addOnPositiveButtonClickListener
                    val endUtcMs = sel.second ?: return@addOnPositiveButtonClickListener
                    val startDate = Instant.ofEpochMilli(startUtcMs).atZone(ZoneOffset.UTC).toLocalDate()
                    val endDate = Instant.ofEpochMilli(endUtcMs).atZone(ZoneOffset.UTC).toLocalDate()

                    val startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    val endMs = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    if (endMs < startMs) {
                        summaryView.text = "日期区间不正确"
                        return@addOnPositiveButtonClickListener
                    }

                    pendingExportRange = Pair(startMs, endMs)
                    val fn = "k2c_translations_${startDate}_${endDate}.csv"
                    exportLauncher.launch(fn)
                }
                picker.show(parentFragmentManager, "export_csv_range")
            }
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
