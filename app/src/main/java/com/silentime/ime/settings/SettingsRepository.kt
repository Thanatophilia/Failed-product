package com.silentime.ime.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Stage 4.4.1（修订版）：统一设置管理层（SharedPreferences 唯一访问点）。
 *
 * - 键名固定：height_portrait_percent / height_landscape_percent / keyboard_theme。
 * - 高度百分比独立保存（竖屏 / 横屏各一个），读取带合法性校验，
 *   缺失 / 损坏 / 越界一律回退默认（竖屏 35%、横屏 49%）。
 * - 写入立即持久化（apply）。
 * - 通过 [SharedPreferences.OnSharedPreferenceChangeListener] 支持应用内监听，
 *   由 InputMethodService 按生命周期注册，实现"设置修改后立即生效"。
 *
 * 本类不存储任何用户输入内容，不引入 INTERNET 权限。
 */
object SettingsRepository {

    private const val PREFS_NAME = "silentime_settings"

    /** 竖屏高度百分比键（存储值：25~45 的整数） */
    const val KEY_HEIGHT_PORTRAIT = "height_portrait_percent"

    /** 横屏高度百分比键（存储值：35~65 的整数） */
    const val KEY_HEIGHT_LANDSCAPE = "height_landscape_percent"

    /** 键盘主题键（存储值：dark / light / high_contrast） */
    const val KEY_THEME = "keyboard_theme"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 幂等初始化；由 MainActivity / InputMethodService 传入任意 Context。
     */
    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun p(): SharedPreferences {
        return prefs ?: error("SettingsRepository 未初始化，请先在 Application/Service/Activity 调用 init()")
    }

    // ---------- 读取（含合法性校验 / 回退默认） ----------

    /** 竖屏高度百分比（默认 35；越界回退默认） */
    fun portraitPercent(): Int {
        val v = p().getInt(KEY_HEIGHT_PORTRAIT, KeyboardSettings.DEFAULT_PORTRAIT_PERCENT)
        return if (v in KeyboardSettings.PORTRAIT_MIN_PERCENT..KeyboardSettings.PORTRAIT_MAX_PERCENT) {
            v
        } else {
            KeyboardSettings.DEFAULT_PORTRAIT_PERCENT
        }
    }

    /** 横屏高度百分比（默认 49；越界回退默认） */
    fun landscapePercent(): Int {
        val v = p().getInt(KEY_HEIGHT_LANDSCAPE, KeyboardSettings.DEFAULT_LANDSCAPE_PERCENT)
        return if (v in KeyboardSettings.LANDSCAPE_MIN_PERCENT..KeyboardSettings.LANDSCAPE_MAX_PERCENT) {
            v
        } else {
            KeyboardSettings.DEFAULT_LANDSCAPE_PERCENT
        }
    }

    fun themePreset(): KeyboardSettings.ThemePreset =
        KeyboardSettings.ThemePreset.fromStorage(p().getString(KEY_THEME, null))

    // ---------- 写入（立即持久化） ----------

    fun setPortraitPercent(percent: Int) {
        val clamped = percent.coerceIn(
            KeyboardSettings.PORTRAIT_MIN_PERCENT,
            KeyboardSettings.PORTRAIT_MAX_PERCENT,
        )
        p().edit().putInt(KEY_HEIGHT_PORTRAIT, clamped).apply()
    }

    fun setLandscapePercent(percent: Int) {
        val clamped = percent.coerceIn(
            KeyboardSettings.LANDSCAPE_MIN_PERCENT,
            KeyboardSettings.LANDSCAPE_MAX_PERCENT,
        )
        p().edit().putInt(KEY_HEIGHT_LANDSCAPE, clamped).apply()
    }

    fun setThemePreset(preset: KeyboardSettings.ThemePreset) {
        p().edit().putString(KEY_THEME, preset.storageKey).apply()
    }

    // ---------- 应用内监听（Service 生命周期注册，实时生效） ----------

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        p().registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        p().unregisterOnSharedPreferenceChangeListener(listener)
    }
}