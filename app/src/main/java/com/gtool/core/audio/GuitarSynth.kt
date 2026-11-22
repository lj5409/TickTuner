package com.gtool.core.audio

import com.gtool.core.music.midiToFrequency
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 吉他拨弦音源：Karplus-Strong 物理建模。
 *
 * - 48 kHz / 单声道 / Float PCM。
 * - 延迟线（带线性插值的小数延迟保证音准）+ 环路低通 + 三角拨弦波形与低通噪声混合激励。
 * - 指数衰减 2–4 秒（低音更长），再经过轻微琴体 EQ 与首尾淡入淡出。
 * - 每个 MIDI 音高的 PCM 只合成一次并缓存；[render] 会按力度返回缩放后的副本，
 *   [pcm] 直接返回缓存（调用方不要修改数组）。
 *
 * 纯 Kotlin，无 Android 依赖；可在 JVM 上预热、测试。
 */
class GuitarSynth(private val sampleRate: Int = SAMPLE_RATE) {

    private val pcmCache = HashMap<Int, FloatArray>()
    private var cachedA4DeciHz: Int = (DEFAULT_A4 * 10.0).roundToInt()

    /**
     * 返回指定音高、力度的 PCM。velocity 会等比缩放振幅（0.8 为基准力度）。
     * 每次调用都会新建数组，适合离线处理；实时播放请用 [pcm] + 混音增益。
     */
    fun render(
        midi: Int,
        velocity: Float = REFERENCE_VELOCITY,
        a4: Double = DEFAULT_A4
    ): FloatArray {
        val base = pcm(midi, a4)
        val gain = (velocity.coerceIn(0f, 1f) / REFERENCE_VELOCITY).coerceIn(0f, 1.25f)
        return FloatArray(base.size) { index -> base[index] * gain }
    }

    /** 缓存的原始 PCM（基准力度 0.8），跟随 [a4] 基准音高。调用方不得修改返回数组。 */
    fun pcm(midi: Int, a4: Double = DEFAULT_A4): FloatArray {
        val safeMidi = midi.coerceIn(MIN_MIDI, MAX_MIDI)
        val safeA4 = a4.coerceIn(400.0, 480.0)
        val deciHz = (safeA4 * 10.0).roundToInt()
        synchronized(pcmCache) {
            if (deciHz != cachedA4DeciHz) {
                pcmCache.clear()
                cachedA4DeciHz = deciHz
            }
            pcmCache[safeMidi]?.let { return it }
            val rendered = synthesize(safeMidi, safeA4)
            pcmCache[safeMidi] = rendered
            return rendered
        }
    }

    /** 预渲染一段音域，建议在后台线程调用。 */
    fun warmUp(range: IntRange = DEFAULT_WARM_UP_RANGE, a4: Double = DEFAULT_A4) {
        for (midi in range) {
            if (midi in MIN_MIDI..MAX_MIDI) pcm(midi, a4)
        }
    }

    fun clearCache() {
        synchronized(pcmCache) { pcmCache.clear() }
    }

    // ------------------------------------------------------------------
    // 合成
    // ------------------------------------------------------------------

    private fun synthesize(midi: Int, a4: Double): FloatArray {
        val frequency = midiToFrequency(midi, a4).toFloat()
        val period = sampleRate / frequency
        val integerDelay = floor(period).toInt().coerceAtLeast(2)

        // 低音衰减慢、高音衰减快，整体落在 2–4 秒。
        val t60 = (3.6f - (midi - 40) * (0.8f / 36f)).coerceIn(2.8f, 3.6f)
        val length = (sampleRate * t60).toInt().coerceAtLeast(integerDelay * 8)

        val bufferSize = integerDelay + 4
        val delayLine = FloatArray(bufferSize)
        val random = Random(midi * 7919 + 17)

        // 激励：60% 低通噪声（拨弦瞬态）+ 40% 三角拨弦波形（保证基频与自然谐波）。
        // 三角波峰值位置对应拨弦点，约在弦长的 15% 处。
        val pickFraction = 0.15f
        val noise = FloatArray(integerDelay)
        var lowPassed = 0f
        for (i in 0 until integerDelay) {
            val white = random.nextFloat() * 2f - 1f
            lowPassed = lowPassed * 0.65f + white * 0.35f
            noise[i] = lowPassed
        }
        var noiseMean = 0f
        for (value in noise) noiseMean += value
        noiseMean /= noise.size

        val triangle = FloatArray(integerDelay) { i ->
            val x = i.toFloat() / integerDelay
            if (x < pickFraction) x / pickFraction else (1f - x) / (1f - pickFraction)
        }
        var triangleMean = 0f
        for (value in triangle) triangleMean += value
        triangleMean /= triangle.size

        var noisePeak = 0f
        var trianglePeak = 0f
        for (i in 0 until integerDelay) {
            noisePeak = max(noisePeak, abs(noise[i] - noiseMean))
            trianglePeak = max(trianglePeak, abs(triangle[i] - triangleMean))
        }
        val noiseScale = if (noisePeak > 1e-6f) trianglePeak / noisePeak else 0f

        var smooth = 0f
        for (i in 0 until integerDelay) {
            val value = 0.4f * (triangle[i] - triangleMean) +
                0.6f * (noise[i] - noiseMean) * noiseScale
            smooth = smooth * 0.5f + value * 0.5f
            delayLine[i] = smooth
        }
        var excitationMean = 0f
        for (i in 0 until integerDelay) excitationMean += delayLine[i]
        excitationMean /= integerDelay
        var excitationPeak = 0f
        for (i in 0 until integerDelay) {
            delayLine[i] -= excitationMean
            excitationPeak = max(excitationPeak, abs(delayLine[i]))
        }
        if (excitationPeak > 1e-5f) {
            val normalize = 0.9f / excitationPeak
            for (i in 0 until integerDelay) delayLine[i] *= normalize
        }

        // 每次绕延迟线一圈的增益，使 t60 秒后衰减到 -60 dB。
        val loopGain = exp(ln(0.001f) * period / (t60 * sampleRate)).toFloat()
            .coerceIn(0.9f, 0.9995f)
        // 一阶 FIR 低通：越小阻尼越强、高频衰减越快。0.55 兼顾拨弦亮度与温暖感。
        val damping = 0.55f

        val output = FloatArray(length)
        var writeIndex = integerDelay
        var previous = 0f
        for (n in 0 until length) {
            val readPosition = writeIndex - period
            var baseIndex = floor(readPosition).toInt()
            val frac = readPosition - baseIndex
            baseIndex = ((baseIndex % bufferSize) + bufferSize) % bufferSize
            val nextIndex = (baseIndex + 1) % bufferSize
            val delayed = delayLine[baseIndex] * (1f - frac) + delayLine[nextIndex] * frac

            val filtered = damping * delayed + (1f - damping) * previous
            previous = delayed
            delayLine[writeIndex] = filtered * loopGain
            output[n] = delayed

            writeIndex++
            if (writeIndex >= bufferSize) writeIndex = 0
        }

        applyBodyEq(output)
        applyFades(output)
        normalize(output, TARGET_PEAK)
        return output
    }

    private fun applyBodyEq(data: FloatArray) {
        if (data.isEmpty()) return
        // 去掉直流与最低频轰鸣。
        highPassOnePole(data, 48.0)
        // 琴体共鸣：约 190 Hz 轻微提升，900 Hz 附近略收，2.6 kHz 增加一点拨弦质感。
        peakingEq(data, 190.0, 0.9, 3.0)
        peakingEq(data, 900.0, 1.2, -1.8)
        peakingEq(data, 2600.0, 0.8, 1.2)
        lowPassOnePole(data, 7200.0)
    }

    private fun applyFades(data: FloatArray) {
        if (data.isEmpty()) return
        val fadeIn = min(sampleRate / 500, data.size / 4).coerceAtLeast(1) // 约 2 ms
        for (i in 0 until fadeIn) {
            data[i] *= i.toFloat() / fadeIn
        }
        val fadeOut = min(sampleRate / 10, data.size / 3).coerceAtLeast(1) // 约 100 ms
        val start = data.size - fadeOut
        for (i in 0 until fadeOut) {
            data[start + i] *= 1f - i.toFloat() / fadeOut
        }
    }

    private fun normalize(data: FloatArray, peak: Float) {
        var maxValue = 0f
        for (value in data) maxValue = max(maxValue, abs(value))
        if (maxValue < 1e-5f) return
        val gain = peak / maxValue
        for (i in data.indices) data[i] *= gain
    }

    private fun highPassOnePole(data: FloatArray, frequency: Double) {
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * PI * frequency)
        val alpha = (rc / (rc + dt)).toFloat()
        var previousInput = 0f
        var previousOutput = 0f
        for (i in data.indices) {
            val input = data[i]
            val output = alpha * (previousOutput + input - previousInput)
            previousInput = input
            previousOutput = output
            data[i] = output
        }
    }

    private fun lowPassOnePole(data: FloatArray, frequency: Double) {
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * PI * frequency)
        val alpha = (dt / (rc + dt)).toFloat()
        var previous = 0f
        for (i in data.indices) {
            previous += alpha * (data[i] - previous)
            data[i] = previous
        }
    }

    /** RBJ Audio EQ Cookbook 的 peaking EQ，原地处理。 */
    private fun peakingEq(data: FloatArray, frequency: Double, q: Double, gainDb: Double) {
        if (abs(gainDb) < 0.01) return
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * frequency / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha / a

        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in data.indices) {
            val x = data[i].toDouble()
            val y = (b0 / a0) * x + (b1 / a0) * x1 + (b2 / a0) * x2 -
                (a1 / a0) * y1 - (a2 / a0) * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            data[i] = y.toFloat()
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val DEFAULT_A4 = 440.0
        const val REFERENCE_VELOCITY = 0.8f
        val DEFAULT_WARM_UP_RANGE = 40..76 // E2..E5
        private const val TARGET_PEAK = 0.72f
        private const val MIN_MIDI = 24
        private const val MAX_MIDI = 108
    }
}
