package com.example.k2ctranslator

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.k2ctranslator.appupdate.AppUpdateChecker
import com.example.k2ctranslator.auth.AuthStore
import com.example.k2ctranslator.supabase.SupabaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var userView: EditText
    private lateinit var passView: EditText
    private lateinit var loginButton: Button
    private lateinit var registerButton: Button
    private lateinit var cloudLoginButton: Button
    private lateinit var cloudRegisterButton: Button
    private lateinit var checkAppUpdateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        titleView = findViewById(R.id.loginTitle)
        statusView = findViewById(R.id.loginStatus)
        userView = findViewById(R.id.loginUsername)
        passView = findViewById(R.id.loginPassword)
        loginButton = findViewById(R.id.loginButton)
        registerButton = findViewById(R.id.registerButton)
        cloudLoginButton = findViewById(R.id.cloudLoginButton)
        cloudRegisterButton = findViewById(R.id.cloudRegisterButton)
        checkAppUpdateButton = findViewById(R.id.checkAppUpdateButton)

        titleView.isSaveEnabled = false
        titleView.text = "${getString(R.string.app_name)}  v${BuildConfig.VERSION_NAME}"

        val cloud = SupabaseAuth.currentSession(this)
        if (cloud != null) {
            goMain()
            return
        }
        val cur = AuthStore.currentUser(this)
        if (cur != null) {
            goMain()
            return
        }

        loginButton.setOnClickListener { onLogin() }
        registerButton.setOnClickListener { onRegister() }
        cloudLoginButton.setOnClickListener { onCloudLogin() }
        cloudRegisterButton.setOnClickListener { onCloudRegister() }
        checkAppUpdateButton.setOnClickListener {
            AppUpdateChecker.checkAndPrompt(this, force = true, showUpToDate = true)
        }
    }

    override fun onResume() {
        super.onResume()
        titleView.text = "${getString(R.string.app_name)}  v${BuildConfig.VERSION_NAME}"
    }

    private fun onLogin() {
        val u = userView.text?.toString().orEmpty()
        val p = passView.text?.toString().orEmpty()
        val r = AuthStore.login(this, u, p)
        statusView.text = r.message
        if (r.ok) goMain()
    }

    private fun onRegister() {
        val u = userView.text?.toString().orEmpty()
        val p = passView.text?.toString().orEmpty()
        val r = AuthStore.register(this, u, p)
        statusView.text = r.message
        if (r.ok) goMain()
    }

    private fun onCloudLogin() {
        val email = userView.text?.toString().orEmpty()
        val pass = passView.text?.toString().orEmpty()
        statusView.text = "登录中…"
        setButtonsEnabled(false)
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { SupabaseAuth.signInEmail(this@LoginActivity, email, pass) }
                statusView.text = r.message
                if (r.ok) goMain() else setButtonsEnabled(true)
            } catch (t: Throwable) {
                statusView.text = "登录失败：${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                setButtonsEnabled(true)
            }
        }
    }

    private fun onCloudRegister() {
        val email = userView.text?.toString().orEmpty()
        val pass = passView.text?.toString().orEmpty()
        statusView.text = "注册中…"
        setButtonsEnabled(false)
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { SupabaseAuth.signUpEmail(this@LoginActivity, email, pass) }
                statusView.text = r.message
                if (r.ok) goMain() else setButtonsEnabled(true)
            } catch (t: Throwable) {
                statusView.text = "注册失败：${t::class.java.simpleName}${t.message?.let { ": $it" } ?: ""}"
                setButtonsEnabled(true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        loginButton.isEnabled = enabled
        registerButton.isEnabled = enabled
        cloudLoginButton.isEnabled = enabled
        cloudRegisterButton.isEnabled = enabled
        userView.isEnabled = enabled
        passView.isEnabled = enabled
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
