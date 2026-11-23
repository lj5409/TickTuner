package com.gtool.feature.acoustic

import com.gtool.core.acoustic.SpectrumChartOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumChartHelpersTest {

    @Test
    fun cycleSpectrumFrequencyRange_cyclesThroughThreePresetsCleanly() {
        val modal = SpectrumChartOptions(minHz = 60.0, maxHz = 600.0)
        val mid = cycleSpectrumFrequencyRange(modal)
        assertEquals(50.0, mid.minHz, 0.01)
        assertEquals(1200.0, mid.maxHz, 0.01)

        val full = cycleSpectrumFrequencyRange(mid)
        assertEquals(50.0, full.minHz, 0.01)
        assertEquals(5000.0, full.maxHz, 0.01)

        val backToModal = cycleSpectrumFrequencyRange(full)
        assertEquals(60.0, backToModal.minHz, 0.01)
        assertEquals(600.0, backToModal.maxHz, 0.01)
    }

    @Test
    fun formatCursorHzLabel_formatsSub1kWithDecimalAndAbove1kAsInteger() {
        assertEquals("98.4 Hz", formatCursorHzLabel(98.44))
        assertEquals("182.6 Hz", formatCursorHzLabel(182.62))
        assertEquals("1420 Hz", formatCursorHzLabel(1420.3))
        assertEquals("5000 Hz", formatCursorHzLabel(5000.0))
    }

    @Test
    fun spectrumRangeShortLabel_returnsReadablePresetNames() {
        assertEquals("60–600 Hz", spectrumRangeShortLabel(SpectrumChartOptions(60.0, 600.0)))
        assertEquals("50–1.2k Hz", spectrumRangeShortLabel(SpectrumChartOptions(50.0, 1200.0)))
        assertEquals("50–5k Hz", spectrumRangeShortLabel(SpectrumChartOptions(50.0, 5000.0)))
    }

    @Test
    fun formatSingleSpectrumReadout_formatsNoteCentsAndDb() {
        assertEquals("A4(+0¢) · -3.2 dB", formatSingleSpectrumReadout(440.0, -3.24, 440.0, useFlats = false))
    }
}
