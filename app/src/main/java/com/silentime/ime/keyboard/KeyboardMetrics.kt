package com.silentime.ime.keyboard

import android.graphics.RectF
import com.silentime.ime.settings.KeyboardSettings

/**
 * 键盘尺寸计算（纯函数，不依赖 View、不持有状态）。
 *
 * 所有高度 / 按键尺寸公式集中在此，与绘制、触摸逻辑完全解耦，
 * 便于统一调整与在 DEVLOG 中记录。
 *
 * 设计：
 *  1. 键盘总高度 = 内容高度 + 底部系统栏（导航栏/手势条）区域；
 *     内容高度 = 可用窗口高度 × 比例（36%~42%），夹在 dp 上下限之间。
 *  2. 横屏与竖屏参数分离，横屏不沿用竖屏尺寸。
 *  3. 按键宽高、间距全部基于内容区域动态计算（weight 分摊宽度）。
 */
object KeyboardMetrics {

    // ---------- 高度参数 ----------

    /** 竖屏：目标比例 35%（用户要求，键盘更矮），dp 上下限 180~320 */
    private const val MIN_DP_PORTRAIT = 180
    private const val MAX_DP_PORTRAIT = 320

    /** 横屏：目标比例 49%（用户指定），dp 上下限 120~260（49% 在大屏/高 density 下也需兜底） */
    private const val MIN_DP_LANDSCAPE = 120
    private const val MAX_DP_LANDSCAPE = 260

    // ---------- 输出结构 ----------

    /** 单个按键及其在 View 坐标系中的矩形（已含 offsetX 偏移） */
    data class KeyBounds(val key: Key, val rect: RectF)

    // ---------- 高度计算 ----------

    /**
     * 键盘 View 总高度（px）。
     *
     *     totalHeight = contentHeight + bottomInsetPx
     *
     * 其中 bottomInsetPx 由 WindowInsets 提供（导航栏/手势条高度，禁止写死）。
     * inset 区域用于绘制背景色以保持视觉连续，但不参与按键布局，
     * 从而保证按键永不遮挡导航栏、也不会产生双重留白。
     *
     * @param portraitPercent 竖屏目标百分比（25~45，默认 35 与基线一致）
     * @param landscapePercent 横屏目标百分比（35~65，默认 49 与基线一致）
     */
    fun totalHeight(
        windowHeightPx: Int,
        bottomInsetPx: Int,
        isLandscape: Boolean,
        density: Float,
        portraitPercent: Int = KeyboardSettings.DEFAULT_PORTRAIT_PERCENT,
        landscapePercent: Int = KeyboardSettings.DEFAULT_LANDSCAPE_PERCENT,
    ): Int = contentHeight(
        windowHeightPx,
        isLandscape,
        density,
        portraitPercent,
        landscapePercent,
    ) + bottomInsetPx

    /**
     * 键盘主体（按键区）内容高度：可用高度 × 对应方向百分比，夹在 dp 上下限之间。
     *
     * 百分比入参由调用方（KeyboardView）从设置仓库读取；未显式传入时用基线默认值
     * （竖屏 35% / 横屏 49%），保证任何漏传路径都与基线表现完全一致。
     */
    fun contentHeight(
        windowHeightPx: Int,
        isLandscape: Boolean,
        density: Float,
        portraitPercent: Int = KeyboardSettings.DEFAULT_PORTRAIT_PERCENT,
        landscapePercent: Int = KeyboardSettings.DEFAULT_LANDSCAPE_PERCENT,
    ): Int {
        val percent = if (isLandscape) landscapePercent else portraitPercent
        val ratio = percent / 100f
        val minPx = (if (isLandscape) MIN_DP_LANDSCAPE else MIN_DP_PORTRAIT) * density
        val maxPx = (if (isLandscape) MAX_DP_LANDSCAPE else MAX_DP_PORTRAIT) * density
        return (windowHeightPx * ratio).toInt().coerceIn(minPx.toInt(), maxPx.toInt())
    }

    // ---------- 按键布局计算 ----------

    /**
     * 计算各键矩形（View 坐标系，内容区域已由调用方扣除 Insets）。
     *
     * 公式：
     *  - 行高 = (内容高 - 总行间距) / 行数
     *  - 行间距 = 内容高 × 2%
     *  - 键宽 = (内容宽 - 两侧 padding) / 总 weight × 该键 weight
     *  - padding = 内容宽 × 1.2%；键间 gap = padding × 0.5
     *
     * @param contentWidth  内容区域宽度（View 宽 - 左右 Insets）
     * @param contentHeight 内容区域高度（View 高 - 底部 Insets）
     * @param offsetX       内容区域相对 View 原点的水平偏移（横屏左侧系统栏时 > 0）
     */
    fun computeBounds(
        contentWidth: Float,
        contentHeight: Float,
        offsetX: Float,
        mode: LayoutMode,
        enterLabel: String,
    ): List<KeyBounds> {
        val rows = KeyboardLayout.rows(mode)
        if (rows.isEmpty()) return emptyList()

        val result = ArrayList<KeyBounds>(rows.size * 10)

        val rowCount = rows.size
        // Stage 4.2.1：水平 padding 由 1.2% 收紧至 0.6%，键区向左右填充（留白减半，gap 联动缩小）。
        // 仅水平坐标受影响；高度/行距/Insets 一律不变。
        val padding = contentWidth * 0.006f
        val rowGap = contentHeight * 0.02f
        val totalRowGap = rowGap * (rowCount - 1)
        val rowHeight = (contentHeight - totalRowGap) / rowCount

        // Stage 4.1 水平布局公共参数（仅水平坐标；高度/行距/Insets 一律不变）
        val availWidth = contentWidth - padding * 2   // 内容可用宽度 W
        val gap = padding * 0.5f                        // 键间距 G（与基线一致）
        val maxKeyCount = rows.maxOf { it.size }        // 最多键数行 Smax(=10)

        rows.forEachIndexed { rowIndex, row ->
            val top = rowIndex * (rowHeight + rowGap)
            val bottom = top + rowHeight

            // Stage 4.1 梯形错位（对齐 Fcitx5）：仅水平坐标计算；高度/行距/Insets 一律不变。
            // 行宽只由权重决定（T×U），gap 是键间视觉间距（键槽内缩进），不参与行宽——
            //   R1(10U) 与 R3(10U) 因此左右完全对齐；R2(9U) 居中后左右各缩进 0.5U；
            //   R4(8U) 整行居中。此与基线 gap 语义一致，避免重复计算间距。
            val totalWeight = KeyboardLayout.totalWeight(row)
            val unitWidth = (availWidth - gap * (maxKeyCount - 1)) / maxKeyCount
            val rowWidth = totalWeight * unitWidth
            val rowLeft = padding + (availWidth - rowWidth) / 2f

            var left = rowLeft
            row.forEach { key ->
                // 回车键文案可能被 service 改写
                val actualKey = if (key.type == KeyType.ENTER) {
                    key.copy(label = enterLabel)
                } else {
                    key
                }
                val keyWidth = unitWidth * key.weight
                val rect = RectF(
                    left + gap + offsetX,
                    top + rowGap * 0.4f,
                    left + keyWidth - gap + offsetX,
                    bottom - rowGap * 0.4f,
                )
                result.add(KeyBounds(actualKey, rect))
                left += keyWidth
            }
        }
        return result
    }
}