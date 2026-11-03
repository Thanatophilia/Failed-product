package com.silentime.ime.settings

/**
 * Stage 4.4.1（修订版）：键盘外观设置的数据模型与默认值。
 *
 * 修订要点（用户批准，取代旧三档方案）：
 *  - 高度设置改为两个百分比滑块：竖屏 25%~45%、横屏 35%~65%，步进 1%。
 *  - 两个设置独立保存，修改后立即生效，切换横竖屏时自动读取对应设置。
 *  - 默认值 35% / 49% 与现有基线键盘尺寸表现完全一致。
 *  - 最终实际高度仍受原 dp 安全边界约束（竖屏 180~320、横屏 120~260）。
 *  - 主题仍为三档：深色 / 浅色 / 高对比（默认深色，保持基线视觉）。
 *
 * 纯数据 / 常量，不涉及 Context、View 或存储。
 */
object KeyboardSettings {

    // ---------- 键盘高度（百分比滑块参数） ----------

    /** 竖屏高度百分比范围（滑块：步进 1%） */
    const val PORTRAIT_MIN_PERCENT = 25
    const val PORTRAIT_MAX_PERCENT = 45

    /** 竖屏默认百分比 = 基线 35%（当前键盘尺寸表现） */
    const val DEFAULT_PORTRAIT_PERCENT = 35

    /** 横屏高度百分比范围（滑块：步进 1%） */
    const val LANDSCAPE_MIN_PERCENT = 35
    const val LANDSCAPE_MAX_PERCENT = 65

    /** 横屏默认百分比 = 基线 49% */
    const val DEFAULT_LANDSCAPE_PERCENT = 49

    // ---------- 键盘主题 ----------

    enum class ThemePreset(val storageKey: String) {
        /** 深色（默认）：保留当前键盘颜色 */
        DARK("dark"),

        /** 浅色：浅色背景、深色文字 */
        LIGHT("light"),

        /** 高对比：强化按键与文字辨识度 */
        HIGH_CONTRAST("high_contrast");

        companion object {
            /**
             * 从存储值解析；缺失 / 损坏 / 未知一律回退默认（DARK）。
             */
            fun fromStorage(value: String?): ThemePreset = when (value) {
                LIGHT.storageKey -> LIGHT
                HIGH_CONTRAST.storageKey -> HIGH_CONTRAST
                else -> DARK
            }
        }
    }

    /** 主题颜色（对应 KeyboardView 的 6 个绘制色） */
    data class ThemeColors(
        val bg: Int,
        val key: Int,
        val keyPressed: Int,
        val funcKey: Int,
        val label: Int,
        val labelPressed: Int,
    )

    /**
     * 三套预设主题颜色表。
     * DARK 与基线 KeyboardView 硬编码色完全一致（保证默认主题视觉效果不变）。
     * 颜色均为通用色值，不包含任何厂商专属颜色或设备像素。
     */
    val THEME_COLORS: Map<ThemePreset, ThemeColors> = mapOf(
        ThemePreset.DARK to ThemeColors(
            bg = 0xFF1B1E26.toInt(),          // 背景
            key = 0xFF2C3140.toInt(),          // 普通键
            keyPressed = 0xFF4C8DFF.toInt(),   // 按下/高亮
            funcKey = 0xFF232733.toInt(),      // 功能键
            label = 0xFFE8ECF2.toInt(),        // 文字
            labelPressed = 0xFF0E1118.toInt(), // 按下文字
        ),
        ThemePreset.LIGHT to ThemeColors(
            bg = 0xFFF2F3F5.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFF4C8DFF.toInt(),
            funcKey = 0xFFE4E6EB.toInt(),
            label = 0xFF1A1D24.toInt(),
            labelPressed = 0xFFFFFFFF.toInt(),
        ),
        ThemePreset.HIGH_CONTRAST to ThemeColors(
            bg = 0xFF000000.toInt(),
            key = 0xFF333333.toInt(),
            keyPressed = 0xFFFFD600.toInt(),
            funcKey = 0xFF1A1A1A.toInt(),
            label = 0xFFFFFFFF.toInt(),
            labelPressed = 0xFF000000.toInt(),
        ),
    )
}