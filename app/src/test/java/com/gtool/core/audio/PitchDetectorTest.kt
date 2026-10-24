package com.gtool.core.audio

import com.gtool.core.music.centsFrom
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * PitchDetector 的纯 JVM 测试：合成正弦 + 静音 + 白噪声。
 * 验收标准：音高误差 < 1 Hz 或 < 1 音分；静音/噪声不给出可信结果。
 */
class PitchDetectorTest {

    private val sampleRate = 48_000

    private fun sine(
        frequency: Double,
        size: Int = 4096,
        amplitude: Double = 0.45
    ): FloatArray = FloatArray(size) { index ->
        (amplitude * sin(2.0 * PI * frequency * index / sampleRate)).toFloat()
    }

    private fun assertDetects(frequency: Double, size: Int) {
        val detector = PitchDetector()
        val result = detector.detect(sine(frequency, size))
        assertNotNull("${frequency}Hz 应该有检测结果", result)
        val detected = result!!.frequency
        assertTrue(
            "${frequency}Hz 检测为 $detected Hz，误差超过 1 Hz",
            abs(detected - frequency) < 1.0
        )
        assertTrue(
            "${frequency}Hz 检测置信度过低：${result.confidence}",
            result.confidence >= 0.8
        )
    }

    @Test
    fun detectsLowE2() = assertDetects(82.4069, 4096)

    @Test
    fun detectsA2() = assertDetects(110.0, 4096)

    @Test
    fun detectsD3() = assertDetects(146.8324, 4096)

    @Test
    fun detectsE4() = assertDetects(329.6276, 4096)

    @Test
    fun detectsA4() {
        assertDetects(440.0, 4096)
        // 高音弦允许只给 2048 点
        assertDetects(440.0, 2048)
    }

    @Test
    fun a4ErrorIsBelowOneCent() {
        val result = PitchDetector().detect(sine(440.0, 4096))
        assertNotNull(result)
        val cents = centsFrom(result!!.frequency, 440.0)
        assertTrue("误差 $cents 音分", abs(cents) < 1.0)
    }

    @Test
    fun silenceReturnsNull() {
        assertNull(PitchDetector().detect(FloatArray(4096)))
        assertNull(PitchDetector().detect(FloatArray(4096) { 0.0001f }))
    }

    @Test
    fun whiteNoiseReturnsNullOrLowConfidence() {
        val random = Random(42L)
        val noise = FloatArray(4096) { (random.nextDouble() * 0.4 - 0.2).toFloat() }
        val result = PitchDetector().detect(noise)
        assertTrue(
            "白噪声不应给出高置信度结果：$result",
            result == null || result.confidence < 0.8
        )
    }
}
