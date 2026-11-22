package com.gtool.core.acoustic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

class AcousticAnalyzerTest {

    private val sr = 48_000
    private val fftSize = 65_536
    private val step = sr.toDouble() / fftSize
    private val binStart = (30.0 / step).toInt()
    private val binCount = ((6000.0 / step).toInt() - binStart + 1)

    /**
     * 构造物理逼真的多洛伦兹共振峰频谱（dBFS），便于精确验证解耦、诊断与评分规则。
     */
    private fun synthSpectrum(
        position: TapPosition,
        peaks: List<Triple<Double, Double, Double>>, // (freqHz, peakDbfs, Q)
        noiseFloorDbfs: Double = -82.0,
        highFreqLevelDbfs: Double = -50.0,
        notch: Triple<Double, Double, Double>? = null, // (freqHz, depthDb, widthHz)
        coherence: Double = 0.96
    ): PositionSpectrum {
        val mags = ArrayList<Float>(binCount)
        val floorPower = Math.pow(10.0, noiseFloorDbfs / 10.0)
        for (i in 0 until binCount) {
            val f = (binStart + i) * step
            var p = floorPower
            // 高频泛音基底轮廓
            if (f >= 600.0) {
                val roll = if (f > 4000.0) (f - 4000.0) / 1000.0 * 4.0 else 0.0
                val ripple = if (f in 1000.0..5000.0) 2.5 * Math.sin(f * 0.025) else 0.0
                p += Math.pow(10.0, (highFreqLevelDbfs - roll + ripple) / 10.0)
            }
            for ((f0, peakDb, q) in peaks) {
                val halfBw = f0 / (2.0 * q)
                val x = (f - f0) / halfBw
                val lorentz = 1.0 / (1.0 + x * x)
                p += Math.pow(10.0, peakDb / 10.0) * lorentz
            }
            var db = 10.0 * log10(p)
            if (notch != null) {
                val (nf, depth, width) = notch
                val d = (f - nf) / width
                db -= depth * Math.exp(-d * d)
            }
            mags.add(db.toFloat())
        }
        return PositionSpectrum(
            position = position,
            sampleRateHz = sr,
            fftSize = fftSize,
            noiseFloorDbfs = noiseFloorDbfs,
            coherenceScore = coherence,
            binStartIndex = binStart,
            freqStepHz = step,
            magnitudesDbfs = mags,
            avgAttackTimeMs = 32.0,
            avgDecayTauMs = 85.0
        )
    }

    @Test
    fun decouplesFiveTapPositionsAndIdentifiesFingerstyleProfile() {
        val spectra = mapOf(
            TapPosition.BRIDGE to synthSpectrum(
                TapPosition.BRIDGE,
                peaks = listOf(
                    Triple(99.2, -32.0, 22.0),   // A0（相对 T1 约 -4 dB）
                    Triple(179.4, -28.0, 26.0),  // T1（偏离 F3 174.6 / F#3 185.0 中央缝隙，无狼音）
                    Triple(204.8, -37.0, 24.0),  // 耦合 B1
                    Triple(312.5, -42.0, 20.0),
                    Triple(359.0, -39.0, 20.0)
                ),
                highFreqLevelDbfs = -44.0
            ),
            TapPosition.DIPOLE_L to synthSpectrum(
                TapPosition.DIPOLE_L,
                peaks = listOf(
                    Triple(99.2, -39.0, 22.0),
                    Triple(179.4, -36.0, 26.0),
                    Triple(312.5, -34.0, 22.0)   // 活跃横偶极 T2,1（相对 T1 仅 -6 dB）
                )
            ),
            TapPosition.DIPOLE_V to synthSpectrum(
                TapPosition.DIPOLE_V,
                peaks = listOf(
                    Triple(99.2, -38.0, 22.0),
                    Triple(179.4, -33.0, 26.0),
                    Triple(359.0, -32.0, 22.0)   // 纵偶极 T1,2 = 2.00 * T1
                )
            ),
            TapPosition.BACK to synthSpectrum(
                TapPosition.BACK,
                peaks = listOf(
                    Triple(99.2, -40.0, 22.0),
                    Triple(204.8, -29.0, 25.0)   // 背板 B1（高出 T1 +25.4 Hz，+2.3 半音）
                )
            ),
            TapPosition.DECOUPLE to synthSpectrum(
                TapPosition.DECOUPLE,
                peaks = listOf(
                    Triple(99.2, -52.0, 15.0),   // 封音孔后 A0 大幅衰减 20 dB
                    Triple(168.5, -29.0, 24.0)   // 未耦合面板裸基频
                )
            )
        )

        val report = AcousticAnalyzer.analyze(spectra, GuitarBodyShape.OM)

        assertEquals(99.2, report.peaks[ModalId.A0]!!.frequencyHz, 0.4)
        assertEquals(179.4, report.peaks[ModalId.T1]!!.frequencyHz, 0.4)
        assertEquals(204.8, report.peaks[ModalId.B1]!!.frequencyHz, 0.4)
        assertEquals(312.5, report.peaks[ModalId.T21]!!.frequencyHz, 0.5)
        assertEquals(359.0, report.peaks[ModalId.T12]!!.frequencyHz, 0.5)
        assertEquals(168.5, report.peaks[ModalId.T1_UNCOUPLED]!!.frequencyHz, 0.5)

        assertFalse(report.decoupleSkipped)
        assertNotNull(report.a0DecoupleDropDb)
        assertTrue("封音孔 A0 应明显衰减: ${report.a0DecoupleDropDb}", report.a0DecoupleDropDb!! > 10.0)
        assertEquals(25.4, report.backTopDiffHz!!, 0.8)
        assertEquals(2.0, report.dipoleRatio!!, 0.04)

        // 无高危缺陷，高综合分
        assertTrue(report.defects.none { it.severity == DefectSeverity.SEVERE })
        assertTrue("优质标杆琴综合分应 >= 85，实际 ${report.overallScore}", report.overallScore >= 85)
        assertEquals("应识别为现代指弹首选", PlayingStyle.FINGERSTYLE, report.styleMatches.first().style)
    }

    @Test
    fun detectsSevereWolfToneAndAntiPhaseCancellation() {
        val spectra = mapOf(
            TapPosition.BRIDGE to synthSpectrum(
                TapPosition.BRIDGE,
                peaks = listOf(
                    Triple(105.0, -35.0, 20.0),
                    Triple(196.1, -26.0, 44.0) // 紧贴 G3 (196.0 Hz) 且 Q >= 35 -> 重度狼音
                )
            ),
            TapPosition.DIPOLE_L to synthSpectrum(TapPosition.DIPOLE_L, listOf(Triple(320.0, -40.0, 18.0))),
            TapPosition.DIPOLE_V to synthSpectrum(TapPosition.DIPOLE_V, listOf(Triple(392.0, -35.0, 20.0))),
            TapPosition.BACK to synthSpectrum(
                TapPosition.BACK,
                peaks = listOf(Triple(202.0, -28.0, 26.0)) // 与 T1 仅差 5.9 Hz < 12 Hz -> 反相相消
            )
        )

        val report = AcousticAnalyzer.analyze(spectra)
        val wolf = report.defects.first { it.code == "wolf_tone" }
        val coupling = report.defects.first { it.code == "anti_phase" }

        assertEquals(DefectSeverity.SEVERE, wolf.severity)
        assertTrue("应标出具体音名 G3: ${wolf.summary}", wolf.summary.contains("G3"))
        assertTrue("应标出具体把位 3弦空弦: ${wolf.summary}", wolf.summary.contains("3弦空弦"))

        assertEquals(DefectSeverity.SEVERE, coupling.severity)
        assertTrue("跳过第 5 步时应标记 decoupleSkipped", report.decoupleSkipped)
    }

    @Test
    fun detectsNeckDeadSpotAbsorptionNotch() {
        val bridge = synthSpectrum(
            TapPosition.BRIDGE,
            peaks = listOf(
                Triple(64.0, -45.0, 8.0),
                Triple(99.0, -34.0, 20.0),
                Triple(182.0, -28.0, 25.0)
            ),
            notch = Triple(80.5, 16.0, 2.5) // 80.5 Hz 深度吸收谷
        )
        val report = AcousticAnalyzer.analyze(mapOf(TapPosition.BRIDGE to bridge))
        val neck = report.defects.first { it.code == "neck_dead_spot" }
        assertEquals(DefectSeverity.MODERATE, neck.severity)
        assertTrue(neck.summary.contains("80"))
    }

    @Test
    fun strummingProfile_matchesStrummingTopStyle() {
        val spectra = mapOf(
            TapPosition.BRIDGE to synthSpectrum(
                TapPosition.BRIDGE,
                peaks = listOf(
                    Triple(106.5, -31.5, 22.0),  // A0 102–110 Hz
                    Triple(198.5, -27.0, 24.0),  // T1 192–205 Hz（略错开 G3）
                    Triple(397.0, -34.0, 22.0)
                ),
                highFreqLevelDbfs = -47.0
            ),
            TapPosition.DIPOLE_L to synthSpectrum(
                TapPosition.DIPOLE_L,
                peaks = listOf(
                    Triple(198.5, -28.0, 24.0),
                    Triple(330.0, -41.0, 18.0)   // 横偶极较克制（-14 dB）
                )
            ),
            TapPosition.DIPOLE_V to synthSpectrum(
                TapPosition.DIPOLE_V,
                peaks = listOf(
                    Triple(198.5, -32.0, 24.0),
                    Triple(397.0, -29.0, 22.0)   // 精确 1:2 八度协同
                )
            ),
            TapPosition.BACK to synthSpectrum(
                TapPosition.BACK,
                peaks = listOf(Triple(233.0, -29.0, 24.0)) // +34.5 Hz 差
            )
        )
        val report = AcousticAnalyzer.analyze(spectra, GuitarBodyShape.DREADNOUGHT)
        assertEquals(PlayingStyle.STRUMMING, report.styleMatches.first().style)
    }

    @Test
    fun manualPeakOverride_snapsAndMarksManualAndRecomputesTopology() {
        val spectra = mapOf(
            TapPosition.BRIDGE to synthSpectrum(
                TapPosition.BRIDGE,
                peaks = listOf(
                    Triple(98.0, -33.0, 20.0),
                    Triple(178.0, -29.0, 24.0),
                    Triple(193.0, -32.0, 24.0)
                )
            ),
            TapPosition.BACK to synthSpectrum(
                TapPosition.BACK,
                peaks = listOf(Triple(215.0, -29.0, 24.0))
            )
        )
        val autoReport = AcousticAnalyzer.analyze(spectra)
        assertEquals(178.0, autoReport.peaks[ModalId.T1]!!.frequencyHz, 0.5)
        assertFalse(autoReport.peaks[ModalId.T1]!!.isManualOverride)

        // 用户手动点选 192.5 Hz 附近微调 T1 -> 应自动吸附至 193.0 Hz 局部峰并重算面背频差
        val manualReport = AcousticAnalyzer.analyze(
            spectra = spectra,
            manualOverrides = mapOf(ModalId.T1 to 192.4)
        )
        val newT1 = manualReport.peaks[ModalId.T1]!!
        assertTrue(newT1.isManualOverride)
        assertTrue("应吸附至 193.0 Hz 局部峰，实际 ${newT1.frequencyHz}", abs(newT1.frequencyHz - 193.0) < 0.6)
        assertEquals(22.0, manualReport.backTopDiffHz!!, 0.8)
    }

    @Test
    fun warmJazzAndPercussiveProfiles_matchExpectedStyles() {
        val jazzSpectra = mapOf(
            TapPosition.BRIDGE to synthSpectrum(
                TapPosition.BRIDGE,
                peaks = listOf(
                    Triple(93.0, -31.0, 14.0),  // 深潜 A0 90–96 Hz
                    Triple(180.5, -28.0, 14.0), // 温润高阻尼 T1（ζ ≈ 0.036）
                    Triple(334.0, -36.0, 15.0)
                ),
                highFreqLevelDbfs = -72.0 // 2.5 kHz 以上大幅滚降
            ),
            TapPosition.DIPOLE_L to synthSpectrum(
                TapPosition.DIPOLE_L,
                listOf(Triple(180.5, -29.0, 14.0), Triple(305.0, -38.0, 15.0))
            ),
            TapPosition.DIPOLE_V to synthSpectrum(
                TapPosition.DIPOLE_V,
                listOf(Triple(180.5, -31.0, 14.0), Triple(334.0, -33.0, 15.0))
            ),
            TapPosition.BACK to synthSpectrum(
                TapPosition.BACK,
                listOf(Triple(201.0, -29.0, 16.0))
            )
        )
        val jazzReport = AcousticAnalyzer.analyze(jazzSpectra, GuitarBodyShape.PARLOR)
        assertEquals(PlayingStyle.WARM_JAZZ, jazzReport.styleMatches.first().style)

        val percSpectra = mapOf(
            TapPosition.BRIDGE to synthSpectrum(
                TapPosition.BRIDGE,
                peaks = listOf(
                    Triple(104.0, -32.0, 15.0),
                    Triple(213.0, -27.0, 15.0), // 高刚度快起振 T1 205–220 Hz
                    Triple(420.0, -34.0, 16.0)
                ),
                highFreqLevelDbfs = -40.0
            ).copy(avgAttackTimeMs = 18.0),
            TapPosition.DIPOLE_L to synthSpectrum(
                TapPosition.DIPOLE_L,
                listOf(Triple(213.0, -28.0, 15.0), Triple(350.0, -43.0, 15.0))
            ),
            TapPosition.DIPOLE_V to synthSpectrum(
                TapPosition.DIPOLE_V,
                listOf(Triple(213.0, -31.0, 15.0), Triple(420.0, -31.0, 16.0))
            ),
            TapPosition.BACK to synthSpectrum(
                TapPosition.BACK,
                listOf(Triple(241.0, -29.0, 16.0))
            )
        )
        val percReport = AcousticAnalyzer.analyze(percSpectra, GuitarBodyShape.CONCERT)
        assertEquals(PlayingStyle.PERCUSSIVE, percReport.styleMatches.first().style)
    }
}
