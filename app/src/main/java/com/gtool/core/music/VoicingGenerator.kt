package com.gtool.core.music

import java.util.concurrent.ConcurrentHashMap

/**
 * 指法生成器（纯 Kotlin，可直接在 JVM 单元测试中运行）。
 *
 * 思路：
 * 1. 只枚举「空弦把位（0–4 品，且至少一根空弦）」与「可移动把位（最低按品 = b，跨度 4 品）」，
 *    每个窗口内逐弦尝试 {-1 闷音} ∪ {该弦发声音高属于和弦组成音的品位}。
 * 2. 硬约束：跨度 ≤ 4、发声弦数 ≥ 4（强力和弦 ≥ 3）、根音必须出现、手指数 ≤ 4（横按算 1 指）。
 * 3. 打分：低音为根音、包含特征音、空弦多、发声弦多、把位低、避免内弦闷音与重复音过多。
 * 4. 结果按分数排序、按品位组合去重，并按 (根音, 和弦类型, 调弦, 变调夹, 最大品) 缓存。
 *
 * 变调夹语义：fret 始终是「相对变调夹」的品位；发声音高 = 弦空弦 MIDI + capo + fret。
 * 因此同一指法在 capo > 0 时音高整体升高，和弦仍是调用方指定的那个和弦。
 */
object VoicingGenerator {

    const val DEFAULT_MAX_FRET = 12

    private const val MAX_CACHE_ENTRIES = 64
    private const val MAX_CACHE_CHORDS = 512

    private val cache = ConcurrentHashMap<String, List<Voicing>>()

    /**
     * 生成推荐指法。
     *
     * @param capo 变调夹品位（0–7 常用），影响发声音高，不改变返回的 frets 基准（相对变调夹）。
     * @param maxFret 最高品位（相对变调夹），默认 12。
     * @param limit 返回条数，默认 5。
     */
    fun generate(
        chord: Chord,
        tuning: Tuning,
        capo: Int = 0,
        maxFret: Int = DEFAULT_MAX_FRET,
        limit: Int = 5
    ): List<Voicing> {
        if (limit <= 0 || tuning.strings.isEmpty()) return emptyList()
        val safeCapo = capo.coerceIn(0, 12)
        val safeMaxFret = maxFret.coerceIn(1, 15)
        val key = buildString {
            append(chord.root.semitone).append('-')
            append(chord.quality.id).append('-')
            append(tuning.id).append(':')
            tuning.strings.forEachIndexed { idx, note ->
                if (idx > 0) append(',')
                append(note.midi)
            }
            append('-')
            append(safeCapo).append('-')
            append(safeMaxFret)
        }
        val all = cache[key] ?: compute(chord, tuning, safeCapo, safeMaxFret).also { computed ->
            if (cache.size >= MAX_CACHE_CHORDS) cache.clear()
            cache[key] = computed
        }
        return if (all.size <= limit) all else all.subList(0, limit).toList()
    }

    /** 清空缓存（测试或调弦数据变更时使用）。 */
    fun clearCache() {
        cache.clear()
    }

    /**
     * 给定弦与相对品位，返回实际发声音高；fret < 0 表示闷音，返回 null。
     */
    fun soundingNote(tuning: Tuning, stringIndex: Int, capo: Int = 0, fret: Int): Note? {
        if (fret < 0 || stringIndex !in tuning.strings.indices) return null
        val midi = tuning.strings[stringIndex].midi + capo.coerceIn(0, 12) + fret
        if (midi !in 0..127) return null
        return Note.fromMidi(midi)
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private fun compute(
        chord: Chord,
        tuning: Tuning,
        capo: Int,
        maxFret: Int
    ): List<Voicing> {
        val toneCount = chord.quality.intervals.size
        val strictMinSounded = if (toneCount <= 2) 3 else 4
        val relaxedMinSounded = if (toneCount <= 3) 3 else strictMinSounded

        val found = LinkedHashMap<String, Voicing>()
        enumerate(chord, tuning, capo, maxFret, strictMinSounded, found)

        // 三音和弦在极高把位偶尔不足 3 个候选，此时才放宽到 3 根弦，保证页面总能展示。
        if (found.size < 3 && relaxedMinSounded < strictMinSounded) {
            enumerate(chord, tuning, capo, maxFret, relaxedMinSounded, found)
        }

        return found.values
            .sortedWith(
                compareByDescending<Voicing> { it.score }
                    .thenBy { it.baseFret }
                    .thenBy { voicing -> voicing.frets.joinToString(",") }
            )
            .take(MAX_CACHE_ENTRIES)
    }

    private fun enumerate(
        chord: Chord,
        tuning: Tuning,
        capo: Int,
        maxFret: Int,
        minSounded: Int,
        into: MutableMap<String, Voicing>
    ) {
        val openMidi = IntArray(tuning.strings.size) { tuning.strings[it].midi }
        if (openMidi.isEmpty()) return
        val toneMask = BooleanArray(12)
        chord.tones.forEach { toneMask[it.pitchClass.semitone] = true }
        val rootPc = chord.root.semitone

        // 空弦把位：至少一根空弦，按品不超过 4 品。
        enumerateWindow(
            openMidi = openMidi,
            toneMask = toneMask,
            rootPc = rootPc,
            capo = capo,
            minSounded = minSounded,
            quality = chord.quality,
            lowFret = 0,
            highFret = minOf(4, maxFret),
            requireOpen = true,
            requireLowestFret = -1,
            into = into
        )

        // 可移动把位：最低按品固定为 base，避免同一指法在多个窗口重复枚举。
        for (base in 1..maxFret) {
            enumerateWindow(
                openMidi = openMidi,
                toneMask = toneMask,
                rootPc = rootPc,
                capo = capo,
                minSounded = minSounded,
                quality = chord.quality,
                lowFret = base,
                highFret = minOf(base + 4, maxFret),
                requireOpen = false,
                requireLowestFret = base,
                into = into
            )
        }
    }

    private fun enumerateWindow(
        openMidi: IntArray,
        toneMask: BooleanArray,
        rootPc: Int,
        capo: Int,
        minSounded: Int,
        quality: ChordQuality,
        lowFret: Int,
        highFret: Int,
        requireOpen: Boolean,
        requireLowestFret: Int,
        into: MutableMap<String, Voicing>
    ) {
        val n = openMidi.size
        val options = Array(n) { stringIndex ->
            val list = ArrayList<Int>(6)
            list.add(-1)
            for (fret in lowFret..highFret) {
                val pc = (openMidi[stringIndex] + capo + fret) % 12
                if (toneMask[pc]) list.add(fret)
            }
            list.toIntArray()
        }

        // 后缀剪枝表：某根弦之后还能否提供根音 / 空弦 / 最低按品。
        val suffixHasRoot = BooleanArray(n + 1)
        val suffixHasOpen = BooleanArray(n + 1)
        val suffixHasLowest = BooleanArray(n + 1)
        for (i in n - 1 downTo 0) {
            var hasRoot = suffixHasRoot[i + 1]
            var hasOpen = suffixHasOpen[i + 1]
            var hasLowest = suffixHasLowest[i + 1]
            for (fret in options[i]) {
                if (fret > 0) {
                    if ((openMidi[i] + capo + fret) % 12 == rootPc) hasRoot = true
                } else if (fret == 0) {
                    hasOpen = true
                    if ((openMidi[i] + capo) % 12 == rootPc) hasRoot = true
                }
                if (requireLowestFret >= 0 && fret == requireLowestFret) hasLowest = true
            }
            suffixHasRoot[i] = hasRoot
            suffixHasOpen[i] = hasOpen
            suffixHasLowest[i] = hasLowest
        }

        val frets = IntArray(n) { -1 }

        fun dfs(
            index: Int,
            minPressed: Int,
            maxPressed: Int,
            hasRoot: Boolean,
            hasOpen: Boolean,
            hasLowest: Boolean,
            sounded: Int
        ) {
            if (sounded + (n - index) < minSounded) return
            if (!hasRoot && !suffixHasRoot[index]) return
            if (requireOpen && !hasOpen && !suffixHasOpen[index]) return
            if (requireLowestFret >= 0 && !hasLowest && !suffixHasLowest[index]) return
            if (maxPressed >= 0 && maxPressed - minPressed > 4) return

            if (index == n) {
                if (requireOpen && !hasOpen) return
                if (requireLowestFret >= 0 && !hasLowest) return
                val voicing = evaluate(
                    frets = frets,
                    openMidi = openMidi,
                    rootPc = rootPc,
                    capo = capo,
                    minSounded = minSounded,
                    quality = quality
                ) ?: return
                val key = voicing.frets.joinToString(",")
                val previous = into[key]
                if (previous == null || voicing.score > previous.score) {
                    into[key] = voicing
                }
                return
            }

            val stringIndex = index
            for (fret in options[stringIndex]) {
                frets[stringIndex] = fret
                if (fret > 0) {
                    val nextMin = minOf(minPressed, fret)
                    val nextMax = maxOf(maxPressed, fret)
                    if (nextMax - nextMin > 4) continue
                    val pc = (openMidi[stringIndex] + capo + fret) % 12
                    dfs(
                        index + 1, nextMin, nextMax,
                        hasRoot || pc == rootPc,
                        hasOpen,
                        hasLowest || (requireLowestFret >= 0 && fret == requireLowestFret),
                        sounded + 1
                    )
                } else if (fret == 0) {
                    val pc = (openMidi[stringIndex] + capo) % 12
                    dfs(
                        index + 1, minPressed, maxPressed,
                        hasRoot || pc == rootPc,
                        true,
                        hasLowest,
                        sounded + 1
                    )
                } else {
                    dfs(index + 1, minPressed, maxPressed, hasRoot, hasOpen, hasLowest, sounded)
                }
            }
            frets[stringIndex] = -1
        }

        dfs(0, Int.MAX_VALUE, -1, false, false, false, 0)
    }

    private fun evaluate(
        frets: IntArray,
        openMidi: IntArray,
        rootPc: Int,
        capo: Int,
        minSounded: Int,
        quality: ChordQuality
    ): Voicing? {
        val n = frets.size
        var sounded = 0
        var rootFound = false
        var bassIndex = -1
        var bassPc = -1
        var openCount = 0
        var mutedCount = 0
        var minPressed = 0
        var maxPressed = 0
        var span = 0
        val pcs = IntArray(n) { -1 }
        val present = BooleanArray(12)

        for (i in 0 until n) {
            val fret = frets[i]
            if (fret < 0) {
                mutedCount++
                continue
            }
            sounded++
            if (fret == 0) openCount++
            val pc = (openMidi[i] + capo + fret) % 12
            pcs[i] = pc
            present[pc] = true
            if (pc == rootPc) rootFound = true
            if (bassIndex < 0) {
                bassIndex = i
                bassPc = pc
            }
            if (fret > 0) {
                if (minPressed == 0 || fret < minPressed) minPressed = fret
                if (fret > maxPressed) maxPressed = fret
            }
        }

        if (sounded < minSounded || !rootFound || bassIndex < 0) return null
        if (minPressed > 0) span = maxPressed - minPressed
        if (span > 4) return null
        val fingers = assignFingers(frets) ?: return null

        var middleMuted = 0
        for (i in 0 until n) {
            if (frets[i] >= 0) continue
            var hasLower = false
            var hasHigher = false
            for (j in 0 until i) if (frets[j] >= 0) { hasLower = true; break }
            for (j in i + 1 until n) if (frets[j] >= 0) { hasHigher = true; break }
            if (hasLower && hasHigher) middleMuted++
        }

        var distinctPcs = 0
        for (pc in 0 until 12) if (present[pc]) distinctPcs++
        val duplicateCount = sounded - distinctPcs

        var allTones = true
        for (interval in quality.intervals) {
            if (!present[(rootPc + interval) % 12]) allTones = false
        }
        val hasCharacteristic = quality.intervals.size >= 2 &&
            present[(rootPc + quality.intervals[1]) % 12]
        val bassIsRoot = bassPc == rootPc
        val bassIsFifth = bassPc == (rootPc + 7) % 12
        val baseFret = if (openCount > 0) 0 else minPressed

        var score = 0
        score += when {
            bassIsRoot -> 45
            bassIsFifth -> 8
            else -> -10
        }
        score += if (hasCharacteristic) 18 else -8
        score += if (allTones) 15 else 0
        score += minOf(openCount, 4) * 7
        score += sounded * 3
        score -= span * 4
        score -= maxPressed * 2
        score -= middleMuted * 14
        score -= duplicateCount * 3
        score -= mutedCount
        if (openCount == 0 && minPressed >= 5) score -= 10
        if (sounded == n && mutedCount == 0) score += 5

        // 惩罚非横按时用 4 指同时把最低按品劈开在相隔 3 根以上琴弦上的扭曲手型（如 1-0-3-2-1-x）
        val usedBarre = (0 until n).count { fingers[it] == 1 && frets[it] > 0 } >= 2
        if (!usedBarre && minPressed > 0) {
            var pressed = 0
            var firstLow = -1
            var lastLow = -1
            var lowCount = 0
            for (i in 0 until n) {
                if (frets[i] > 0) pressed++
                if (frets[i] == minPressed) {
                    if (firstLow < 0) firstLow = i
                    lastLow = i
                    lowCount++
                }
            }
            if (pressed == 4 && lowCount >= 2 && lastLow - firstLow >= 3) {
                score -= 12
            }
        }

        return Voicing(
            frets = frets.toList(),
            fingers = fingers.toList(),
            baseFret = baseFret,
            score = score
        )
    }

    /**
     * 计算 1–4 的手指编号（横按统一用 1 指，0 表示不按）。
     *
     * 只有在「最低按品出现在 ≥2 根弦、跨度不超过 3、中间没有空弦/闷音、其余按弦数 ≤3」时才允许横按；
     * 横按之外每根按弦分配独立手指（2、3、4），不把同一高品位的多根弦错误归到同一根手指。
     */
    private fun assignFingers(frets: IntArray): IntArray? {
        val n = frets.size
        val fingers = IntArray(n)
        var pressedCount = 0
        var minFret = Int.MAX_VALUE
        var maxFret = Int.MIN_VALUE
        val lowestStrings = ArrayList<Int>(n)

        for (i in 0 until n) {
            val fret = frets[i]
            if (fret <= 0) continue
            pressedCount++
            if (fret < minFret) minFret = fret
            if (fret > maxFret) maxFret = fret
        }
        if (pressedCount == 0) return fingers

        for (i in 0 until n) {
            if (frets[i] == minFret) lowestStrings.add(i)
        }

        var barreGapOk = true
        if (lowestStrings.size >= 2) {
            val first = lowestStrings.first()
            val last = lowestStrings.last()
            for (i in first + 1 until last) {
                // 横按手指会压到中间弦：中间弦必须在横按品或更高品位（被其他手指截停），不能是空弦或闷音。
                if (frets[i] < minFret) {
                    barreGapOk = false
                    break
                }
            }
        }

        val higherStrings = (0 until n)
            .filter { frets[it] > minFret }
            .sortedWith(compareBy({ frets[it] }, { it }))

        val canBarre = lowestStrings.size >= 2 &&
            maxFret - minFret <= 3 &&
            higherStrings.size <= 3 &&
            barreGapOk

        val adjacentMiniBarre = canBarre &&
            lowestStrings.size == 2 &&
            lowestStrings.last() - lowestStrings.first() == 1 &&
            higherStrings.isNotEmpty()

        val useBarre = when {
            canBarre && (pressedCount > 4 || (lowestStrings.size >= 3 && (higherStrings.isNotEmpty() || pressedCount >= 4)) || adjacentMiniBarre) -> true
            pressedCount > 4 -> return null
            else -> false
        }

        if (useBarre) {
            for (i in lowestStrings) fingers[i] = 1
            higherStrings.forEachIndexed { index, stringIndex ->
                fingers[stringIndex] = index + 2
            }
            return fingers
        }

        if (pressedCount > 4) return null
        val order = (0 until n)
            .filter { frets[it] > 0 }
            .sortedWith(compareBy({ frets[it] }, { it }))
        order.forEachIndexed { index, stringIndex ->
            fingers[stringIndex] = index + 1
        }
        return fingers
    }
}
