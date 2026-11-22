package com.gtool.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * VoicingGenerator 的纯 JVM 测试。只依赖 core/music 下的共享模型。
 */
class VoicingGeneratorTest {

    private val standard = TuningLibrary.STANDARD

    private val commonChords = listOf(
        Chord(PitchClass.C, ChordQuality.MAJOR),
        Chord(PitchClass.G, ChordQuality.MAJOR),
        Chord(PitchClass.D, ChordQuality.MAJOR),
        Chord(PitchClass.A, ChordQuality.MINOR),
        Chord(PitchClass.F, ChordQuality.MAJOR),
        Chord(PitchClass.B, ChordQuality.DOM7),
        Chord(PitchClass.C, ChordQuality.MAJ7)
    )

    @Before
    fun setUp() {
        VoicingGenerator.clearCache()
    }

    @Test
    fun cMajorContainsPlayableShape() {
        val chord = Chord(PitchClass.C, ChordQuality.MAJOR)
        val voicings = VoicingGenerator.generate(chord, standard)
        assertTrue("应生成指法", voicings.isNotEmpty())
        val openC = listOf(-1, 3, 2, 0, 1, 0)
        val barreC = listOf(8, 10, 10, 9, 8, 8)
        assertTrue(
            "C 大三应包含 x32010 或 8-10-10-9-8-8，实际=${voicings.map { it.frets }}",
            voicings.any { it.frets == openC } || voicings.any { it.frets == barreC }
        )
    }

    @Test
    fun generatedVoicingsSatisfyHardConstraints() {
        for (chord in commonChords) {
            val voicings = VoicingGenerator.generate(chord, standard, capo = 0, maxFret = 12, limit = 5)
            assertEquals("${chord.displayName} 应给出 5 个指法", 5, voicings.size)
            for (voicing in voicings) {
                val label = "${chord.displayName} ${voicing.frets}"
                assertEquals("$label 弦数", standard.stringCount, voicing.frets.size)
                assertEquals("$label 手指数组长度", standard.stringCount, voicing.fingers.size)
                assertTrue("$label 跨度应 ≤4，实际 ${voicing.span}", voicing.span <= 4)

                val sounded = voicing.soundedStrings
                assertTrue("$label 发声弦应 ≥4，实际 ${sounded.size}", sounded.size >= 4)

                val rootPresent = sounded.any { stringIndex ->
                    val midi = standard.strings[stringIndex].midi + voicing.frets[stringIndex]
                    PitchClass.of(midi) == chord.root
                }
                assertTrue("$label 必须包含根音", rootPresent)

                assertTrue("$label 手指编号应在 0..4", voicing.fingers.all { it in 0..4 })
                assertTrue(
                    "$label 使用的手指数应 ≤4",
                    voicing.fingers.filter { it in 1..4 }.distinct().size <= 4
                )
                assertTrue("$label 品位应在 -1..12", voicing.frets.all { it in -1..12 })
                voicing.frets.forEachIndexed { index, fret ->
                    if (fret <= 0) {
                        assertEquals("$label 空弦/闷音不应有手指编号", 0, voicing.fingers[index])
                    }
                }

                val expectedBase = if (voicing.frets.any { it == 0 }) 0 else voicing.minPressed
                assertEquals("$label baseFret", expectedBase, voicing.baseFret)
            }
        }
    }

    @Test
    fun recommendedVoicingHasRootBass() {
        for (chord in commonChords) {
            val first = VoicingGenerator.generate(chord, standard, limit = 1).first()
            val bassIndex = first.frets.indexOfFirst { it >= 0 }
            assertTrue("${chord.displayName} 应有发声弦", bassIndex >= 0)
            val bass = PitchClass.of(standard.strings[bassIndex].midi + first.frets[bassIndex])
            assertEquals("${chord.displayName} 首选指法低音应为根音，实际 $bass", chord.root, bass)
        }
    }

    @Test
    fun commonChordsHaveAtLeastThreeVoicings() {
        for (chord in commonChords) {
            val voicings = VoicingGenerator.generate(chord, standard, limit = 5)
            assertTrue(
                "${chord.displayName} 应至少 3 个指法，实际 ${voicings.size}",
                voicings.size >= 3
            )
        }
    }

    @Test
    fun everyRootAndQualityHasVoicings() {
        var count = 0
        for (root in PitchClass.entries) {
            for (quality in ChordQuality.entries) {
                val chord = Chord(root, quality)
                val voicings = VoicingGenerator.generate(chord, standard, limit = 5)
                assertTrue(
                    "${root.sharpName}${quality.id} 应至少 3 个指法，实际 ${voicings.size}",
                    voicings.size >= 3
                )
                val rootPresent = voicings.all { voicing ->
                    voicing.soundedStrings.any { stringIndex ->
                        PitchClass.of(standard.strings[stringIndex].midi + voicing.frets[stringIndex]) == root
                    }
                }
                assertTrue("${root.sharpName}${quality.id} 所有指法都应含根音", rootPresent)
                count++
            }
        }
        assertEquals(12 * 18, count)
    }

    @Test
    fun capoKeepsSelectedChordAndShiftsSoundingPitch() {
        val chord = Chord(PitchClass.C, ChordQuality.MAJOR)
        val capo = 2
        val voicings = VoicingGenerator.generate(chord, standard, capo = capo, limit = 5)
        assertTrue("变调夹下也应有指法", voicings.isNotEmpty())

        for (voicing in voicings) {
            val sounded = voicing.soundedStrings
            val pitchClasses = sounded.map { stringIndex ->
                PitchClass.of(standard.strings[stringIndex].midi + capo + voicing.frets[stringIndex])
            }
            val label = "capo=$capo ${voicing.frets}"
            assertTrue("$label 只能包含 C 大三组成音，实际 $pitchClasses", pitchClasses.all { chord.contains(it) })
            assertTrue("$label 必须含根音", chord.root in pitchClasses)
            assertTrue("$label 跨度应 ≤4", voicing.span <= 4)
            assertTrue("$label 发声弦应 ≥4", sounded.size >= 4)
        }

        // 品位相对变调夹显示：同一指法在 capo 2 时实际音高整体 +2 个半音。
        val openFretNote = VoicingGenerator.soundingNote(standard, 0, capo = 0, fret = 3)
        val capoFretNote = VoicingGenerator.soundingNote(standard, 0, capo = 2, fret = 3)
        assertNotNull(openFretNote)
        assertNotNull(capoFretNote)
        assertEquals(2, capoFretNote!!.midi - openFretNote!!.midi)
    }

    @Test
    fun customTuningAndHighFretWindowStillGenerate() {
        val dropD = TuningLibrary.DROP_D
        val chord = Chord(PitchClass.D, ChordQuality.MAJOR)
        val voicings = VoicingGenerator.generate(chord, dropD, capo = 0, maxFret = 12, limit = 5)
        assertTrue(voicings.isNotEmpty())
        for (voicing in voicings) {
            assertTrue(voicing.span <= 4)
            assertTrue(voicing.frets.all { it in -1..12 })
            val rootPresent = voicing.soundedStrings.any {
                PitchClass.of(dropD.strings[it].midi + voicing.frets[it]) == chord.root
            }
            assertTrue("Drop D 下 D 大三应含根音", rootPresent)
        }
    }

    @Test
    fun fullGenerationOf216ChordsUnderTwoSeconds() {
        VoicingGenerator.clearCache()
        val start = System.nanoTime()
        var generated = 0
        for (root in PitchClass.entries) {
            for (quality in ChordQuality.entries) {
                generated += VoicingGenerator.generate(Chord(root, quality), standard, limit = 5).size
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(216 * 5, generated)
        assertTrue("12 根音 × 18 类型全量生成耗时 ${elapsedMs}ms，应 <2000ms", elapsedMs < 2000)
    }

    @Test
    fun fMajorPrefersStandardBarreWithTextbookFingers() {
        val chord = Chord(PitchClass.F, ChordQuality.MAJOR)
        val voicings = VoicingGenerator.generate(chord, standard, capo = 0, limit = 5)
        assertTrue("F 大三应有指法", voicings.isNotEmpty())
        val first = voicings.first()
        assertEquals(listOf(1, 3, 3, 2, 1, 1), first.frets)
        assertEquals(listOf(1, 3, 4, 2, 1, 1), first.fingers)
    }

    @Test
    fun barreChordsNeverAssignSameNonBarreFingerToMultipleStrings() {
        for (root in PitchClass.entries) {
            for (quality in ChordQuality.entries) {
                val chord = Chord(root, quality)
                val voicings = VoicingGenerator.generate(chord, standard, capo = 0, limit = 5)
                for (voicing in voicings) {
                    val counts = HashMap<Int, Int>()
                    voicing.fingers.forEachIndexed { index, finger ->
                        if (voicing.frets[index] > 0 && finger > 0) {
                            counts[finger] = (counts[finger] ?: 0) + 1
                        }
                    }
                    for (finger in 2..4) {
                        val used = counts[finger] ?: 0
                        assertTrue(
                            "${chord.displayName} ${voicing.frets} 手指 ${voicing.fingers} 不应把 $finger 指重复分配给多根弦",
                            used <= 1
                        )
                    }
                }
            }
        }
    }

    @Test
    fun cMinorAtCapoTwoHasDistinctRingAndPinkyFingers() {
        val chord = Chord(PitchClass.C, ChordQuality.MINOR)
        val voicings = VoicingGenerator.generate(chord, standard, capo = 2, limit = 5)
        val amShape = voicings.firstOrNull { it.frets == listOf(-1, 1, 3, 3, 2, 1) }
        assertNotNull("Cm Capo 2 应包含 Am 型横按 [-1, 1, 3, 3, 2, 1]，实际=${voicings.map { it.frets }}", amShape)
        assertEquals(listOf(0, 1, 3, 4, 2, 1), amShape!!.fingers)
    }

    @Test
    fun cacheInvalidatesWhenSameIdCustomTuningChangesStrings() {
        val chord = Chord(PitchClass.D, ChordQuality.MAJOR)
        val v1 = Tuning(
            id = "custom_same_id",
            name = "Custom",
            strings = standard.strings,
            builtIn = false
        )
        val v2 = v1.copy(strings = TuningLibrary.DROP_D.strings)

        val firstResult = VoicingGenerator.generate(chord, v1, capo = 0, limit = 5)
        // 不手动清空缓存，直接用同 id 但不同弦音的调弦生成
        val secondResult = VoicingGenerator.generate(chord, v2, capo = 0, limit = 5)

        assertTrue(firstResult.isNotEmpty())
        assertTrue(secondResult.isNotEmpty())
        // 验证第二批指法的所有发声音高在 Drop D 下都属于 D 大三和弦
        for (voicing in secondResult) {
            val pcs = voicing.soundedStrings.map { i ->
                PitchClass.of(v2.strings[i].midi + voicing.frets[i])
            }
            assertTrue("Drop D 缓存应重算，实际音高=$pcs, frets=${voicing.frets}", pcs.all { chord.contains(it) })
        }
        // Drop D 下 D 大三应包含低音 6 弦空弦 D（frets[0] == 0）
        assertTrue(
            "Drop D 下 D 大三首选指法应利用 6 弦空弦 D，实际=${secondResult.map { it.frets }}",
            secondResult.any { it.frets[0] == 0 }
        )
    }

    @Test
    fun all216ChordsIncludeRequiredCharacteristicTones() {
        for (root in PitchClass.entries) {
            for (quality in ChordQuality.entries) {
                val chord = Chord(root, quality)
                val voicings = VoicingGenerator.generate(chord, standard, capo = 0, limit = 5)
                assertTrue("${chord.displayName} 应至少 3 个指法", voicings.size >= 3)
                for (voicing in voicings) {
                    val pcs = voicing.soundedStrings.map { i ->
                        PitchClass.of(standard.strings[i].midi + voicing.frets[i])
                    }.toSet()
                    for (required in chord.requiredPitchClasses) {
                        assertTrue(
                            "${chord.displayName} ${voicing.frets} 缺少必需特征音 $required，实际音级=$pcs",
                            required in pcs
                        )
                    }
                }
            }
        }
    }

    @Test
    fun extendedChordsNeverRecommendPlainMajorTriad() {
        val plainC = listOf(-1, 3, 2, 0, 1, 0)
        for (quality in listOf(
            ChordQuality.DOM7,
            ChordQuality.MAJ7,
            ChordQuality.SIX,
            ChordQuality.ADD9,
            ChordQuality.NINE,
            ChordQuality.MAJ9
        )) {
            val chord = Chord(PitchClass.C, quality)
            val voicings = VoicingGenerator.generate(chord, standard, capo = 0, limit = 5)
            assertTrue("${chord.displayName} 应生成指法", voicings.isNotEmpty())
            assertTrue(
                "${chord.displayName} 不应把普通 C 大三 $plainC 混入推荐指法，实际=${voicings.map { it.frets }}",
                voicings.none { it.frets == plainC }
            )
        }
    }

    @Test
    fun barreChordsNeverLeaveOpenStringsAboveBarre() {
        for (capo in listOf(0, 2, 5)) {
            for (root in PitchClass.entries) {
                for (quality in ChordQuality.entries) {
                    val chord = Chord(root, quality)
                    val voicings = VoicingGenerator.generate(chord, standard, capo = capo, limit = 5)
                    for (voicing in voicings) {
                        val barreIndices = voicing.fingers.indices.filter {
                            voicing.fingers[it] == 1 && voicing.frets[it] > 0
                        }
                        if (barreIndices.size >= 2) {
                            val first = barreIndices.first()
                            val last = barreIndices.last()
                            val barreFret = voicing.frets[first]
                            for (i in first + 1 until last) {
                                assertTrue(
                                    "${chord.displayName} capo=$capo ${voicing.frets} 横按中间弦不应低于横按品",
                                    voicing.frets[i] >= barreFret
                                )
                            }
                            for (i in last + 1 until voicing.frets.size) {
                                assertTrue(
                                    "${chord.displayName} capo=$capo ${voicing.frets} 横按后高音弦不应为空弦 0",
                                    voicing.frets[i] != 0
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun capoVoicingsRespectAbsoluteFretboardLimit() {
        for (capo in 0..7) {
            for (chord in commonChords + listOf(
                Chord(PitchClass.C, ChordQuality.NINE),
                Chord(PitchClass.E, ChordQuality.MIN9),
                Chord(PitchClass.G, ChordQuality.HALF_DIM7)
            )) {
                val voicings = VoicingGenerator.generate(chord, standard, capo = capo, limit = 5)
                assertTrue("${chord.displayName} capo=$capo 应生成指法", voicings.isNotEmpty())
                for (voicing in voicings) {
                    val maxRel = voicing.frets.maxOrNull() ?: 0
                    assertTrue(
                        "${chord.displayName} capo=$capo ${voicing.frets} 绝对品位 ${capo + maxRel} 超出指板上限 ${VoicingGenerator.MAX_ABSOLUTE_FRET}",
                        capo + maxRel <= VoicingGenerator.MAX_ABSOLUTE_FRET
                    )
                }
            }
        }
    }

    @Test
    fun lowestSoundingNoteUsesTrueMinimumMidiForNonAscendingTuning() {
        // 故意把 5 弦设得比 6 弦更低（模拟特殊自定义调弦）
        val reEntrant = Tuning(
            id = "custom_reentrant",
            name = "Re-entrant",
            strings = listOf(
                Note.parse("A2")!!,
                Note.parse("E2")!!,
                Note.parse("D3")!!,
                Note.parse("G3")!!,
                Note.parse("B3")!!,
                Note.parse("E4")!!
            ),
            builtIn = false
        )
        val voicing = Voicing(
            frets = listOf(0, 0, 2, 2, 2, 0),
            fingers = listOf(0, 0, 1, 2, 3, 0),
            baseFret = 0
        )
        val lowest = VoicingGenerator.lowestSoundingNote(reEntrant, voicing, capo = 0)
        assertEquals(Note.parse("E2"), lowest)
    }

    @Test
    fun guitarSynthRespectsA4ReferenceFrequency() {
        val synth = com.gtool.core.audio.GuitarSynth()
        val pcm440 = synth.render(midi = 69, a4 = 440.0)
        val pcm432 = synth.render(midi = 69, a4 = 432.0)
        assertTrue("不同 A4 基准生成的 PCM 应存在明显频率差异", !pcm440.contentEquals(pcm432))

        val detector = com.gtool.core.audio.PitchDetector(com.gtool.core.audio.GuitarSynth.SAMPLE_RATE.toDouble())
        val win440 = pcm440.copyOfRange(2400, 2400 + 4096)
        val win432 = pcm432.copyOfRange(2400, 2400 + 4096)
        val det440 = detector.detect(win440)
        val det432 = detector.detect(win432)
        assertNotNull("A4=440 应可识别基频", det440)
        assertNotNull("A4=432 应可识别基频", det432)
        assertTrue(
            "A4=440 识别频率应接近 440Hz，实际=${det440!!.frequency}",
            kotlin.math.abs(det440.frequency - 440.0) < 3.0
        )
        assertTrue(
            "A4=432 识别频率应接近 432Hz，实际=${det432!!.frequency}",
            kotlin.math.abs(det432.frequency - 432.0) < 3.0
        )
    }
}
