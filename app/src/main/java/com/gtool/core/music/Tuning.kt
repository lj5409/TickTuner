package com.gtool.core.music

/**
 * 一套调弦。strings 顺序为「低音弦 → 高音弦」，即 6 弦 → 1 弦。
 */
data class Tuning(
    val id: String,
    val name: String,
    val strings: List<Note>,
    val builtIn: Boolean = false
) {
    val stringCount: Int get() = strings.size

    fun names(useFlats: Boolean = false): List<String> = strings.map { it.name(useFlats) }

    fun joinedNames(useFlats: Boolean = false): String = names(useFlats).joinToString(" ")

    /** 各弦音名紧凑拼接（不含八度），如 EADGBE / DADGBE / DADGAD。 */
    fun compactPitchNames(useFlats: Boolean = false): String =
        strings.joinToString("") { it.pitchClass.name(useFlats) }

    /** index 0 = 最低音弦（吉他 6 弦）。 */
    fun stringNumber(index: Int): Int = stringCount - index

    fun copyAsCustom(newName: String, newId: String = "custom_${System.currentTimeMillis()}"): Tuning =
        Tuning(id = newId, name = newName, strings = strings.toList(), builtIn = false)
}

object TuningLibrary {

    private fun builtIn(id: String, name: String, vararg notes: String): Tuning =
        Tuning(id, name, notes.map { Note.parse(it) ?: error("bad note $it") }, builtIn = true)

    val STANDARD = builtIn("standard", "标准调弦 EADGBE", "E2", "A2", "D3", "G3", "B3", "E4")
    val DROP_D = builtIn("drop_d", "Drop D", "D2", "A2", "D3", "G3", "B3", "E4")
    val HALF_STEP_DOWN = builtIn("half_step_down", "降半音 Eb", "D#2", "G#2", "C#3", "F#3", "A#3", "D#4")
    val FULL_STEP_DOWN = builtIn("full_step_down", "降全音 D", "D2", "G2", "C3", "F3", "A3", "D4")
    val OPEN_G = builtIn("open_g", "Open G", "D2", "G2", "D3", "G3", "B3", "D4")
    val OPEN_D = builtIn("open_d", "Open D", "D2", "A2", "D3", "F#3", "A3", "D4")
    val OPEN_DM = builtIn("open_dm", "Open Dm", "D2", "A2", "D3", "F3", "A3", "D4")
    val DADGAD = builtIn("dadgad", "DADGAD", "D2", "A2", "D3", "G3", "A3", "D4")
    val OPEN_C = builtIn("open_c", "Open C", "C2", "G2", "C3", "G3", "C4", "E4")

    val builtIns: List<Tuning> = listOf(
        STANDARD, DROP_D, HALF_STEP_DOWN, FULL_STEP_DOWN,
        OPEN_G, OPEN_D, OPEN_DM, DADGAD, OPEN_C
    )

    fun blank(name: String = "自定义调弦", base: Tuning = STANDARD): Tuning =
        base.copyAsCustom(name, "custom_${System.currentTimeMillis()}")

    fun find(id: String, custom: List<Tuning>): Tuning =
        (builtIns + custom).firstOrNull { it.id == id } ?: STANDARD

    fun all(custom: List<Tuning>): List<Tuning> = builtIns + custom

    /**
     * 按用户自定义顺序排列全部调弦（内置 + 自定义混合排序）。
     * - [order] 为空时保持默认顺序（内置在前、自定义在后）；
     * - 自动忽略已删除或重复的 id；
     * - 未出现在 [order] 中的新增调弦追加到末尾。
     */
    fun orderedAll(custom: List<Tuning>, order: List<String>): List<Tuning> {
        val available = builtIns + custom
        if (order.isEmpty()) return available
        val byId = available.associateBy { it.id }
        val result = ArrayList<Tuning>(available.size)
        val seen = HashSet<String>(available.size)
        for (id in order) {
            val tuning = byId[id]
            if (tuning != null && seen.add(id)) {
                result.add(tuning)
            }
        }
        for (tuning in available) {
            if (seen.add(tuning.id)) {
                result.add(tuning)
            }
        }
        return result
    }

    /** 将列表中 [fromIndex] 位置的项移动到 [toIndex]，越界或同位置时返回原列表。 */
    fun <T> moveItem(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex == toIndex || fromIndex !in items.indices || toIndex !in items.indices) {
            return items
        }
        val mutable = items.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable
    }
}
