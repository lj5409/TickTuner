package com.gtool.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 可选音频输入设备条目（内置麦克风、外接 USB 指向麦、有线耳麦等）。
 */
data class AudioInputOption(
    val id: Int,
    val label: String,
    val detail: String,
    val isExternal: Boolean,
    val deviceInfo: AudioDeviceInfo?
) {
    companion object {
        const val AUTO_ID = -1
        val AUTO = AudioInputOption(
            id = AUTO_ID,
            label = "系统默认（自动）",
            detail = "优先 UNPROCESSED 原始拾音，插外接麦自动跟随系统路由",
            isExternal = false,
            deviceInfo = null
        )
    }
}

/**
 * 枚举并热监听 Android 音频输入设备（支持手机内置麦与外接 USB/有线指向麦）。
 */
class AudioInputDeviceManager(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow(listOf(AudioInputOption.AUTO))
    val devices: StateFlow<List<AudioInputOption>> = _devices.asStateFlow()

    private var registered = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refresh()
        }
    }

    fun start() {
        refresh()
        if (!registered) {
            runCatching {
                audioManager.registerAudioDeviceCallback(callback, mainHandler)
                registered = true
            }
        }
    }

    fun stop() {
        if (registered) {
            runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
            registered = false
        }
    }

    fun refresh() {
        val raw = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        }.getOrElse { emptyArray() }

        val options = mutableListOf(AudioInputOption.AUTO)
        var builtInIndex = 0
        for (info in raw) {
            if (!isSupportedMicType(info.type)) continue
            val isExt = info.type != AudioDeviceInfo.TYPE_BUILTIN_MIC
            val typeTag = typeLabel(info.type)
            val product = info.productName?.toString()?.trim().orEmpty()
            val label = when {
                info.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> {
                    builtInIndex++
                    val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        info.address.takeIf { it.isNotBlank() && it != "bottom" && it != "back" }
                    } else null
                    val locSuffix = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.address == "bottom" -> "（底部主麦）"
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.address == "back" -> "（背部副麦）"
                        builtInIndex == 1 -> "（主麦）"
                        else -> " #$builtInIndex"
                    }
                    "手机内置麦克风$locSuffix" + (addr?.let { " [$it]" } ?: "")
                }

                product.isNotEmpty() -> "$typeTag · $product"
                else -> typeTag
            }
            val rates = info.sampleRates
            val rateInfo = if (rates.isEmpty() || rates.contains(48_000)) {
                "支持 48 kHz"
            } else {
                "支持 ${rates.maxOrNull() ?: 44_100} Hz"
            }
            options.add(
                AudioInputOption(
                    id = info.id,
                    label = label,
                    detail = if (isExt) "外接输入 · $rateInfo" else "内置输入 · $rateInfo",
                    isExternal = isExt,
                    deviceInfo = info
                )
            )
        }
        _devices.value = options
    }

    private fun isSupportedMicType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true

        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 外接麦"

        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线麦克风"
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "线性音频输入"

        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙麦克风"
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {
            "BLE 音频麦"
        } else {
            "外接麦克风"
        }
    }
}
