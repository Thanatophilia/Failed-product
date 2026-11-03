package com.silentime.ime.keyboard

/**
 * 单个按键的类型。
 * 用枚举而非字符串，避免运行期拼写错误，也便于 when 穷举。
 */
enum class KeyType {
    /** 普通字符键：字母、数字、符号 */
    CHARACTER,

    /** Shift 大小写切换（一次性，输入一个字母后自动复位） */
    SHIFT,

    /** Backspace 退格删除 */
    BACKSPACE,

    /** 空格 */
    SPACE,

    /** 回车 / 换行 / 搜索等动作键 */
    ENTER,

    /** 切换到数字符号布局 */
    SYMBOLS,

    /** 切回字母布局 */
    LETTERS,
}

/**
 * 键盘上一个按键的数据模型。
 *
 * 这是一个纯数据类，不包含任何绘制或触摸逻辑，
 * 绘制与命中检测由 [KeyboardView] 负责。
 *
 * @param type       按键类型
 * @param label      显示文字（字母键为小写基准，Shift 时由视图自行转大写）
 * @param value      实际提交的字符；功能键为 null
 * @param weight     横向宽度权重，1.0 为标准键宽
 */
data class Key(
    val type: KeyType,
    val label: String,
    val value: String? = null,
    val weight: Float = 1f,
) {
    /** 是否为字符键（可被 Shift 影响） */
    val isCharacter: Boolean get() = type == KeyType.CHARACTER

    /** 是否为可打印字母（a-z / A-Z），用于判断 Shift 是否生效 */
    val isLetter: Boolean
        get() = isCharacter && value != null && value.length == 1 && value[0].isLetter()

    companion object {
        fun char(value: String, weight: Float = 1f): Key =
            Key(KeyType.CHARACTER, value, value, weight)

        fun shift(): Key = Key(KeyType.SHIFT, "\u21E7", weight = 1.5f)

        fun backspace(): Key = Key(KeyType.BACKSPACE, "\u232B", weight = 1.5f)

        fun space(): Key = Key(KeyType.SPACE, "", " ", weight = 5f)

        fun enter(label: String = "\u23CE"): Key =
            Key(KeyType.ENTER, label, "\n", weight = 1.5f)

        fun symbols(): Key = Key(KeyType.SYMBOLS, "?123", weight = 1.5f)

        fun letters(): Key = Key(KeyType.LETTERS, "ABC", weight = 1.5f)
    }
}