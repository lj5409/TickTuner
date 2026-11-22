package com.gtool.feature.metronome

import com.gtool.core.audio.BeatEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatPulseStateTest {

    @Test
    fun screenFlash_onlyFollowsMainBeats_ignoresSubdivisions() {
        val pulse = BeatPulseState()
        val now = System.nanoTime()

        // 1) 主拍（subIndex = 0）命中后，全屏闪烁与拍条均被点亮
        pulse.enqueue(
            BeatEvent(
                beatIndex = 0,
                subIndex = 0,
                isAccent = true,
                nanoTime = now + 50_000_000L,
                bpm = 120,
                level = 2
            )
        )
        // 2) 细分拍（subIndex = 1）在 250ms 后命中：全屏闪烁必须保持已衰减状态，不能重新跳高
        pulse.enqueue(
            BeatEvent(
                beatIndex = 0,
                subIndex = 1,
                isAccent = true,
                nanoTime = now + 300_000_000L,
                bpm = 120,
                level = 2
            )
        )

        val mainFlash = pulse.frame(now + 55_000_000L, avOffsetMs = 0, style = "flash")
        assertEquals(1f, mainFlash, 0.01f)
        assertTrue("拍条脉冲在主拍命中时应明显点亮", pulse.barIntensity > 0.8f)

        // 到达细分拍时刻（距离主拍 250ms，强闪 65ms 早已熄灭）
        val subFlash = pulse.frame(now + 305_000_000L, avOffsetMs = 0, style = "flash")
        assertEquals("屏幕闪烁不应响应细分拍", 0f, subFlash, 0.001f)
        assertEquals(1, pulse.activeSub)
        assertTrue("拍条仍应为细分拍提供轻量脉冲反馈", pulse.barIntensity > 0.4f)
    }

    @Test
    fun mutedMainBeat_doesNotFlashScreen_butPulsesBar() {
        val pulse = BeatPulseState()
        val now = System.nanoTime()
        pulse.enqueue(
            BeatEvent(
                beatIndex = 1,
                subIndex = 0,
                isAccent = false,
                nanoTime = now + 50_000_000L,
                bpm = 120,
                level = 0
            )
        )

        val flash = pulse.frame(now + 55_000_000L, avOffsetMs = 0, style = "pulse")
        assertEquals(0f, flash, 0.001f)
        assertTrue("静音拍在拍条上应有柔和微闪", pulse.barIntensity > 0.3f)
    }
}
