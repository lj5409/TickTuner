package com.gtool.core.music

/**
 * 一个可弹奏的指法。
 *
 * frets 顺序与 Tuning.strings 一致：低音弦 → 高音弦。
 * fret = -1 表示闷音，0 表示空弦，>0 表示相对品丝（不含变调夹）。
 */
data class Voicing(
    val frets: List<Int>,
    val fingers: List<Int>,
    val baseFret: Int,
    val score: Int = 0
) {
    val muted: List<Boolean> get() = frets.map { it < 0 }

    val soundedStrings: List<Int> get() = frets.indices.filter { frets[it] >= 0 }

    /** 需要按下的最低品（0 表示有空弦）。 */
    val minPressed: Int get() = frets.filter { it > 0 }.minOrNull() ?: 0

    /** 按下的最高品。 */
    val maxPressed: Int get() = frets.maxOrNull() ?: 0

    /** 指法跨度（品）。 */
    val span: Int get() = (maxPressed - minPressed).coerceAtLeast(0)

    val isOpenPosition: Boolean get() = frets.any { it == 0 }

    fun fretAt(stringIndex: Int): Int = frets.getOrElse(stringIndex) { -1 }

    fun fingerAt(stringIndex: Int): Int = fingers.getOrElse(stringIndex) { 0 }
}
