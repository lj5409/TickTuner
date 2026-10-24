package com.gtool.core.device

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** 闪光灯能力。API 33+ 且 maxStrengthLevel > 1 时才支持亮度调节。 */
data class TorchCapabilities(
    val available: Boolean = false,
    val cameraId: String? = null,
    val maxStrengthLevel: Int = 1,
    val supportsStrength: Boolean = false,
    val error: String? = null
) {
    val supportsStrengthControl: Boolean
        get() = available && supportsStrength && maxStrengthLevel > 1
}

/**
 * 闪光灯节拍控制器。
 *
 * - 探测后置带闪光灯相机，读取 [CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL]。
 * - API 33+ 且支持强度时用 [CameraManager.turnOnTorchWithStrengthLevel]，否则退化为开关。
 * - 所有相机调用在单一后台线程串行执行，全部 try/catch，失败通过 [errors] 上报，不崩溃。
 * - 脉冲由调用方按拍点时间触发：[pulse] 点亮并预约 [durationMs] 后熄灭；
 *   若下一次脉冲到来时尚未熄灭，则顺延熄灭时间（连续点亮时不反复开关 LED）。
 */
class TorchController(context: Context) {

    companion object {
        private const val TAG = "TorchController"

        /** 默认点亮时长，处于需求要求的 40–60 ms 区间。 */
        const val DEFAULT_PULSE_MS = 50L
    }

    private val cameraManager =
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "gtool-torch").apply { isDaemon = true }
        }

    @Volatile
    private var capabilities = TorchCapabilities()

    @Volatile
    private var strengthLevel = 1

    @Volatile
    private var torchOn = false

    /** 是否“请求过点亮”。点亮失败时也要保证后续能尝试熄灭。 */
    @Volatile
    private var torchRequestedOn = false

    @Volatile
    private var released = false

    private var offFuture: ScheduledFuture<*>? = null

    private val _errors = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 相机相关的可读错误（SecurityException、被占用等）。 */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** 最近一次探测结果（未探测时为 available = false）。 */
    val state: TorchCapabilities
        get() = capabilities

    /**
     * 探测闪光灯能力。会做相机 Binder 调用，建议在 IO 线程执行，不要在 UI 线程频繁调用。
     */
    fun probe(): TorchCapabilities {
        if (released) return capabilities
        val manager = cameraManager
        if (manager == null) {
            return TorchCapabilities(error = "本机没有相机服务，无法使用闪光灯").also {
                capabilities = it
            }
        }
        return try {
            val ids = manager.cameraIdList
            var backWithFlash: String? = null
            var anyWithFlash: String? = null
            for (id in ids) {
                val chars = manager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (!hasFlash) continue
                if (anyWithFlash == null) anyWithFlash = id
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    backWithFlash = id
                    break
                }
            }
            val target = backWithFlash ?: anyWithFlash
            if (target == null) {
                TorchCapabilities(available = false, error = "未找到带闪光灯的相机").also {
                    capabilities = it
                }
            } else {
                var maxLevel = 1
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    maxLevel = runCatching {
                        manager.getCameraCharacteristics(target)
                            .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                    }.getOrDefault(1)
                }
                if (maxLevel < 1) maxLevel = 1
                TorchCapabilities(
                    available = true,
                    cameraId = target,
                    maxStrengthLevel = maxLevel,
                    supportsStrength = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxLevel > 1
                ).also { result ->
                    capabilities = result
                    strengthLevel = strengthLevel.coerceIn(1, maxLevel)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "探测闪光灯失败", t)
            TorchCapabilities(error = "闪光灯检测失败：${describe(t)}").also { capabilities = it }
        }
    }

    /** 设置 1..max 的强度档位，仅支持强度的设备生效。 */
    fun setStrength(level: Int) {
        val max = capabilities.maxStrengthLevel.coerceAtLeast(1)
        strengthLevel = level.coerceIn(1, max)
    }

    /**
     * 点亮 [durationMs] 毫秒后熄灭。可在任意线程调用；命令在后台线程串行执行。
     */
    fun pulse(durationMs: Long = DEFAULT_PULSE_MS) {
        if (released) return
        val caps = capabilities
        if (!caps.available) {
            _errors.tryEmit(caps.error ?: "闪光灯不可用")
            return
        }
        val cameraId = caps.cameraId ?: return
        val duration = durationMs.coerceIn(20L, 120L)
        submit {
            try {
                if (!torchOn) {
                    torchRequestedOn = true
                    if (caps.supportsStrengthControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val level = strengthLevel.coerceIn(1, caps.maxStrengthLevel.coerceAtLeast(1))
                        cameraManager?.turnOnTorchWithStrengthLevel(cameraId, level)
                    } else {
                        cameraManager?.setTorchMode(cameraId, true)
                    }
                    torchOn = true
                }
                offFuture?.cancel(false)
                offFuture = scheduler.schedule(
                    { turnOffInternal() },
                    duration,
                    TimeUnit.MILLISECONDS
                )
            } catch (t: Throwable) {
                torchOn = false
                reportFailure(t, "点亮")
            }
        }
    }

    /** 立即熄灭（例如暂停/退出）。 */
    fun turnOff() {
        if (released) return
        submit { turnOffInternal() }
    }

    /** 关闭闪光灯并结束后台线程。 */
    fun release() {
        if (released) return
        released = true
        submit { turnOffInternal() }
        // shutdown 会执行已排队的熄灭任务；不阻塞主线程等待。
        runCatching { scheduler.shutdown() }
    }

    private fun turnOffInternal() {
        offFuture?.cancel(false)
        offFuture = null
        torchOn = false
        if (!torchRequestedOn) return
        torchRequestedOn = false
        val cameraId = capabilities.cameraId ?: return
        try {
            cameraManager?.setTorchMode(cameraId, false)
        } catch (t: Throwable) {
            reportFailure(t, "熄灭")
        }
    }

    private fun submit(task: () -> Unit) {
        try {
            scheduler.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    reportFailure(t, "操作")
                }
            }
        } catch (_: RejectedExecutionException) {
            // release 之后不再接受新任务，直接忽略。
        }
    }

    private fun reportFailure(t: Throwable, action: String) {
        Log.w(TAG, "闪光灯$action 失败", t)
        torchOn = false
        val message = when (t) {
            is SecurityException -> "闪光灯被系统拒绝：可能缺少相机权限，或被其他应用占用"
            is IllegalStateException -> "闪光灯当前不可用：相机可能被其他应用占用"
            is IllegalArgumentException -> "闪光灯参数不被支持（${t.message ?: "未知"}）"
            else -> "闪光灯$action 失败：${describe(t)}"
        }
        _errors.tryEmit(message)
    }

    private fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
}
