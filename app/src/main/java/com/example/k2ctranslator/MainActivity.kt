package com.example.k2ctranslator

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.appbar.MaterialToolbar
import androidx.appcompat.app.AlertDialog
import com.example.k2ctranslator.appupdate.AppUpdateChecker
import com.example.k2ctranslator.auth.AuthStore
import com.example.k2ctranslator.supabase.SupabaseAuth
import com.example.k2ctranslator.translator.EngineProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AuthStore.currentUser(this) == null && SupabaseAuth.currentSession(this) == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main_host)

        if (!EngineProvider.isModelAvailable(this)) {
            AlertDialog.Builder(this)
                .setTitle("未安装模型")
                .setMessage("安装完成后需要先下载模型，是否现在去下载？")
                .setPositiveButton("继续") { _, _ ->
                    startActivity(Intent(this, ModelUpdateActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val headerDate = findViewById<android.widget.TextView>(R.id.appHeaderDate)
        val headerVersion = findViewById<android.widget.TextView>(R.id.appHeaderVersion)
        val headerId = findViewById<android.widget.TextView>(R.id.appHeaderId)

        headerDate.text = "日期：${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}"
        headerVersion.text = "v${BuildConfig.VERSION_NAME}"

        val cloud = SupabaseAuth.currentSession(this@MainActivity)?.email
        val local = AuthStore.currentUser(this@MainActivity)
        val id = cloud ?: local
        headerId.text = if (!id.isNullOrBlank()) "ID：$id" else ""

        val topBar = findViewById<MaterialToolbar>(R.id.topAppBar)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        topBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_logout -> {
                    AuthStore.logout(this)
                    SupabaseAuth.logout(this)
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }

        val bottom = findViewById<BottomNavigationView>(R.id.bottomNav)
        val pager = findViewById<ViewPager2>(R.id.mainPager)
        pager.adapter = MainPagerAdapter(this)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val id = when (position) {
                    0 -> R.id.nav_translate
                    1 -> R.id.nav_dict
                    2 -> R.id.nav_stats
                    else -> R.id.nav_translate
                }
                if (bottom.selectedItemId != id) bottom.selectedItemId = id
            }
        })

        bottom.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_translate -> pager.setCurrentItem(0, true)
                R.id.nav_dict -> pager.setCurrentItem(1, true)
                R.id.nav_stats -> pager.setCurrentItem(2, true)
            }
            true
        }

        if (savedInstanceState == null) {
            pager.setCurrentItem(0, false)
        }

        AppUpdateChecker.checkAndPrompt(this)
    }
}
