package com.silentime.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.silentime.ime.keyboard.KeyboardView
import com.silentime.ime.keyboard.LayoutMode

/**
 * SilentIME 输入法服务（Stage 3：键盘 UI + 基础英文输入）。
 *
 * 已实现：
 *  - Canvas 自绘 QWERTY 键盘
 *  - 字母 / 数字 / 符号输入
 *  - Shift 大小写切换（一次性）
 *  - 数字符号布局切换
 *  - Backspace 删除（含长按连删）
 *  - 空格 / 回车（按 ImeOptions 智能选择动作）
 *  - 按键按下视觉反馈
 *
 * 隐私立场（项目硬性约束，不得破坏）：
 *  - 不申请 INTERNET 权限，输入内容不出设备
 *  - 不采集、不落盘、不上传任何键入内容与历史
 *  - 密码框（password inputType）不记忆、不联想
 *  - 后续 AI 功能必须用户显式触发，密钥绝不硬编码
 */
class SilentImeService : InputMethodService(), KeyboardView.Listener {

    companion object {
        private const val TAG = "SilentIME/Service"

        /** 约束在 debug 构建打印诊断日志（release 自动关闭） */
        private val DEBUG = BuildConfig.DEBUG

        private fun logD(msg: String) {
            if (DEBUG) Log.d(TAG, msg)
        }
    }

    private var keyboardView: KeyboardView? = null

    /** 当前 EditorInfo，用于决定回车键行为 */
    private var currentEditorInfo: EditorInfo? = null

    /** Shift 是否激活（一次性） */
    private var shiftActive = false

    // ---------- 生命周期 ----------

    override fun onCreateInputView(): View {
        val view = KeyboardView(this)
        view.listener = this
        keyboardView = view
        logD("onCreateInputView newView=$view")
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        currentEditorInfo = attribute
        // 每次进入新输入框都从小写开始，避免 Shift 状态残留
        shiftActive = false
        keyboardView?.let { kb ->
            if (kb.mode == LayoutMode.LETTERS_UPPER) {
                kb.mode = LayoutMode.LETTERS_LOWER
            }
            kb.enterLabel = resolveEnterLabel(attribute)
        }
        logD(
            "onStartInput restarting=$restarting inputType=${attribute?.inputType} " +
                "imeOptions=${attribute?.imeOptions}"
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        shiftActive = false
        keyboardView?.let { kb ->
            kb.mode = LayoutMode.LETTERS_LOWER
            kb.enterLabel = resolveEnterLabel(info)
        }
        logD(
            "onStartInputView restarting=$restarting inputType=${info?.inputType} " +
                "imeOptions=${info?.imeOptions} kb=${keyboardView} " +
                "attachSeq=${keyboardView?.attachSeq} windowVisible=${isInputViewShown}"
        )
    }

    override fun onFinishInput() {
        logD("onFinishInput kb=${keyboardView}")
        super.onFinishInput()
        currentEditorInfo = null
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        logD("onFinishInputView finishingInput=$finishingInput kb=${keyboardView}")
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        val kb = keyboardView
        // 尽力获取导航模式标签（Stage 3.2.1 诊断用；失败时为 "n/a"）
        if (kb != null && kb.debugNavMode == "n/a") {
            kb.debugNavMode = try {
                val mode = android.provider.Settings.Secure.getInt(
                    contentResolver,
                    "navigation_mode",
                )
                when (mode) {
                    0 -> "3button"
                    1 -> "gestural_2button"
                    2 -> "gestural"
                    else -> "nav=$mode"
                }
            } catch (_: Exception) {
                "n/a"
            }
        }
        // API 31+ 系统推荐 IME 窗口高度（含 inset），用于诊断热切换预算
        val recH = if (android.os.Build.VERSION.SDK_INT >= 31) {
            getInputMethodWindowRecommendedHeight()
        } else {
            -1
        }
        logD(
            "onWindowShown kb=$kb attachSeq=${kb?.attachSeq} " +
                "windowVisible=${isInputViewShown} navMode=${kb?.debugNavMode} " +
                "imeRecH=$recH"
        )
        // Stage 3.2.1 实验结论：此处 requestRemeasure() 会让系统把 IME 窗口
        // 预算从"含 inset 的总预算(875)"改为"不含 inset 的内容预算(743)"，
        // View 因此少 132px（键盘变矮回归）。故不再调用，恢复系统自然协商。
        // 热切换钳制由 KeyboardView.onMeasure 的 cachedBudgetH 处理。
    }

    override fun onWindowHidden() {
        logD("onWindowHidden kb=${keyboardView} windowVisible=${isInputViewShown}")
        super.onWindowHidden()
    }

    override fun onDestroy() {
        logD("onDestroy kb=${keyboardView}")
        keyboardView?.listener = null
        keyboardView = null
        super.onDestroy()
    }

    // ---------- 安全工具 ----------

    /**
     * 取当前 InputConnection。
     * 某些输入框可能返回 null，所有调用点都必须容忍。
     */
    private fun currentImeConnection(): InputConnection? = currentInputConnection

    /** 当前是否处于密码输入框（用于关闭任何记忆/联想能力） */
    private fun isPasswordField(): Boolean {
        val info = currentEditorInfo ?: return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        return cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    // ---------- KeyboardView.Listener 实现 ----------

    override fun onKeyText(text: String) {
        // 仅提交到当前输入框，不落盘、不缓存、不上传
        currentImeConnection()?.commitText(text, 1)
    }

    override fun onKeyBackspace() {
        deleteBackward()
    }

    override fun onKeyBackspaceRepeat() {
        deleteBackward()
    }

    /**
     * 删除光标前一个字符。
     *
     * 优先用 deleteSurroundingText(1, 0)，这是"光标前一个字符"的标准语义。
     * 部分输入框（尤其 WebView / 自定义编辑器）支持不佳，
     * 此时退化为发送 DEL 键事件作为兜底。
     */
    private fun deleteBackward() {
        val ic = currentImeConnection() ?: return

        // 有选区时先删除选区（符合用户直觉）
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }

        val deleted = ic.deleteSurroundingText(1, 0)
        if (!deleted) {
            // 兜底：模拟物理退格键
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    /**
     * 回车键：依据输入框的 imeOptions / inputType 决定行为。
     *  - 多行输入框 → 换行
     *  - 搜索框 → 触发搜索
     *  - 发送框 → 触发发送
     *  - 其它 → 默认 ACTION_DONE
     */
    override fun onKeyEnter() {
        val ic = currentImeConnection() ?: return
        val info = currentEditorInfo

        val imeOptions = info?.imeOptions ?: 0
        val actionId = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

        val inputType = info?.inputType ?: 0
        val isMultiline = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        // 多行输入框：换行
        if (isMultiline && (noEnterAction || actionId == EditorInfo.IME_ACTION_UNSPECIFIED)) {
            ic.commitText("\n", 1)
            return
        }

        when (actionId) {
            EditorInfo.IME_ACTION_NONE -> {
                ic.commitText("\n", 1)
            }

            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS,
            -> {
                ic.performEditorAction(actionId)
            }

            else -> {
                if (isMultiline) {
                    ic.commitText("\n", 1)
                } else {
                    ic.performEditorAction(EditorInfo.IME_ACTION_DONE)
                }
            }
        }
    }

    override fun onShiftToggle() {
        shiftActive = !shiftActive
        keyboardView?.updateShiftState(shiftActive)
    }

    override fun onLayoutModeChange(mode: LayoutMode) {
        val kb = keyboardView ?: return
        when (mode) {
            LayoutMode.LETTERS_UPPER -> {
                shiftActive = true
                kb.mode = LayoutMode.LETTERS_UPPER
            }

            LayoutMode.LETTERS_LOWER -> {
                shiftActive = false
                kb.mode = LayoutMode.LETTERS_LOWER
            }

            LayoutMode.SYMBOLS -> {
                shiftActive = false
                kb.mode = LayoutMode.SYMBOLS
            }
        }
    }

    // ---------- 辅助 ----------

    /** 根据 ImeOptions 决定回车键显示文案 */
    private fun resolveEnterLabel(info: EditorInfo?): String {
        val actionId = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val isMultiline = ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        return when {
            actionId == EditorInfo.IME_ACTION_SEARCH -> "\uD83D\uDD0D" // 🔍
            actionId == EditorInfo.IME_ACTION_SEND -> "\u27A4"          // ➤
            actionId == EditorInfo.IME_ACTION_GO -> "\u21B5"            // ↵
            actionId == EditorInfo.IME_ACTION_NEXT -> "\u21E5"          // ⇥
            actionId == EditorInfo.IME_ACTION_DONE -> "\u2713"          // ✓
            isMultiline -> "\u23CE"                                     // ⏎
            else -> "\u23CE"
        }
    }
}