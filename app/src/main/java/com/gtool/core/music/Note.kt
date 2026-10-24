package com.gtool.core.music

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 十二平均律音级。semitone 以 C = 0 计。
 */
enum class PitchClass(val semitone: Int, val sharpName: String, val flatName: String) {
    C(0, "C", "C"),
    C_SHARP(1, "C#", "Db"),
    D(2, "D", "D"),
    D_SHARP(3, "D#", "Eb"),
    E(4, "E", "E"),
    F(5, "F", "F"),
    F_SHARP(6, "F#", "Gb"),
    G(7, "G", "G"),
    G_SHARP(8, "G#", "Ab"),
    A(9, "A", "A"),
    A_SHARP(10, "A#", "Bb"),
    B(11, "B", "B");

    fun name(useFlats: Boolean): String = if (useFlats) flatName else sharpName

    companion object {
        fun of(semitone: Int): PitchClass = entries[((semitone % 12) + 12) % 12]

        /** 支持 "C"、"C#"、"Db"、"Bb" 等写法。 */
        fun parse(text: String): PitchClass? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val base = when (t[0].uppercaseChar()) {
                'C' -> C
                'D' -> D
                'E' -> E
                'F' -> F
                'G' -> G
                'A' -> A
                'B' -> B
                else -> return null
            }
            return when {
                t.length == 1 -> base
                t[1] == '#' || t[1] == '♯' -> of(base.semitone + 1)
                t[1] == 'b' || t[1] == '♭' -> of(base.semitone - 1)
                else -> null
            }
        }
    }
}

/**
 * 带八度的具体音，例如 E2（吉他 6 弦空弦）。
 */
data class Note(val pitchClass: PitchClass, val octave: Int) : Comparable<Note> {

    val midi: Int get() = (octave + 1) * 12 + pitchClass.semitone

    fun frequency(a4: Double = 440.0): Double = a4 * 2.0.pow((midi - 69) / 12.0)

    fun name(useFlats: Boolean = false): String = pitchClass.name(useFlats) + octave

    override fun compareTo(other: Note): Int = midi.compareTo(other.midi)

    companion object {
        fun fromMidi(midi: Int): Note =
            Note(PitchClass.of(midi), Math.floorDiv(midi, 12) - 1)

        /** 由频率反推最接近的平均律音。 */
        fun fromFrequency(hz: Double, a4: Double = 440.0): Note =
            fromMidi((69 + 12 * log2(hz / a4)).roundToInt())

        /** 支持 "E2"、"C#4"、"Bb3"、"eb2" 等写法。 */
        fun parse(text: String): Note? {
            val t = text.trim()
            if (t.length < 2) return null
            val match = Regex("^([A-Ga-g][#b♯♭]?)(-?\\d{1,2})$").find(t) ?: return null
            val pc = PitchClass.parse(match.groupValues[1]) ?: return null
            val octave = match.groupValues[2].toIntOrNull() ?: return null
            if (octave !in 0..9) return null
            return Note(pc, octave)
        }

        fun centsBetween(frequency: Double, target: Double): Double =
            1200.0 * log2(frequency / target)

        private fun log2(x: Double): Double = ln(x) / ln(2.0)
    }
}

/**
 * 音分偏差：正值表示偏高。
 */
fun centsFrom(frequency: Double, target: Double): Double =
    1200.0 * (ln(frequency / target) / ln(2.0))

/**
 * MIDI 音高在给定采样率下的频率。
 */
fun midiToFrequency(midi: Int, a4: Double = 440.0): Double =
    a4 * 2.0.pow((midi - 69) / 12.0)
