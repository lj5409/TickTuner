package com.gtool.feature.acoustic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.gtool.core.acoustic.AcousticRecordSummary
import com.gtool.core.acoustic.AcousticReportFormatter
import com.gtool.ui.AppPosture
import com.gtool.ui.LocalWindowPosture
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

/**
 * 声学分析档案主页：
 * - 按吉他名称归类折叠展示多次历史分析记录
 * - 支持一键新建检测、续测同一把吉他、查看报告/频谱、删除记录
 * - 支持自由双选任意两条记录（同把吉他不同时间 或 两把不同吉他）进入横向 FFT 叠图对比
 */
@Composable
fun AcousticHomeView(
    state: AcousticUiState,
    onStartNew: (prefillGuitarName: String?) -> Unit,
    onOpenRecord: (String) -> Unit,
    onDeleteRecord: (String) -> Unit,
    onToggleCompareMode: () -> Unit,
    onToggleSelectCompare: (String) -> Unit,
    onLaunchCompare: (String, String) -> Unit
) {
    val posture = LocalWindowPosture.current.posture
    val grouped = remember(state.summaries) {
        state.summaries
            .groupBy { it.guitarName }
            .entries
            .sortedByDescending { entry -> entry.value.maxOf { it.createdAtEpochMs } }
    }
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var pendingDelete by remember { mutableStateOf<AcousticRecordSummary?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "吉他声学分析",
                    color = TextMain,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "敲击 FFT 频谱采集 · 模态体检 · 曲线对比",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.summaries.size >= 2) {
                    OutlinedButton(
                        onClick = onToggleCompareMode,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (state.compareSelectMode) Warm else InfoBlue
                        )
                    ) {
                        Text(
                            text = if (state.compareSelectMode) "取消对比" else "记录对比",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Button(
                    onClick = { onStartNew(null) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        contentColor = Color(0xFF04211A)
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建检测", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 双选对比提示栏
        if (state.compareSelectMode) {
            Spacer(Modifier.height(10.dp))
            CompareSelectionBanner(
                summaries = state.summaries,
                selectedIds = state.selectedForCompareIds,
                onLaunch = {
                    if (state.selectedForCompareIds.size == 2) {
                        onLaunchCompare(state.selectedForCompareIds[0], state.selectedForCompareIds[1])
                    }
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        if (state.summaries.isEmpty()) {
            EmptyAcousticPlaceholder(
                onStartNew = { onStartNew(null) },
                modifier = Modifier.weight(1f)
            )
        } else if (posture == AppPosture.UNFOLDED_EXPANDED) {
            // 折叠屏展开态：左栏档案列表 + 右栏使用概览与快捷新建/对比卡片
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1.25f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(grouped, key = { it.key }) { (guitarName, records) ->
                        GuitarGroupCard(
                            guitarName = guitarName,
                            records = records,
                            collapsed = collapsedGroups[guitarName] == true,
                            onToggleCollapse = { collapsedGroups[guitarName] = !(collapsedGroups[guitarName] == true) },
                            compareSelectMode = state.compareSelectMode,
                            selectedCompareIds = state.selectedForCompareIds,
                            onAddForGuitar = { onStartNew(guitarName) },
                            onOpenRecord = onOpenRecord,
                            onToggleSelectCompare = onToggleSelectCompare,
                            onRequestDelete = { pendingDelete = it }
                        )
                    }
                }
                AcousticOverviewSidePanel(
                    guitarCount = grouped.size,
                    recordCount = state.summaries.size,
                    latestRecord = state.summaries.firstOrNull(),
                    onStartNew = { onStartNew(null) },
                    onOpenLatest = { state.summaries.firstOrNull()?.let { onOpenRecord(it.id) } },
                    onToggleCompare = onToggleCompareMode,
                    canCompare = state.summaries.size >= 2,
                    modifier = Modifier
                        .weight(0.75f)
                        .fillMaxHeight()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(grouped, key = { it.key }) { (guitarName, records) ->
                    GuitarGroupCard(
                        guitarName = guitarName,
                        records = records,
                        collapsed = collapsedGroups[guitarName] == true,
                        onToggleCollapse = { collapsedGroups[guitarName] = !(collapsedGroups[guitarName] == true) },
                        compareSelectMode = state.compareSelectMode,
                        selectedCompareIds = state.selectedForCompareIds,
                        onAddForGuitar = { onStartNew(guitarName) },
                        onOpenRecord = onOpenRecord,
                        onToggleSelectCompare = onToggleSelectCompare,
                        onRequestDelete = { pendingDelete = it }
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Surface2,
            title = { Text("删除声学记录", color = TextMain) },
            text = {
                Text(
                    text = "确认删除「${target.guitarName}」在 ${AcousticReportFormatter.formatDateTime(target.createdAtEpochMs)} 的 FFT 原始数据与报告？此操作不可撤销。",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteRecord(target.id)
                        pendingDelete = null
                    }
                ) {
                    Text("确认删除", color = Danger, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = TextMuted)
                }
            }
        )
    }
}

@Composable
private fun CompareSelectionBanner(
    summaries: List<AcousticRecordSummary>,
    selectedIds: List<String>,
    onLaunch: () -> Unit
) {
    val recA = selectedIds.getOrNull(0)?.let { id -> summaries.firstOrNull { it.id == id } }
    val recB = selectedIds.getOrNull(1)?.let { id -> summaries.firstOrNull { it.id == id } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2)
            .border(1.dp, InfoBlue.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "选择任意 2 条记录叠加对比（支持同琴或跨琴）",
                color = InfoBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "A: ${recA?.guitarName ?: "未选"}    B: ${recB?.guitarName ?: "未选"}",
                color = TextMain,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onLaunch,
            enabled = selectedIds.size == 2,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = InfoBlue,
                contentColor = Color(0xFF04121F)
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("开始对比", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GuitarGroupCard(
    guitarName: String,
    records: List<AcousticRecordSummary>,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    compareSelectMode: Boolean,
    selectedCompareIds: List<String>,
    onAddForGuitar: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onToggleSelectCompare: (String) -> Unit,
    onRequestDelete: (AcousticRecordSummary) -> Unit
) {
    val latest = records.first()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
    ) {
        // 吉他分组头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleCollapse)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Accent.copy(alpha = 0.16f))
                    .border(1.dp, Accent.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${latest.overallScore}",
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = guitarName,
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${latest.bodyShape.label} · 共 ${records.size} 次检测 · ${latest.qualityGrade}",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
            TextButton(
                onClick = onAddForGuitar,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("+ 复测", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Icon(
                imageVector = if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = TextMuted
            )
        }

        if (!collapsed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (rec in records) {
                    val compareSlot = selectedCompareIds.indexOf(rec.id)
                    val isSelected = compareSlot >= 0
                    RecordSummaryRow(
                        summary = rec,
                        compareSelectMode = compareSelectMode,
                        compareBadge = when (compareSlot) {
                            0 -> "A"
                            1 -> "B"
                            else -> null
                        },
                        isSelected = isSelected,
                        onClick = {
                            if (compareSelectMode) onToggleSelectCompare(rec.id) else onOpenRecord(rec.id)
                        },
                        onDelete = { onRequestDelete(rec) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordSummaryRow(
    summary: AcousticRecordSummary,
    compareSelectMode: Boolean,
    compareBadge: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = if (isSelected) InfoBlue else FretWire.copy(alpha = 0.45f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Surface3 else Surface2)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (compareSelectMode) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) InfoBlue else Surface1)
                    .border(1.5.dp, if (isSelected) InfoBlue else TextMuted, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (compareBadge != null) {
                    Text(compareBadge, color = Color(0xFF04121F), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Transparent, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = AcousticReportFormatter.formatDateTime(summary.createdAtEpochMs),
                    color = TextMain,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${summary.overallScore}分 · ${summary.topStyleLabel}",
                    color = Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(3.dp))
            val modalLine = buildString {
                summary.a0Hz?.let { append("A₀ ${String.format(Locale.US, "%.1f", it)}  ") }
                summary.t1Hz?.let { append("T₁ ${String.format(Locale.US, "%.1f", it)}  ") }
                summary.b1Hz?.let { append("B₁ ${String.format(Locale.US, "%.1f", it)} Hz") }
            }.ifEmpty { "${summary.positions.size} 个点位" }
            Text(text = modalLine, color = TextMuted, fontSize = 11.sp)
            if (summary.notes.isNotBlank()) {
                Text(
                    text = "备注：${summary.notes}",
                    color = Warm.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!compareSelectMode) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除记录",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyAcousticPlaceholder(
    onStartNew: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Surface1)
                .border(1.dp, FretWire.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "钢弦木吉他敲击 FFT 声学体检",
                color = TextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "通过标准化 5 点敲击（琴码中心 / 琴码侧翼 / 下腹中线 / 背板中心 / 封音孔解耦），离线提取 A₀、T₁、B₁、T₂,₁、T₁,₂ 等核心共振模态，排查狼音与面背板相位相消，输出客观评分、曲风适配建议，并支持导出 MD / 带图 PDF / 频谱 PNG 及双记录横屏叠图对比。",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onStartNew,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF04211A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("开始首次吉他声学检测", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AcousticOverviewSidePanel(
    guitarCount: Int,
    recordCount: Int,
    latestRecord: AcousticRecordSummary?,
    onStartNew: () -> Unit,
    onOpenLatest: () -> Unit,
    onToggleCompare: () -> Unit,
    canCompare: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("声学档案总览", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatMiniBox("归档吉他", "$guitarCount 把", Modifier.weight(1f))
            StatMiniBox("历史检测", "$recordCount 次", Modifier.weight(1f))
        }
        if (latestRecord != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface2)
                    .clickable(onClick = onOpenLatest)
                    .padding(12.dp)
            ) {
                Text("最近一次检测", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(latestRecord.guitarName, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${latestRecord.overallScore} 分 (${latestRecord.qualityGrade}) · ${latestRecord.topStyleLabel}",
                    color = Accent,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (canCompare) {
            OutlinedButton(
                onClick = onToggleCompare,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Text("选择两条记录叠图对比", color = InfoBlue, fontWeight = FontWeight.SemiBold)
            }
        }
        Button(
            onClick = onStartNew,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF04211A)),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("开始新的 5 点敲击检测", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatMiniBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(10.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
