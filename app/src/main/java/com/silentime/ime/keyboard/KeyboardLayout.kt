package com.silentime.ime.keyboard

/**
 * 键盘布局定义。
 *
 * 纯数据 + 纯函数，不涉及任何 View / Context，
 * 因此可以被单测覆盖，也便于后续扩展中文拼音布局。
 */
enum class LayoutMode {
    /** 小写字母 */
    LETTERS_LOWER,

    /** 大写字母 */
    LETTERS_UPPER,

    /** 数字与符号 */
    SYMBOLS,
}

object KeyboardLayout {

    /** QWERTY 三行字母 */
    private val ROW_1 = "qwertyuiop"
    private val ROW_2 = "asdfghjkl"
    private val ROW_3 = "zxcvbnm"

    /** 数字行 */
    private val NUM_ROW_1 = "1234567890"
    private val NUM_ROW_2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")")
    private val NUM_ROW_3 = listOf("*", "\"", "'", ":", ";", "!", "?")

    /**
     * 返回指定模式的全部行。
     *
     * @return 二维列表，外层是行，内层是该行的按键
     */
    fun rows(mode: LayoutMode): List<List<Key>> = when (mode) {
        LayoutMode.LETTERS_LOWER -> letterRows(upper = false)
        LayoutMode.LETTERS_UPPER -> letterRows(upper = true)
        LayoutMode.SYMBOLS -> symbolRows()
    }

    private fun letterRows(upper: Boolean): List<List<Key>> {
        fun letters(s: String): List<Key> = s.map { ch ->
            val c = if (upper) ch.uppercaseChar() else ch
            Key.char(c.toString())
        }

        return listOf(
            letters(ROW_1),
            letters(ROW_2),
            // 第三行：Shift + 字母 + Backspace
            buildList {
                add(Key.shift())
                addAll(letters(ROW_3))
                add(Key.backspace())
            },
            // 第四行：符号切换 + 空格 + 回车
            listOf(Key.symbols(), Key.space(), Key.enter()),
        )
    }

    private fun symbolRows(): List<List<Key>> {
        fun chars(list: List<String>): List<Key> = list.map { Key.char(it) }

        return listOf(
            NUM_ROW_1.map { Key.char(it.toString()) },
            chars(NUM_ROW_2),
            buildList {
                add(Key.letters())
                addAll(chars(NUM_ROW_3))
                add(Key.backspace())
            },
            // 底部行：ABC 切换 + 空格 + 回车
            listOf(Key.letters(), Key.space(), Key.enter()),
        )
    }

    /**
     * 单行内所有按键的总权重，供 [KeyboardView] 按比例分配宽度。
     */
    fun totalWeight(row: List<Key>): Float = row.sumOf { it.weight.toDouble() }.toFloat()
}