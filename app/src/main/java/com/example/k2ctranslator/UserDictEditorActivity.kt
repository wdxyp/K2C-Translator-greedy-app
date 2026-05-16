package com.example.k2ctranslator

import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
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
    private var rootContentView: View? = null
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var lastKeyboardVisible: Boolean = false

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
        contentView.setHorizontallyScrolling(false)
        contentView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) contentView.post { ensureCursorVisible() }
        }
        contentView.setOnClickListener { contentView.post { ensureCursorVisible() } }
        contentView.setOnClickListener { contentView.post { ensureCursorVisible() } }
        contentView.setOnKeyListener { _, _, event ->
            if (event.action == KeyEvent.ACTION_UP || event.action == KeyEvent.ACTION_DOWN) {
                contentView.post { ensureCursorVisible() }
            }
            false
        }
        contentView.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    contentView.post { ensureCursorVisible() }
                }
            },
        )

        rootContentView = findViewById(android.R.id.content)
        val root = rootContentView
        if (root != null) {
            val listener =
                ViewTreeObserver.OnGlobalLayoutListener {
                    val r = Rect()
                    root.getWindowVisibleDisplayFrame(r)
                    val screenH = root.rootView.height
                    val visibleH = r.height()
                    if (screenH <= 0) return@OnGlobalLayoutListener
                    val keyboardH = screenH - visibleH
                    val keyboardVisible = keyboardH > (screenH * 0.15)
                    if (keyboardVisible && !lastKeyboardVisible) {
                        contentView.post { ensureCursorVisible() }
                    }
                    lastKeyboardVisible = keyboardVisible
                }
            keyboardListener = listener
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }

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
                contentView.post { ensureCursorVisible() }
                setLoadingState(false, "就绪")
            } catch (t: Throwable) {
                setLoadingState(false, "加载失败：${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    override fun onDestroy() {
        val root = rootContentView
        val listener = keyboardListener
        if (root != null && listener != null) {
            root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        super.onDestroy()
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
                contentView.post { ensureCursorVisible() }
                setLoadingState(false, "已恢复默认")
            } catch (t: Throwable) {
                setLoadingState(false, "恢复失败：${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    private fun ensureCursorVisible() {
        val layout = contentView.layout ?: return
        val pos = contentView.selectionStart
        if (pos < 0) return
        val line = layout.getLineForOffset(pos)
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val available = contentView.height - contentView.totalPaddingTop - contentView.totalPaddingBottom
        if (available <= 0) return

        val lineTop = top - contentView.totalPaddingTop
        val lineBottom = bottom + contentView.totalPaddingBottom
        val cur = contentView.scrollY

        val target =
            when {
                lineTop < cur -> lineTop
                lineBottom > cur + available -> lineBottom - available
                else -> cur
            }
        val newY = target.coerceAtLeast(0)
        if (newY != cur) contentView.scrollTo(0, newY)
    }
}
