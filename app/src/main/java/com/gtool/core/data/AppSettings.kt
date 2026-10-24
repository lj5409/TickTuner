package com.gtool.core.data

import kotlinx.serialization.Serializable

/** 节拍器设置。持久化到 DataStore。 */
@Serializable
data class MetronomeSettings(
    val bpm: Int = 120,
    val beatsPerBar: Int = 4,
    val beatUnit: Int = 4,
    /** 每拍细分数（1 = 不细分）。 */
    val subdivision: Int = 1,
    /** 每拍重音等级：0 静音、1 普通、2 重音。长度可小于 beatsPerBar，循环使用。 */
    val accentPattern: List<Int> = listOf(2, 1, 1, 1),
    val soundEnabled: Boolean = true,
    val screenEnabled: Boolean = false,
    val torchEnabled: Boolean = false,
    val vibrateEnabled: Boolean = false,
    /** ARGB 颜色，用 Long 存储避免无符号问题。 */
    val flashColor: Long = 0xFF2EE6A8,
    val flashBrightness: Float = 0.85f,
    /** pulse = 柔和脉冲，flash = 强闪。 */
    val flashStyle: String = "pulse",
    /** 闪光灯强度档位，1..maxLevel。 */
    val torchStrength: Int = 1,
    /** 音画校准偏移（毫秒），正值表示画面提前。 */
    val avOffsetMs: Int = 0
)

/** 全局设置。 */
@Serializable
data class AppSettings(
    val a4: Double = 440.0,
    val useFlats: Boolean = false,
    val capo: Int = 0,
    val lastTuningId: String = "standard",
    val showNoteNamesOnFretboard: Boolean = true,
    /** 分解和弦拨弦顺序（弦号 1–6，默认 53231323）。 */
    val arpeggioPattern: String = "53231323",
    val metronome: MetronomeSettings = MetronomeSettings()
)

data class AppState(
    val settings: AppSettings = AppSettings(),
    val customTunings: List<com.gtool.core.music.Tuning> = emptyList()
) {
    val allTunings: List<com.gtool.core.music.Tuning>
        get() = com.gtool.core.music.TuningLibrary.all(customTunings)

    val activeTuning: com.gtool.core.music.Tuning
        get() = com.gtool.core.music.TuningLibrary.find(settings.lastTuningId, customTunings)
}
