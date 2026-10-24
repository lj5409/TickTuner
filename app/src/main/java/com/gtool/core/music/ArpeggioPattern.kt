package com.gtool.core.music

/**
 * 分解和弦的拨弦顺序。
 *
 * 用吉他弦号书写：1 = 高音 E（最细），6 = 低音 E（最粗），
 * 例如经典指法 `53231323`。
 */
object ArpeggioPattern {

    const val DEFAULT: String = "53231323"
    const val MAX_LENGTH: Int = 16
    const val MIN_STRING: Int = 1
    const val MAX_STRING: Int = 6

    /** 只保留 1–6 的数字，最多 16 步；允许为空（编辑中间态）。 */
    fun sanitize(raw: String): String =
        raw.filter { it in '1'..'6' }.take(MAX_LENGTH)

    /** 用于播放/持久化：为空时回退到默认 53231323。 */
    fun normalize(raw: String): String =
        sanitize(raw).ifEmpty { DEFAULT }

    /**
     * 弦号 → [Voicing.frets] 下标（0 = 最低音弦）。
     * 越界返回 -1。
     */
    fun stringIndex(digit: Char, stringCount: Int = 6): Int {
        if (stringCount <= 0) return -1
        val number = digit - '0'
        if (number !in MIN_STRING..MAX_STRING) return -1
        return (stringCount - number).takeIf { it in 0 until stringCount } ?: -1
    }

    /** 把顺序转换为指法下标列表；非法或超出弦数的项会被丢弃。 */
    fun indicesFor(raw: String, stringCount: Int = 6): List<Int> =
        normalize(raw).map { digit -> stringIndex(digit, stringCount) }.filter { it >= 0 }
}
