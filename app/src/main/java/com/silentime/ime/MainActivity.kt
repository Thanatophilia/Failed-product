package com.silentime.ime

import android.database.ContentObserver
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.silentime.ime.settings.KeyboardSettings
import com.silentime.ime.settings.SettingsRepository

/**
 * Stage 4.4.1（修订版）：设置页。
 *
 * 交互设计：
 *  - 「竖屏键盘高度」SeekBar：25%~45%，步进 1%。
 *  - 「横屏键盘高度」SeekBar：35%~65%，步进 1%。
 *  - 两个设置独立保存（SharedPreferences），修改后立即生效（Service 监听重建）。
 *  - 切换横竖屏时键盘自动读取对应设置。
 *  - 「键盘主题」三档保留：深色 / 浅色 / 高对比（默认深色，视觉与基线一致）。
 *
 * 说明：默认值 35% / 49% 与基线键盘尺寸表现完全一致；
 * 最终实际高度仍受原 dp 安全边界约束（竖屏 180~320、横屏 120~260）。
 */
class MainActivity : AppCompatActivity() {

    /** Stage 4.4.2-R1：系统反馈状态提示（null = 尚未创建） */
    private var systemStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsRepository.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        // ---------- 标题 ----------
        root.addView(sectionTitle("SilentIME 设置"))

        // ---------- 竖屏高度滑块 ----------
        root.addView(sectionTitle("竖屏键盘高度"))
        val portraitLabel = valueLabel().also { root.addView(it) }
        val portraitBar = SeekBar(this).apply {
            max = KeyboardSettings.PORTRAIT_MAX_PERCENT - KeyboardSettings.PORTRAIT_MIN_PERCENT
            progress = SettingsRepository.portraitPercent() - KeyboardSettings.PORTRAIT_MIN_PERCENT
            // 步进 1%：progress 每 1 单位即 1% 百分比
        }
        root.addView(portraitBar)

        // ---------- 横屏高度滑块 ----------
        root.addView(sectionTitle("横屏键盘高度"))
        val landscapeLabel = valueLabel().also { root.addView(it) }
        val landscapeBar = SeekBar(this).apply {
            max = KeyboardSettings.LANDSCAPE_MAX_PERCENT - KeyboardSettings.LANDSCAPE_MIN_PERCENT
            progress = SettingsRepository.landscapePercent() - KeyboardSettings.LANDSCAPE_MIN_PERCENT
        }
        root.addView(landscapeBar)

        // ---------- 按键音效 / 震动（Stage 4.4.2，默认关闭） ----------
        root.addView(sectionTitle("按键反馈"))

        val soundRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 12)
        }
        soundRow.addView(
            TextView(this).apply {
                text = "按键音效"
                textSize = 16f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val soundSwitch = Switch(this).apply {
            isChecked = SettingsRepository.keySoundEnabled()
        }
        soundRow.addView(soundSwitch)
        root.addView(soundRow)

        val vibeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 12)
        }
        vibeRow.addView(
            TextView(this).apply {
                text = "按键震动"
                textSize = 16f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val vibeSwitch = Switch(this).apply {
            isChecked = SettingsRepository.keyVibrationEnabled()
        }
        vibeRow.addView(vibeSwitch)
        root.addView(vibeRow)

        // ---------- Stage 4.4.2-R1：系统反馈状态提示 ----------
        // 依赖系统总开关（Settings.System 触摸音效 / 触觉反馈）：
        //  SilentIME 开关只决定"是否请求系统反馈"，真正输出与否由系统总开关决定。
        //  这里是只读提示（不写入、不请求权限、不改变反馈调用链）：
        //  - soundSystemOn=false → 提示"系统触摸提示音已关闭"
        //  - vibeSystemOn=false  → 提示"系统触觉反馈已关闭"
        //  - 两个都开 → 绿色"状态正常"提示
        //  通过 ContentObserver 监听系统设置变化，返回页面时自动刷新。
        val systemStatusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 4, 0, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        systemStatus = systemStatusView
        refreshSystemStatusView()

        // 系统设置变化（如用户在系统设置里打开/关闭触摸音效/触觉）后自动刷新提示。
        // 读取 Settings.System 无需权限；注册的 URI 是系统设置整体，通知粒度粗但
        // 足够（仅影响本页一个 TextView，开销可忽略）。页面关闭时注销。
        val systemObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshSystemStatusView()
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            systemObserver,
        )
        root.addView(systemStatusView)

        // 页面关闭时注销观察者，防止泄漏
        lifecycle.addObserver(object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(
                source: androidx.lifecycle.LifecycleOwner,
                event: androidx.lifecycle.Lifecycle.Event,
            ) {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                    contentResolver.unregisterContentObserver(systemObserver)
                }
            }
        })

        // ---------- 主题三档 ----------
        root.addView(sectionTitle("键盘主题"))
        val themeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val currentTheme = SettingsRepository.themePreset()
        KeyboardSettings.ThemePreset.entries.forEach { preset ->
            val rb = RadioButton(this).apply {
                text = when (preset) {
                    KeyboardSettings.ThemePreset.DARK -> "深色（默认）"
                    KeyboardSettings.ThemePreset.LIGHT -> "浅色"
                    KeyboardSettings.ThemePreset.HIGH_CONTRAST -> "高对比"
                }
                tag = preset
                isChecked = preset == currentTheme
                setPadding(8, 12, 8, 12)
            }
            themeGroup.addView(rb)
        }
        root.addView(themeGroup)

        // ---------- 实时更新标签 ----------
        val refreshLabels = {
            portraitLabel.text =
                "当前：${SettingsRepository.portraitPercent()}%（范围 25~45，默认 35%）"
            landscapeLabel.text =
                "当前：${SettingsRepository.landscapePercent()}%（范围 35~65，默认 49%）"
        }
        refreshLabels()

        portraitBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val percent = KeyboardSettings.PORTRAIT_MIN_PERCENT + progress
                SettingsRepository.setPortraitPercent(percent)
                refreshLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        landscapeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val percent = KeyboardSettings.LANDSCAPE_MIN_PERCENT + progress
                SettingsRepository.setLandscapePercent(percent)
                refreshLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val rb = themeGroup.findViewById<RadioButton>(checkedId)
            val preset = rb?.tag as? KeyboardSettings.ThemePreset
                ?: return@setOnCheckedChangeListener
            SettingsRepository.setThemePreset(preset)
        }

        // Stage 4.4.2：按键反馈开关（独立保存 + 监听即时生效）
        soundSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsRepository.setKeySoundEnabled(isChecked)
            logD("keySoundEnabled -> $isChecked")
        }

        vibeSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsRepository.setKeyVibrationEnabled(isChecked)
            logD("keyVibrationEnabled -> $isChecked")
        }

        setContentView(root)
    }

    /**
     * 从系统设置页返回（或页面重新可见）时刷新系统反馈状态提示。
     * 与 ContentObserver 双保险：即使系统设置通知偶发丢失，回到前台也必然刷新。
     */
    override fun onResume() {
        super.onResume()
        refreshSystemStatusView()
    }

    /**
     * Stage 4.4.2-R1：刷新"系统反馈状态提示"文本与颜色。
     * 仅读取系统设置并更新 UI，不写入、不改权限、不动反馈调用链。
     */
    private fun refreshSystemStatusView() {
        val view = systemStatus ?: return
        val s = systemSoundEffectsEnabled()
        val v = systemHapticFeedbackEnabled()
        val parts = ArrayList<String>()
        if (!s) parts.add("系统触摸提示音已关闭，开启后才能听到按键音效。")
        if (!v) parts.add("系统触觉反馈已关闭，开启后才能感受到按键震动。")
        if (parts.isEmpty()) {
            view.text = "系统反馈状态正常：可听到按键音效并感受到按键震动。"
            view.setTextColor(0xFF7CB342.toInt()) // 绿色
        } else {
            view.text = parts.joinToString("\n")
            view.setTextColor(0xFFFFB300.toInt()) // 琥珀色
        }
        view.visibility = View.VISIBLE
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        setPadding(0, 32, 0, 12)
    }

    private fun valueLabel(): TextView = TextView(this).apply {
        textSize = 14f
        setTextColor(Color.LTGRAY)
        setPadding(0, 4, 0, 12)
    }

    /** Debug 诊断日志（仅 Debug 构建打印） */
    private fun logD(msg: String) {
        if (BuildConfig.DEBUG) Log.d("SilentIME/UI", msg)
    }

    // ---------- Stage 4.4.2-R1：系统反馈总开关只读检测 ----------

    /**
     * 系统"触摸提示音"总开关（Settings.System.SOUND_EFFECTS_ENABLED）。
     * 仅读取（普通权限即可，不需要 WRITE_SETTINGS）；读取失败按"开启"处理，
     * 避免个别机型缺失该键时误报提示干扰用户。
     */
    private fun systemSoundEffectsEnabled(): Boolean =
        Settings.System.getInt(
            contentResolver,
            Settings.System.SOUND_EFFECTS_ENABLED,
            1,
        ) == 1

    /**
     * 系统"触觉反馈"总开关（Settings.System.HAPTIC_FEEDBACK_ENABLED）。
     * 仅读取；读取失败按"开启"处理（理由同上）。
     */
    private fun systemHapticFeedbackEnabled(): Boolean =
        Settings.System.getInt(
            contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) == 1
}