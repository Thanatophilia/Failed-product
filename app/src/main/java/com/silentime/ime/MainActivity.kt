package com.silentime.ime

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
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

        setContentView(root)
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
}