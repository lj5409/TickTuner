package com.gtool.core.music

/**
 * 和弦性质。intervals 为相对根音的半音数，degrees 为对应的音级写法。
 */
enum class ChordQuality(
    val id: String,
    val displayName: String,
    val symbol: String,
    val intervals: List<Int>,
    val degrees: List<String>
) {
    MAJOR("maj", "大三和弦", "", listOf(0, 4, 7), listOf("1", "3", "5")),
    MINOR("min", "小三和弦", "m", listOf(0, 3, 7), listOf("1", "b3", "5")),
    DOM7("7", "属七和弦", "7", listOf(0, 4, 7, 10), listOf("1", "3", "5", "b7")),
    MAJ7("maj7", "大七和弦", "maj7", listOf(0, 4, 7, 11), listOf("1", "3", "5", "7")),
    MIN7("m7", "小七和弦", "m7", listOf(0, 3, 7, 10), listOf("1", "b3", "5", "b7")),
    HALF_DIM7("m7b5", "半减七和弦", "m7b5", listOf(0, 3, 6, 10), listOf("1", "b3", "b5", "b7")),
    DIM("dim", "减三和弦", "dim", listOf(0, 3, 6), listOf("1", "b3", "b5")),
    DIM7("dim7", "减七和弦", "dim7", listOf(0, 3, 6, 9), listOf("1", "b3", "b5", "bb7")),
    AUG("aug", "增三和弦", "aug", listOf(0, 4, 8), listOf("1", "3", "#5")),
    SUS2("sus2", "挂二和弦", "sus2", listOf(0, 2, 7), listOf("1", "2", "5")),
    SUS4("sus4", "挂四和弦", "sus4", listOf(0, 5, 7), listOf("1", "4", "5")),
    SIX("6", "六和弦", "6", listOf(0, 4, 7, 9), listOf("1", "3", "5", "6")),
    MIN6("m6", "小六和弦", "m6", listOf(0, 3, 7, 9), listOf("1", "b3", "5", "6")),
    ADD9("add9", "加九和弦", "add9", listOf(0, 4, 7, 14), listOf("1", "3", "5", "9")),
    NINE("9", "九和弦", "9", listOf(0, 4, 7, 10, 14), listOf("1", "3", "5", "b7", "9")),
    MAJ9("maj9", "大九和弦", "maj9", listOf(0, 4, 7, 11, 14), listOf("1", "3", "5", "7", "9")),
    MIN9("m9", "小九和弦", "m9", listOf(0, 3, 7, 10, 14), listOf("1", "b3", "5", "b7", "9")),
    POWER5("5", "强力和弦", "5", listOf(0, 7), listOf("1", "5"));

    companion object {
        fun fromId(id: String): ChordQuality? = entries.firstOrNull { it.id == id }
    }
}

data class ChordTone(
    val pitchClass: PitchClass,
    val degree: String,
    val semitone: Int
)

/**
 * 一个和弦：根音 + 性质。组成音按音级顺序排列。
 */
data class Chord(val root: PitchClass, val quality: ChordQuality) {

    val tones: List<ChordTone> = quality.intervals.mapIndexed { index, semitone ->
        ChordTone(PitchClass.of(root.semitone + semitone), quality.degrees[index], semitone)
    }

    val pitchClasses: List<PitchClass> = tones.map { it.pitchClass }

    val displayName: String get() = root.sharpName + quality.symbol

    /** 组成音在指定八度上的具体音高。 */
    fun notes(baseOctave: Int = 3): List<Note> {
        val baseMidi = (baseOctave + 1) * 12 + root.semitone
        return tones.map { Note.fromMidi(baseMidi + it.semitone) }
    }

    fun contains(pitchClass: PitchClass): Boolean = pitchClasses.contains(pitchClass)
}

object ChordLibrary {
    fun of(root: PitchClass, quality: ChordQuality): Chord = Chord(root, quality)

    fun all(): List<Chord> = PitchClass.entries.flatMap { root ->
        ChordQuality.entries.map { quality -> Chord(root, quality) }
    }
}
