package com.gtool.core.acoustic

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticReportFormatterTest {

    private fun sampleRecord(skipDecouple: Boolean = true, qualityOverride: Boolean = false): AcousticRecord {
        val step = 48_000.0 / 65_536.0
        val binStart = (30.0 / step).toInt()
        val mags = List(400) { i ->
            val f = (binStart + i) * step
            when {
                kotlin.math.abs(f - 100.0) < 2.0 -> -30f
                kotlin.math.abs(f - 182.0) < 2.0 -> -26f
                else -> -72f
            }
        }
        val bridge = PositionSpectrum(
            position = TapPosition.BRIDGE,
            noiseFloorDbfs = -78.0,
            coherenceScore = if (qualityOverride) 0.84 else 0.95,
            qualityOverride = qualityOverride,
            binStartIndex = binStart,
            freqStepHz = step,
            magnitudesDbfs = mags
        )
        val back = bridge.copy(position = TapPosition.BACK)
        val spectra = buildMap {
            put(TapPosition.BRIDGE, bridge)
            put(TapPosition.BACK, back)
            if (!skipDecouple) put(TapPosition.DECOUPLE, bridge.copy(position = TapPosition.DECOUPLE))
        }
        val report = AcousticAnalyzer.analyze(spectra, GuitarBodyShape.OM)
        return AcousticRecord(
            id = "test-1",
            guitarName = "Martin OM-28",
            bodyShape = GuitarBodyShape.OM,
            notes = "新换磷青铜012弦",
            inputDeviceName = "USB 外接麦 · UMIK-1",
            createdAtEpochMs = 1_760_000_000_000L,
            spectra = spectra,
            report = report
        )
    }

    @Test
    fun toMarkdown_rendersAllSixSpecSectionsAndFlags() {
        val md = AcousticReportFormatter.toMarkdown(sampleRecord(skipDecouple = true, qualityOverride = true))
        assertTrue(md.contains("# 原声吉他声振模态与频响特性诊断报告"))
        assertTrue(md.contains("## 一、 乐器档案与测量工况"))
        assertTrue(md.contains("## 二、 核心共振模态实测数据表"))
        assertTrue(md.contains("## 三、 声学缺陷与物理稳定性诊断"))
        assertTrue(md.contains("## 四、 音质综合客观评级"))
        assertTrue(md.contains("## 五、 演奏风格与曲风适配建议"))
        assertTrue(md.contains("## 六、 针对性微调与保养建议"))
        assertTrue(md.contains("Martin OM-28"))
        assertTrue(md.contains("USB 外接麦 · UMIK-1"))
        assertTrue(md.contains("已跳过第 5 步封音孔解耦"))
        assertTrue(md.contains("含嘈杂环境人工放宽确认点位"))
    }

    @Test
    fun toRawCsv_outputsHeadersAndNumericRows() {
        val csv = AcousticReportFormatter.toRawCsv(sampleRecord())
        val lines = csv.lines().filter { it.isNotBlank() }
        assertTrue(lines.first().startsWith("# TickTuner Acoustic Raw FFT Data"))
        val header = lines.first { it.startsWith("Frequency_Hz") }
        assertTrue(header.contains("Spectrum-Bridge_dBFS"))
        assertTrue(header.contains("Spectrum-Back_dBFS"))
        val dataLines = lines.filterNot { it.startsWith("#") || it.startsWith("Frequency_Hz") }
        assertEquals(400, dataLines.size)
        val cols = dataLines.first().split(",")
        assertEquals(5, cols.size) // Freq + 2 positions * (dBFS, RelDb)
    }

    @Test
    fun safeFileStem_stripsIllegalCharacters() {
        val stem = AcousticReportFormatter.safeFileStem("Taylor 314ce / 2026:test*", 1_760_000_000_000L)
        assertTrue(stem.startsWith("Taylor_314ce_2026_test_"))
        assertTrue(!stem.contains("/"))
        assertTrue(!stem.contains("*"))
        assertTrue(!stem.contains(":"))
    }

    @Test
    fun acousticRecord_jsonRoundTripPreservesSpectraAndSummary() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val original = sampleRecord(skipDecouple = false, qualityOverride = false)
        val encoded = json.encodeToString(AcousticRecord.serializer(), original)
        val decoded = json.decodeFromString(AcousticRecord.serializer(), encoded)
        assertEquals(original.id, decoded.id)
        assertEquals(original.guitarName, decoded.guitarName)
        assertEquals(original.spectra.size, decoded.spectra.size)
        assertEquals(
            original.spectra[TapPosition.BRIDGE]!!.magnitudesDbfs.size,
            decoded.spectra[TapPosition.BRIDGE]!!.magnitudesDbfs.size
        )
        assertEquals(original.toSummary(), decoded.toSummary())
    }
}
