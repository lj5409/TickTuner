package com.gtool.core.device

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 振动节拍控制器。
 *
 * 用 [VibrationEffect.createOneShot]：重音 60 ms / 255，普通 30 ms / 140；
 * 细分拍用更短更轻的一下（16 ms / 90），避免高频细分时持续震动。
 * 没有振动器或没有振幅控制能力时静默降级，不抛异常。
 */
class VibratorController(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.applicationContext
                .getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    /** 是否支持振动（无振动器时为 false，调用方据此提示/降级）。 */
    val isSupported: Boolean = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)

    private val hasAmplitudeControl: Boolean =
        runCatching { vibrator?.hasAmplitudeControl() == true }.getOrDefault(false)

    /**
     * 打一拍。
     *
     * @param isAccent 重音（60 ms / 255）。
     * @param isSubdivision 细分拍（16 ms / 轻振），优先于普通拍。
     */
    fun pulse(isAccent: Boolean, isSubdivision: Boolean = false) {
        val device = vibrator ?: return
        if (!isSupported) return
        val duration: Long
        val amplitude: Int
        when {
            isSubdivision -> {
                duration = 16L
                amplitude = 90
            }

            isAccent -> {
                duration = 60L
                amplitude = 255
            }

            else -> {
                duration = 30L
                amplitude = 140
            }
        }
        runCatching {
            val effect = if (hasAmplitudeControl) {
                VibrationEffect.createOneShot(duration, amplitude)
            } else {
                VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            device.vibrate(effect)
        }
    }

    /** 停止当前振动（例如暂停时）。 */
    fun cancel() {
        runCatching { vibrator?.cancel() }
    }

    /** 释放引用；无系统资源需要显式关闭。 */
    fun release() {
        cancel()
    }
}
