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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.gtool.core.acoustic.AcousticReportFormatter
import com.gtool.core.acoustic.DefectSeverity
import com.gtool.core.acoustic.GuitarBodyShape
import com.gtool.core.acoustic.ModalId
import com.gtool.core.acoustic.SpectrumChartOptions
import com.gtool.core.acoustic.TapPosition
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Bg
import com.gtool.ui.theme.Danger
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.InfoBlue
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.Surface3
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm
import java.util.Locale
import kotlin.math.abs

/**
 * 声学记录三合一详情页：
 * - 「声学报告」：综合评级、核心模态表、缺陷诊断、5维得分、4大曲风适配、保养建议、MD/PDF 导出
 * - 「FFT 频谱」：各点位频谱交互图（注明部位/模态代号/频率）、手动微调共振峰自动重算、PNG 保存相册/分享
 * - 「原始数据」：各点位采样元信息、5 次敲击质量明细、完整离散频率-dB 表及 CSV/JSON 导出
 */
@Composable
fun AcousticReportView(
    state: AcousticUiState,
    onBack: () -> Unit,
    onSelectTab: (DetailTab) -> Unit,
    onSelectSpectrumPosition: (TapPosition) -> Unit,
    onSelectRawDataPosition: (TapPosition) -> Unit,
    onUpdateChartOptions: ((SpectrumChartOptions) -> SpectrumChartOptions) -> Unit,
    onSetTuningModal: (ModalId?) -> Unit,
    onApplyManualPeak: (ModalId, Double) -> Unit,
    onResetManualPeaks: () -> Unit,
    onSaveSpectrumPng: (alsoShare: Boolean) -> Unit,
    onShareDoc: (ExportDocumentKind, TapPosition?) -> Unit,
    onSaveDoc: (ExportDocumentKind, TapPosition?) -> Unit,
    onOpenCompare: () -> Unit,
    onUpdateMetadata: (guitarName: String, shape: GuitarBodyShape, notes: String) -> Unit
) {
    val record = state.activeRecord ?: return
    var showEditDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回档案列表", tint = TextMain)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.guitarName,
                    color = TextMain,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${record.bodyShape.label} · ${AcousticReportFormatter.formatDateTime(record.createdAtEpochMs)}",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = { showEditDialog = true }) {
                Icon(Icons.Default.Edit, contentDescription = "编辑档案信息", tint = TextMuted)
            }
            if (state.summaries.size >= 2) {
                TextButton(onClick = onOpenCompare) {
                    Text("对比", color = InfoBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // 三段切换 Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface1)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (tab in DetailTab.entries) {
                val selected = state.detailTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) Accent else Color.Transparent)
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        color = if (selected) Color(0xFF04211A) else TextMuted,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        when (state.detailTab) {
            DetailTab.REPORT -> ReportSummaryTab(
                record = record,
                exporting = state.exporting,
                onShareDoc = { kind -> onShareDoc(kind, null) },
                onSaveDoc = { kind -> onSaveDoc(kind, null) },
                modifier = Modifier.weight(1f)
            )

            DetailTab.SPECTRUM -> SpectrumDetailTab(
                state = state,
                record = record,
                onSelectPosition = onSelectSpectrumPosition,
                onUpdateOptions = onUpdateChartOptions,
                onSetTuningModal = onSetTuningModal,
                onApplyManualPeak = onApplyManualPeak,
                onResetManualPeaks = onResetManualPeaks,
                onSavePng = onSaveSpectrumPng,
                modifier = Modifier.weight(1f)
            )

            DetailTab.RAW_DATA -> RawDataTab(
                record = record,
                selectedPosition = state.rawDataPosition,
                exporting = state.exporting,
                onSelectPosition = onSelectRawDataPosition,
                onShareDoc = onShareDoc,
                onSaveDoc = onSaveDoc,
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showEditDialog) {
        EditRecordMetadataDialog(
            record = record,
            onDismiss = { showEditDialog = false },
            onSave = { name, shape, notes ->
                onUpdateMetadata(name, shape, notes)
                showEditDialog = false
            }
        )
    }
}

@Composable
private fun ReportSummaryTab(
    record: AcousticRecord,
    exporting: Boolean,
    onShareDoc: (ExportDocumentKind) -> Unit,
    onSaveDoc: (ExportDocumentKind) -> Unit,
    modifier: Modifier = Modifier
) {
    val report = record.report
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 综合品质评分卡
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface1)
                .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(Accent.copy(alpha = 0.16f))
                    .border(2.5.dp, Accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${report.overallScore}", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text("总分", color = TextMuted, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(report.qualityGrade, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                val topStyle = report.styleMatches.firstOrNull()
                if (topStyle != null) {
                    Text(
                        "最佳适配：${topStyle.style.label} (${topStyle.matchPercent}%)",
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                val flags = buildList {
                    add("最低 γ²=${String.format(Locale.US, "%.2f", report.minCoherence)}")
                    if (report.decoupleSkipped) add("跳过封音孔")
                    if (report.hasQualityOverride) add("含人工放宽点位")
                    if (record.manualPeakOverrides.isNotEmpty()) add("含手动微调模态")
                }.joinToString(" · ")
                Text(flags, color = TextMuted, fontSize = 11.sp)
                if (record.notes.isNotBlank()) {
                    Text("备注：${record.notes}", color = Warm, fontSize = 11.sp)
                }
            }
        }

        // 2. 核心共振模态实测表
        SectionCard(title = "核心共振模态实测数据") {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("模态", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(0.75f))
                Text("频率(Hz)", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("Q值", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(0.7f))
                Text("相对dB", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(0.85f))
                Text("音高偏差", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1.2f))
            }
            Spacer(Modifier.height(4.dp))
            val order = listOf(ModalId.A0, ModalId.T1, ModalId.B1, ModalId.T21, ModalId.T12, ModalId.T1_UNCOUPLED)
            for (id in order) {
                val p = report.peaks[id] ?: continue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Surface2)
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = id.symbol + if (p.isManualOverride) "*" else "",
                        color = if (p.isManualOverride) InfoBlue else Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(0.75f)
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f", p.frequencyHz),
                        color = TextMain,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f", p.qFactor),
                        color = TextMain,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(0.7f)
                    )
                    Text(
                        text = String.format(Locale.US, "%+.1f", p.relativeDb),
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(0.85f)
                    )
                    val noteCell = if (id == ModalId.A0 || id == ModalId.T1 || id == ModalId.B1 || id == ModalId.T1_UNCOUPLED) {
                        "${p.nearestNoteName} (${String.format(Locale.US, "%+.0f", p.centsOffset)}¢)"
                    } else "-"
                    Text(noteCell, color = TextMain, fontSize = 11.sp, modifier = Modifier.weight(1.2f))
                }
            }
            Spacer(Modifier.height(4.dp))
            val topo = buildString {
                if (report.backTopDiffHz != null && report.backTopDiffSemitones != null) {
                    append("面背频差 ${String.format(Locale.US, "%+.1f", report.backTopDiffHz)} Hz (${String.format(Locale.US, "%+.1f", report.backTopDiffSemitones)} 半音)")
                }
                if (report.dipoleRatio != null) {
                    if (isNotEmpty()) append(" · ")
                    append("纵偶极比 ${String.format(Locale.US, "%.2f", report.dipoleRatio)}")
                }
                if (report.decoupleShiftHz != null) {
                    if (isNotEmpty()) append(" · ")
                    append("解耦频移 ${String.format(Locale.US, "%+.1f", report.decoupleShiftHz)} Hz")
                }
            }
            if (topo.isNotEmpty()) {
                Text(topo, color = InfoBlue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        // 3. 声学缺陷诊断
        SectionCard(title = "声学缺陷与物理稳定性诊断") {
            for (defect in report.defects) {
                val color = when (defect.severity) {
                    DefectSeverity.OK -> Accent
                    DefectSeverity.MODERATE -> Warm
                    DefectSeverity.SEVERE -> Danger
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(defect.title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(defect.severity.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(defect.summary, color = color, fontSize = 12.sp)
                    Text(defect.detail, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }

        // 4. 五维量化评级
        SectionCard(title = "音质五维客观评分") {
            for (dim in report.dimensionScores) {
                val barCol = when {
                    dim.score >= 85 -> Accent
                    dim.score >= 65 -> Warm
                    else -> Danger
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${dim.label} (${dim.weightPercent}%)", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${dim.score} / 100", color = barCol, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { (dim.score / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = barCol,
                        trackColor = Surface3
                    )
                    Text(dim.basis, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }

        // 5. 四大曲风适配度
        SectionCard(title = "演奏风格与曲风适配推断") {
            report.styleMatches.forEachIndexed { idx, m ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = m.style.label + if (idx == 0) " ★ 最佳" else "",
                            color = if (idx == 0) Accent else TextMain,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("${m.matchPercent}%", color = if (idx == 0) Accent else InfoBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { (m.matchPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (idx == 0) Accent else InfoBlue,
                        trackColor = Surface3
                    )
                    Text(m.reason, color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
            if (report.notRecommendedHint.isNotBlank()) {
                Text(
                    text = "局限提示：${report.notRecommendedHint}",
                    color = Warm,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        // 6. 微调与保养建议
        SectionCard(title = "针对性微调与保养建议") {
            Text("琴弦张力匹配：${report.stringTensionAdvice}", color = TextMain, fontSize = 12.sp, lineHeight = 18.sp)
            Text("结构微调方向：${report.voicingAdvice}", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }

        // 7. 报告导出（MD / PDF）
        SectionCard(title = "导出声学报告（离线生成）") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onSaveDoc(ExportDocumentKind.MARKDOWN) },
                    enabled = !exporting,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF04211A)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("另存 MD", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { onShareDoc(ExportDocumentKind.MARKDOWN) },
                    enabled = !exporting,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = Accent)
                    Spacer(Modifier.width(4.dp))
                    Text("分享 MD", color = Accent, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onSaveDoc(ExportDocumentKind.PDF) },
                    enabled = !exporting,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = InfoBlue, contentColor = Color(0xFF04121F)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("另存带图 PDF", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { onShareDoc(ExportDocumentKind.PDF) },
                    enabled = !exporting,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = InfoBlue)
                    Spacer(Modifier.width(4.dp))
                    Text("分享 PDF", color = InfoBlue, fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun SpectrumDetailTab(
    state: AcousticUiState,
    record: AcousticRecord,
    onSelectPosition: (TapPosition) -> Unit,
    onUpdateOptions: ((SpectrumChartOptions) -> SpectrumChartOptions) -> Unit,
    onSetTuningModal: (ModalId?) -> Unit,
    onApplyManualPeak: (ModalId, Double) -> Unit,
    onResetManualPeaks: () -> Unit,
    onSavePng: (alsoShare: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val availablePositions = TapPosition.ordered.filter { record.spectra.containsKey(it) }
    val pos = state.selectedSpectrumPosition.takeIf { record.spectra.containsKey(it) }
        ?: availablePositions.firstOrNull()
        ?: return
    val spec = record.spectra[pos] ?: return
    val peaks = AcousticChartBitmapRenderer.peaksRelevantToPosition(record.report, pos)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 点位选择芯片
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (p in availablePositions) {
                val sel = p == pos
                FilterChip(
                    selected = sel,
                    onClick = { onSelectPosition(p) },
                    label = { Text("${p.stepIndex}.${p.shortTitle}", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent.copy(alpha = 0.22f),
                        selectedLabelColor = Accent
                    )
                )
            }
        }

        // 交互式 FFT 频谱主图
        FftSpectrumChart(
            headerTitle = "敲击部位：${pos.title} (${pos.code})",
            seriesList = listOf(
                SpectrumCurveSeries(
                    label = record.guitarName,
                    spectrum = spec,
                    color = Accent,
                    peaks = peaks,
                    isTopLayer = true
                )
            ),
            options = state.chartOptions,
            a4Hz = record.a4Hz,
            useFlats = state.useFlats,
            tuningModalTarget = state.tuningModalTarget,
            onManualPeakSelected = onApplyManualPeak,
            modifier = Modifier
                .fillMaxWidth()
                .height(310.dp)
        )

        // 频段视角 & 坐标轴控制栏
        SpectrumViewControlRow(
            options = state.chartOptions,
            onUpdateOptions = onUpdateOptions
        )

        // 共振峰摘要 & 手动微调卡片
        SectionCard(title = "共振峰标注与手动微调（点击模态后在图上点选重定位）") {
            val editableModals = AcousticChartBitmapRenderer.modalIdsForPosition(pos)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (mid in editableModals) {
                    val peak = record.report.peaks[mid]
                    val active = state.tuningModalTarget == mid
                    FilterChip(
                        selected = active,
                        onClick = { onSetTuningModal(if (active) null else mid) },
                        label = {
                            val freqStr = peak?.let { "${String.format(Locale.US, "%.1f", it.frequencyHz)}Hz" } ?: "未检出"
                            Text("${mid.symbol} $freqStr", fontSize = 12.sp)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Warm.copy(alpha = 0.25f),
                            selectedLabelColor = Warm
                        )
                    )
                }
            }
            if (state.tuningModalTarget != null) {
                Text(
                    text = "微调中：请在上方频谱图点击或拖动至新的 ${state.tuningModalTarget.symbol} 峰值，松手将自动吸附局部峰并重算完整报告。",
                    color = Warm,
                    fontSize = 12.sp
                )
            }
            if (record.manualPeakOverrides.isNotEmpty()) {
                TextButton(
                    onClick = onResetManualPeaks,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text("恢复全部自动识别共振峰 (${record.manualPeakOverrides.size} 处已微调)", color = InfoBlue, fontSize = 12.sp)
                }
            }
        }

        // 保存频谱 PNG 到相册 / 分享
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { onSavePng(false) },
                enabled = !state.exporting,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF04211A)),
                modifier = Modifier
                    .weight(1.2f)
                    .height(46.dp)
            ) {
                Text("下载频谱图 (PNG)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { onSavePng(true) },
                enabled = !state.exporting,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(0.9f)
                    .height(46.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = InfoBlue)
                Spacer(Modifier.width(4.dp))
                Text("保存并分享", color = InfoBlue, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun SpectrumViewControlRow(
    options: SpectrumChartOptions,
    onUpdateOptions: ((SpectrumChartOptions) -> SpectrumChartOptions) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val isModalRange = abs(options.minHz - 60.0) < 1.0 && abs(options.maxHz - 600.0) < 1.0
        val isMidRange = abs(options.minHz - 50.0) < 1.0 && abs(options.maxHz - 1200.0) < 1.0
        val isFullRange = abs(options.minHz - 50.0) < 1.0 && abs(options.maxHz - 5000.0) < 1.0

        FilterChip(
            selected = isModalRange,
            onClick = { onUpdateOptions { it.copy(minHz = 60.0, maxHz = 600.0) } },
            label = { Text("主模态 60–600", fontSize = 11.sp) }
        )
        FilterChip(
            selected = isMidRange,
            onClick = { onUpdateOptions { it.copy(minHz = 50.0, maxHz = 1200.0) } },
            label = { Text("标准 50–1.2k", fontSize = 11.sp) }
        )
        FilterChip(
            selected = isFullRange,
            onClick = { onUpdateOptions { it.copy(minHz = 50.0, maxHz = 5000.0) } },
            label = { Text("全频 50–5k", fontSize = 11.sp) }
        )
        FilterChip(
            selected = options.logScale,
            onClick = { onUpdateOptions { it.copy(logScale = !it.logScale) } },
            label = { Text(if (options.logScale) "对数轴" else "线性轴", fontSize = 11.sp) }
        )
        FilterChip(
            selected = options.normalizeToPeak,
            onClick = { onUpdateOptions { it.copy(normalizeToPeak = !it.normalizeToPeak) } },
            label = { Text(if (options.normalizeToPeak) "0 dB 对齐" else "原始 dBFS", fontSize = 11.sp) }
        )
    }
}

private enum class RawBandFilter(val label: String, val minHz: Double, val maxHz: Double) {
    LOW_MODAL("主模态 60–500 Hz", 60.0, 500.0),
    MID("中频 500–2000 Hz", 500.0, 2000.0),
    HIGH("高频 2–6 kHz", 2000.0, 6000.0),
    ALL("全部频点", 0.0, 10000.0)
}

@Composable
private fun RawDataTab(
    record: AcousticRecord,
    selectedPosition: TapPosition,
    exporting: Boolean,
    onSelectPosition: (TapPosition) -> Unit,
    onShareDoc: (ExportDocumentKind, TapPosition?) -> Unit,
    onSaveDoc: (ExportDocumentKind, TapPosition?) -> Unit,
    modifier: Modifier = Modifier
) {
    val available = TapPosition.ordered.filter { record.spectra.containsKey(it) }
    val pos = selectedPosition.takeIf { record.spectra.containsKey(it) } ?: available.firstOrNull() ?: return
    val spec = record.spectra[pos] ?: return
    var band by remember { mutableStateOf(RawBandFilter.LOW_MODAL) }

    val peakRef = remember(spec) { spec.peakMagnitudeDbfs() }
    val filteredBins = remember(spec, band) {
        val lo = spec.localIndexForFrequency(band.minHz)
        val hi = spec.localIndexForFrequency(band.maxHz)
        (lo..hi).map { idx ->
            Triple(spec.frequencyAt(idx), spec.magnitudesDbfs[idx].toDouble(), spec.magnitudesDbfs[idx].toDouble() - peakRef)
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 点位选择
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (p in available) {
                FilterChip(
                    selected = p == pos,
                    onClick = { onSelectPosition(p) },
                    label = { Text("${p.stepIndex}.${p.shortTitle}", fontSize = 12.sp) }
                )
            }
        }

        // 采样元信息与 5 次敲击摘要
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface1)
                .border(1.dp, FretWire.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "采样 ${spec.sampleRateHz} Hz · FFT ${spec.fftSize} 点 (Δf=${String.format(Locale.US, "%.2f", spec.freqStepHz)} Hz) · 底噪 ${spec.noiseFloorDbfs.toInt()} dBFS · γ²=${String.format(Locale.US, "%.2f", spec.coherenceScore)}",
                color = TextMain,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            if (spec.taps.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (t in spec.taps) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Surface2)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text("第${t.tapIndex}击 · ${(t.coherenceToMean * 100).toInt()}%", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("SNR ${t.snrDb.toInt()}dB · 起振 ${t.attackTimeMs.toInt()}ms", color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // 频段筛选
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (b in RawBandFilter.entries) {
                FilterChip(
                    selected = band == b,
                    onClick = { band = b },
                    label = { Text(b.label, fontSize = 11.sp) }
                )
            }
        }

        // 表头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Surface2)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("频率 (Hz)", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("原始电平 (dBFS)", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("相对峰值 (dB)", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }

        // 虚拟滚动原始频点表
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Surface1),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(filteredBins) { (hz, dbfs, rel) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(String.format(Locale.US, "%.2f", hz), color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(String.format(Locale.US, "%.2f", dbfs), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(
                        String.format(Locale.US, "%+.2f", rel),
                        color = if (rel > -6.0) Accent else TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 导出原始 CSV / JSON 按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onSaveDoc(ExportDocumentKind.RAW_CSV, null) },
                enabled = !exporting,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF04211A)),
                modifier = Modifier.weight(1f)
            ) {
                Text("另存 CSV", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { onShareDoc(ExportDocumentKind.RAW_CSV, null) },
                enabled = !exporting,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("分享 CSV", color = Accent, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { onSaveDoc(ExportDocumentKind.RAW_JSON, null) },
                enabled = !exporting,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("导出 JSON", color = InfoBlue, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun EditRecordMetadataDialog(
    record: AcousticRecord,
    onDismiss: () -> Unit,
    onSave: (String, GuitarBodyShape, String) -> Unit
) {
    var name by remember { mutableStateOf(record.guitarName) }
    var shape by remember { mutableStateOf(record.bodyShape) }
    var notes by remember { mutableStateOf(record.notes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        title = { Text("编辑乐器档案信息", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("吉他名称") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (s in GuitarBodyShape.entries) {
                        FilterChip(
                            selected = shape == s,
                            onClick = { shape = s },
                            label = { Text(s.label, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("备注") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim().ifEmpty { record.guitarName }, shape, notes) }
            ) {
                Text("保存", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextMuted)
            }
        }
    )
}
