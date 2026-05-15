package com.example.k2ctranslator

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.k2ctranslator.translator.UserDictStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserDictEditorActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var contentView: EditText
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button
    private lateinit var backButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_dict_editor)

        statusView = findViewById(R.id.dictStatusText)
        contentView = findViewById(R.id.dictContentText)
        saveButton = findViewById(R.id.dictSaveButton)
        resetButton = findViewById(R.id.dictResetButton)
        backButton = findViewById(R.id.dictBackButton)

        saveButton.setOnClickListener { onSave() }
        resetButton.setOnClickListener { onReset() }
        backButton.setOnClickListener { finish() }

        setLoadingState(true, "正在加载词典…")
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    if (!UserDictStorage.userDictFile(this@UserDictEditorActivity).exists()) {
                        UserDictStorage.resetToDefault(this@UserDictEditorActivity)
                    }
                    UserDictStorage.readUserDict(this@UserDictEditorActivity)
                }
                contentView.setText(content)
                setLoadingState(false, "就绪")
            } catch (t: Throwable) {
                setLoadingState(false, "加载失败：${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    private fun setLoadingState(loading: Boolean, status: String) {
        statusView.text = status
        saveButton.isEnabled = !loading
        resetButton.isEnabled = !loading
        backButton.isEnabled = !loading
        contentView.isEnabled = !loading
    }

    private fun onSave() {
        val text = contentView.text?.toString().orEmpty()
        setLoadingState(true, "保存中…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    UserDictStorage.writeUserDict(this@UserDictEditorActivity, text)
                }
                setLoadingState(false, "已保存")
            } catch (t: Throwable) {
                setLoadingState(false, "保存失败：${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    private fun onReset() {
        setLoadingState(true, "恢复默认…")
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    UserDictStorage.resetToDefault(this@UserDictEditorActivity)
                    UserDictStorage.readUserDict(this@UserDictEditorActivity)
                }
                contentView.setText(content)
                setLoadingState(false, "已恢复默认")
            } catch (t: Throwable) {
                setLoadingState(false, "恢复失败：${t.message ?: t::class.java.simpleName}")
            }
        }
    }
}
