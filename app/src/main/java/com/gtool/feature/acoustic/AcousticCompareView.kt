package com.gtool.feature.acoustic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.acoustic.AcousticChartBitmapRenderer
import com.gtool.core.acoustic.AcousticRecord
import com.gtool.core.acoustic.AcousticRecordSummary
import com.gtool.core.acoustic.AcousticReportFormatter
import com.gtool.core.acoustic.SpectrumChartOptions
import com.gtool.core.acoustic.TapPosition
import com.gtool.core.music.centsFrom
import com.gtool.ui.AppPosture
import com.gtool.ui.LocalWindowPosture
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Bg
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.InfoBlue
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.Surface3
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm
import java.util.Locale

/**
 * 双记录 FFT 频谱横向叠加对比页：
 * - 对比相同敲击部位的共振峰频率与能量
 * - 直板机支持一键横屏全屏对比 + 自然重力横屏（折叠屏展开态直接宽屏显示）
 * - 支持一键切换叠放顺序（A 在上 ⇄ B 在上）
 * - 支持 A/B 各自从 8 种高对比曲线色卡中选择颜色
 * - 支持「0 dB 峰值对齐」与「原始电平 dBFS」切换、共振峰差值对照及对比叠图 PNG 下载
 */
@Composable
fun AcousticCompareView(
    state: AcousticUiState,
    onBack: () -> Unit,
    onSelectPosition: (TapPosition) -> Unit,
    onToggleStackOrder: () -> Unit,
    onSelectColor: (isA: Boolean, argb: Long) -> Unit,
    onUpdateOptions: ((SpectrumChartOptions) -> SpectrumChartOptions) -> Unit,
    onToggleForceLandscape: () -> Unit,
    onSwapRecord: (isSlotA: Boolean, newId: String) -> Unit,
    onSaveComparePng: (alsoShare: Boolean) -> Unit
) {
    val recA = state.compareRecordA ?: return
    val recB = state.compareRecordB ?: return
    val posture = LocalWindowPosture.current.posture
    val isWideLandscape = posture == AppPosture.LANDSCAPE_COMPACT || posture == AppPosture.UNFOLDED_EXPANDED

    val commonPositions = state.commonComparePositions
    val pos = state.comparePosition.takeIf { it in commonPositions }
        ?: commonPositions.firstOrNull()
    if (pos == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("两条记录没有共同的敲击点位，无法叠加对比", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF04211A))) {
                    Text("返回档案列表", fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }
    val specA = recA.spectra[pos] ?: return
    val specB = recB.spectra[pos] ?: return

    val colorA = Color(state.compareColorA)
    val colorB = Color(state.compareColorB)
    val peaksA = AcousticChartBitmapRenderer.peaksRelevantToPosition(recA.report, pos)
    val peaksB = AcousticChartBitmapRenderer.peaksRelevantToPosition(recB.report, pos)

    var colorPickerTargetIsA by remember { mutableStateOf<Boolean?>(null) }
    var swapRecordTargetIsA by remember { mutableStateOf<Boolean?>(null) }

    val seriesList = listOf(
        SpectrumCurveSeries(
            label = "A: ${recA.guitarName}",
            spectrum = specA,
            color = colorA,
            peaks = peaksA,
            isTopLayer = state.compareAOnTop
        ),
        SpectrumCurveSeries(
            label = "B: ${recB.guitarName}",
            spectrum = specB,
            color = colorB,
            peaks = peaksB,
            isTopLayer = !state.compareAOnTop
        )
    )

    if (isWideLandscape) {
        // 横屏 / 折叠屏大宽屏：最大化横向叠图舞台 + 右侧紧凑参数与共振峰差值栏
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1.55f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 紧凑横屏顶栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextMain)
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (p in commonPositions) {
                            FilterChip(
                                selected = p == pos,
                                onClick = { onSelectPosition(p) },
                                label = { Text(p.shortTitle, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Accent.copy(alpha = 0.22f),
                                    selectedLabelColor = Accent
                                )
                            )
                        }
                    }
                    if (posture == AppPosture.LANDSCAPE_COMPACT && state.compareForceLandscape) {
                        TextButton(
                            onClick = onToggleForceLandscape,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("退出横屏", color = Warm, fontSize = 12.sp)
                        }
                    }
                }

                // 全宽横向叠加 FFT 频谱图
                FftSpectrumChart(
                    headerTitle = "叠加对比 · ${pos.title} (${if (state.compareAOnTop) "A 在上" else "B 在上"})",
                    seriesList = seriesList,
                    options = state.compareOptions,
                    a4Hz = state.a4Hz,
                    useFlats = state.useFlats,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                SpectrumViewControlRow(
                    options = state.compareOptions,
                    onUpdateOptions = onUpdateOptions
                )
            }

            // 右侧控制与共振峰对比表
            Column(
                modifier = Modifier
                    .weight(0.75f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompareLegendAndControlsCard(
                    recA = recA,
                    recB = recB,
                    colorA = colorA,
                    colorB = colorB,
                    aOnTop = state.compareAOnTop,
                    exporting = state.exporting,
                    onPickColorA = { colorPickerTargetIsA = true },
                    onPickColorB = { colorPickerTargetIsA = false },
                    onSwapA = { swapRecordTargetIsA = true },
                    onSwapB = { swapRecordTargetIsA = false },
                    onToggleStackOrder = onToggleStackOrder,
                    onSavePng = onSaveComparePng
                )
                CompareModalDeltaCard(
                    recA = recA,
                    recB = recB,
                    position = pos,
                    options = state.compareOptions,
                    colorA = colorA,
                    colorB = colorB
                )
            }
        }
    } else {
        // 竖屏态：突出一键「横屏全屏对比」入口，同时完整可用
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextMain)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("FFT 频谱叠加对比", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("横握手机或点右侧一键横屏全屏对比", color = TextMuted, fontSize = 11.sp)
                }
                Button(
                    onClick = onToggleForceLandscape,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = InfoBlue, contentColor = Color(0xFF04121F))
                ) {
                    Text("横屏全屏", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (p in commonPositions) {
                        FilterChip(
                            selected = p == pos,
                            onClick = { onSelectPosition(p) },
                            label = { Text("${p.stepIndex}.${p.shortTitle}", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent.copy(alpha = 0.22f),
                                selectedLabelColor = Accent
                            )
                        )
                    }
                }

                FftSpectrumChart(
                    headerTitle = "叠加对比 · ${pos.title} (${if (state.compareAOnTop) "A 在上" else "B 在上"})",
                    seriesList = seriesList,
                    options = state.compareOptions,
                    a4Hz = state.a4Hz,
                    useFlats = state.useFlats,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                )

                SpectrumViewControlRow(
                    options = state.compareOptions,
                    onUpdateOptions = onUpdateOptions
                )

                CompareLegendAndControlsCard(
                    recA = recA,
                    recB = recB,
                    colorA = colorA,
                    colorB = colorB,
                    aOnTop = state.compareAOnTop,
                    exporting = state.exporting,
                    onPickColorA = { colorPickerTargetIsA = true },
                    onPickColorB = { colorPickerTargetIsA = false },
                    onSwapA = { swapRecordTargetIsA = true },
                    onSwapB = { swapRecordTargetIsA = false },
                    onToggleStackOrder = onToggleStackOrder,
                    onSavePng = onSaveComparePng
                )

                CompareModalDeltaCard(
                    recA = recA,
                    recB = recB,
                    position = pos,
                    options = state.compareOptions,
                    colorA = colorA,
                    colorB = colorB
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // 曲线选色弹窗
    colorPickerTargetIsA?.let { isA ->
        val currentArgb = if (isA) state.compareColorA else state.compareColorB
        CurveColorPickerDialog(
            title = if (isA) "选择曲线 A 颜色（${recA.guitarName}）" else "选择曲线 B 颜色（${recB.guitarName}）",
            selectedArgb = currentArgb,
            onPick = { argb ->
                onSelectColor(isA, argb)
                colorPickerTargetIsA = null
            },
            onDismiss = { colorPickerTargetIsA = null }
        )
    }

    // 更换对比记录弹窗
    swapRecordTargetIsA?.let { isSlotA ->
        SwapRecordDialog(
            title = if (isSlotA) "更换记录 A" else "更换记录 B",
            summaries = state.summaries,
            disabledId = if (isSlotA) recB.id else recA.id,
            currentId = if (isSlotA) recA.id else recB.id,
            onPick = { id ->
                onSwapRecord(isSlotA, id)
                swapRecordTargetIsA = null
            },
            onDismiss = { swapRecordTargetIsA = null }
        )
    }
}

@Composable
private fun CompareLegendAndControlsCard(
    recA: AcousticRecord,
    recB: AcousticRecord,
    colorA: Color,
    colorB: Color,
    aOnTop: Boolean,
    exporting: Boolean,
    onPickColorA: () -> Unit,
    onPickColorB: () -> Unit,
    onSwapA: () -> Unit,
    onSwapB: () -> Unit,
    onToggleStackOrder: () -> Unit,
    onSavePng: (alsoShare: Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CurveLegendRow(
            tag = "A",
            record = recA,
            color = colorA,
            isOnTop = aOnTop,
            onPickColor = onPickColorA,
            onSwap = onSwapA
        )
        CurveLegendRow(
            tag = "B",
            record = recB,
            color = colorB,
            isOnTop = !aOnTop,
            onPickColor = onPickColorB,
            onSwap = onSwapB
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = onToggleStackOrder,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.weight(1.1f)
            ) {
                Text(
                    text = if (aOnTop) "叠序：A 在上 ⇄" else "叠序：B 在上 ⇄",
                    color = TextMain,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Button(
                onClick = { onSavePng(false) },
                enabled = !exporting,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF04211A)),
                modifier = Modifier.weight(0.85f)
            ) {
                Text("存 PNG", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(
                onClick = { onSavePng(true) },
                enabled = !exporting,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = "分享对比图", tint = InfoBlue, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CurveLegendRow(
    tag: String,
    record: AcousticRecord,
    color: Color,
    isOnTop: Boolean,
    onPickColor: () -> Unit,
    onSwap: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 点击色块换曲线颜色
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(color)
                .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                .clickable(onClick = onPickColor),
            contentAlignment = Alignment.Center
        ) {
            Text(tag, color = Color(0xFF09120E), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onSwap)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.guitarName,
                    color = TextMain,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOnTop) {
                    Spacer(Modifier.width(4.dp))
                    Text("置顶", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = AcousticReportFormatter.formatDateTime(record.createdAtEpochMs) +
                    if (record.notes.isNotBlank()) " · ${record.notes}" else "",
                color = TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            onClick = onPickColor,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("配色", color = color, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CompareModalDeltaCard(
    recA: AcousticRecord,
    recB: AcousticRecord,
    position: TapPosition,
    options: SpectrumChartOptions,
    colorA: Color,
    colorB: Color
) {
    val specA = recA.spectra[position] ?: return
    val specB = recB.spectra[position] ?: return
    val refA = specA.peakMagnitudeDbfs(options.minHz, options.maxHz)
    val refB = specB.peakMagnitudeDbfs(options.minHz, options.maxHz)
    val modals = AcousticChartBitmapRenderer.modalIdsForPosition(position)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "共振峰频率与能量差值 (B − A)",
            color = Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        for (mid in modals) {
            val pA = recA.report.peaks[mid]
            val pB = recB.report.peaks[mid]
            if (pA == null && pB == null) continue
            val eA = pA?.let { if (options.normalizeToPeak) it.amplitudeDbfs - refA else it.amplitudeDbfs }
            val eB = pB?.let { if (options.normalizeToPeak) it.amplitudeDbfs - refB else it.amplitudeDbfs }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface2)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(mid.symbol, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (pA != null && pB != null && eA != null && eB != null) {
                        val dHz = pB.frequencyHz - pA.frequencyHz
                        val dCents = centsFrom(pB.frequencyHz, pA.frequencyHz)
                        val dDb = eB - eA
                        Text(
                            text = "${String.format(Locale.US, "%+.1f", dHz)}Hz (${String.format(Locale.US, "%+.0f", dCents)}¢) · ${String.format(Locale.US, "%+.1f", dDb)}dB",
                            color = InfoBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = pA?.let { "A: ${String.format(Locale.US, "%.1f", it.frequencyHz)}Hz / ${String.format(Locale.US, "%.1f", eA ?: 0.0)}dB" } ?: "A: -",
                        color = colorA,
                        fontSize = 11.sp
                    )
                    Text(
                        text = pB?.let { "B: ${String.format(Locale.US, "%.1f", it.frequencyHz)}Hz / ${String.format(Locale.US, "%.1f", eB ?: 0.0)}dB" } ?: "B: -",
                        color = colorB,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CurveColorPickerDialog(
    title: String,
    selectedArgb: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val rows = CompareColorPresets.chunked(4)
                for (row in rows) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (preset in row) {
                            val selected = preset.argb == selectedArgb
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPick(preset.argb) }
                                    .padding(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(preset.argb))
                                        .border(
                                            width = if (selected) 3.dp else 1.dp,
                                            color = if (selected) Color.White else FretWire,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color(0xFF09120E),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(preset.name, color = TextMain, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = TextMuted) }
        }
    )
}

@Composable
private fun SwapRecordDialog(
    title: String,
    summaries: List<AcousticRecordSummary>,
    disabledId: String,
    currentId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(summaries, key = { it.id }) { s ->
                    val disabled = s.id == disabledId
                    val current = s.id == currentId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (current) Surface3 else Surface1)
                            .clickable(enabled = !disabled) { onPick(s.id) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.guitarName,
                                color = if (disabled) TextMuted else TextMain,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${AcousticReportFormatter.formatDateTime(s.createdAtEpochMs)} · ${s.overallScore}分",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        if (disabled) {
                            Text("另一槽已选", color = TextMuted, fontSize = 10.sp)
                        } else if (current) {
                            Text("当前", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextMuted) }
        }
    )
}
