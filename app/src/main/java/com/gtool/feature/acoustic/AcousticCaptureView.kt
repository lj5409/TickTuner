package com.gtool.feature.acoustic

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.acoustic.TapCaptureProcessor
import com.gtool.core.acoustic.TapPosition
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
import kotlin.math.ln

/**
 * 5 点图示引导敲击采集页面：
 * - 展示吉他正/背板敲击目标图示与拾音指引
 * - 自动执行 1 秒环境底噪检测
 * - 5 格有效敲击质量胶囊（支持单点击删除低分敲击补敲），实时展示等效相干度 γ² 与平均 FFT 预览
 * - 满 5 次且达标解锁下一步；嘈杂环境提供人工确认兜底；第 5 步（封音孔）允许跳过
 */
@Composable
fun AcousticCaptureView(
    state: AcousticUiState,
    onBackToPrep: () -> Unit,
    onRequestPermission: () -> Unit,
    onTogglePause: () -> Unit,
    onResetStep: () -> Unit,
    onRemoveTap: (Int) -> Unit,
    onPrevStep: () -> Unit,
    onConfirmNext: (allowOverride: Boolean) -> Unit,
    onSkipOptionalStep: () -> Unit
) {
    val posture = LocalWindowPosture.current.posture
    val wide = posture == AppPosture.UNFOLDED_EXPANDED || posture == AppPosture.LANDSCAPE_COMPACT
    val pos = state.currentTapPosition
    val snap = state.captureSnapshot

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        // 顶栏 + 5 步进度条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackToPrep) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回准备页", tint = TextMain)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${state.draftGuitarName} · 步骤 ${pos.stepIndex}/5：${pos.title}",
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "输入设备：${state.selectedInputOption.label}",
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            if (state.currentStepIndex > 0) {
                TextButton(onClick = onPrevStep) {
                    Text("上一步", color = InfoBlue, fontSize = 12.sp)
                }
            }
        }

        // 5 步横向进度胶囊
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TapPosition.ordered.forEachIndexed { idx, item ->
                val done = state.capturedSpectra.containsKey(item)
                val current = idx == state.currentStepIndex
                val bg = when {
                    current -> Accent.copy(alpha = 0.22f)
                    done -> Surface3
                    else -> Surface1
                }
                val border = when {
                    current -> Accent
                    done -> Accent.copy(alpha = 0.5f)
                    else -> FretWire.copy(alpha = 0.5f)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (done) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        text = "${item.stepIndex}.${item.shortTitle}" + if (item.optional) "(选)" else "",
                        color = if (current || done) TextMain else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        if (!state.permissionGranted) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Surface1)
                        .padding(20.dp)
                ) {
                    Text("需要麦克风权限以采集敲击声学信号", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("音频完全在本机内存中实时做 FFT 计算，不上传且不保存录音文件。", color = TextMuted, fontSize = 13.sp)
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF04211A))
                    ) {
                        Text("授予麦克风权限", fontWeight = FontWeight.Bold)
                    }
                }
            }
            return
        }

        if (wide) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GuitarTapGuideCanvas(
                        position = pos,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                    StepInstructionCard(pos)
                }
                Column(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CaptureTelemetryCard(
                        state = state,
                        snap = snap,
                        onTogglePause = onTogglePause,
                        onResetStep = onResetStep,
                        onRemoveTap = onRemoveTap
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GuitarTapGuideCanvas(
                    position = pos,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(205.dp)
                )
                StepInstructionCard(pos)
                CaptureTelemetryCard(
                    state = state,
                    snap = snap,
                    onTogglePause = onTogglePause,
                    onResetStep = onResetStep,
                    onRemoveTap = onRemoveTap
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        // 底部导航动作区
        CaptureBottomActionBar(
            pos = pos,
            snap = snap,
            onConfirmNext = onConfirmNext,
            onSkipOptionalStep = onSkipOptionalStep
        )
    }
}

@Composable
private fun StepInstructionCard(pos: TapPosition) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("敲击动作：${pos.instruction}", color = TextMain, fontSize = 13.sp, lineHeight = 18.sp)
        Text("拾音位置：${pos.micPlacement}", color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun CaptureTelemetryCard(
    state: AcousticUiState,
    snap: TapCaptureProcessor.Snapshot,
    onTogglePause: () -> Unit,
    onResetStep: () -> Unit,
    onRemoveTap: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 状态 + 底噪 + 实时电平
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val phaseLabel = when (snap.phase) {
                TapCaptureProcessor.Phase.IDLE -> "未开始监听"
                TapCaptureProcessor.Phase.CALIBRATING -> "请保持安静，正在测底噪..."
                TapCaptureProcessor.Phase.LISTENING -> "监听就绪 · 请干脆轻敲目标点"
                TapCaptureProcessor.Phase.CAPTURING_TAP -> "正在截取脉冲 (850 ms)..."
                TapCaptureProcessor.Phase.COOLDOWN -> "稳定缓冲中..."
                TapCaptureProcessor.Phase.READY -> if (snap.coherencePassed) "5 次采集完成，一致性达标" else "已满 5 次，一致性偏低"
            }
            val phaseColor = when (snap.phase) {
                TapCaptureProcessor.Phase.CALIBRATING -> Warm
                TapCaptureProcessor.Phase.CAPTURING_TAP -> InfoBlue
                TapCaptureProcessor.Phase.READY -> if (snap.coherencePassed) Accent else Warm
                else -> Accent
            }
            Text(phaseLabel, color = phaseColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onTogglePause,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(if (state.isListening) "暂停" else "继续监听", color = InfoBlue, fontSize = 12.sp)
                }
                IconButton(onClick = onResetStep, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "重测本点", tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (state.captureError != null) {
            Text(state.captureError, color = Danger, fontSize = 12.sp)
        }

        if (snap.phase == TapCaptureProcessor.Phase.CALIBRATING) {
            LinearProgressIndicator(
                progress = { snap.calibrationProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Warm,
                trackColor = Surface3
            )
        } else {
            // 实时电平条 + 底噪信息
            val levelFrac = ((snap.currentLevelDbfs + 90.0) / 90.0).toFloat().coerceIn(0f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "本底噪声：${snap.noiseFloorDbfs.toInt()} dBFS" + if (snap.noiseWarning) "（环境偏噪）" else "（良好）",
                        color = if (snap.noiseWarning) Warm else TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "实时：${snap.currentLevelDbfs.toInt()} dBFS",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
                LinearProgressIndicator(
                    progress = { levelFrac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (snap.currentLevelDbfs > -6.0) Danger else Accent,
                    trackColor = Surface3
                )
            }
        }

        // 5 格敲击质量胶囊（点击已采集胶囊可单次移除重敲）
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "有效敲击 (${snap.acceptedTaps.size}/${snap.targetTapCount})，点单格可删重敲",
                    color = TextMain,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                val cohText = if (snap.acceptedTaps.size >= 2) {
                    "一致性 γ²=${String.format(Locale.US, "%.2f", snap.coherenceScore)}"
                } else "等待敲击"
                val cohColor = when {
                    snap.acceptedTaps.size < 2 -> TextMuted
                    snap.coherenceScore >= 0.90 -> Accent
                    else -> Warm
                }
                Text(cohText, color = cohColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (i in 0 until snap.targetTapCount) {
                    val tap = snap.acceptedTaps.getOrNull(i)
                    val isLowCoh = tap != null && snap.acceptedTaps.size >= 2 && tap.coherenceToMean < 0.88
                    val slotBg = when {
                        tap == null -> Surface2
                        isLowCoh -> Warm.copy(alpha = 0.2f)
                        else -> Accent.copy(alpha = 0.2f)
                    }
                    val slotBorder = when {
                        tap == null -> FretWire
                        isLowCoh -> Warm
                        else -> Accent
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(slotBg)
                            .border(1.dp, slotBorder, RoundedCornerShape(10.dp))
                            .clickable(enabled = tap != null) { onRemoveTap(i) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (tap != null) {
                            Text(
                                text = "#${i + 1} ✓",
                                color = if (isLowCoh) Warm else Accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${(tap.coherenceToMean * 100).toInt()}%",
                                color = TextMain,
                                fontSize = 10.sp
                            )
                        } else {
                            Text("#${i + 1}", color = TextMuted, fontSize = 12.sp)
                            Text("待敲", color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // 最近一次质检反馈横幅
        snap.lastFeedback?.let { fb ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (fb.accepted) Accent.copy(alpha = 0.12f) else Danger.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (fb.accepted) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    tint = if (fb.accepted) Accent else Danger,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = fb.message,
                    color = if (fb.accepted) TextMain else Danger,
                    fontSize = 12.sp
                )
            }
        }

        // 实时平均 FFT 迷你预览图（60 – 1200 Hz）
        if (snap.previewSpectrumDbfs.isNotEmpty()) {
            LiveMiniSpectrumPreview(
                magnitudesDbfs = snap.previewSpectrumDbfs,
                binStart = snap.previewBinStart,
                freqStepHz = snap.freqStepHz,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(86.dp)
            )
        }
    }
}

@Composable
private fun LiveMiniSpectrumPreview(
    magnitudesDbfs: List<Float>,
    binStart: Int,
    freqStepHz: Double,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .padding(6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minHz = 60.0
            val maxHz = 1200.0
            val startIdx = ((minHz / freqStepHz).toInt() - binStart).coerceIn(0, magnitudesDbfs.lastIndex)
            val endIdx = ((maxHz / freqStepHz).toInt() - binStart).coerceIn(startIdx, magnitudesDbfs.lastIndex)
            if (endIdx <= startIdx) return@Canvas

            var maxDb = Float.NEGATIVE_INFINITY
            for (i in startIdx..endIdx) {
                if (magnitudesDbfs[i] > maxDb) maxDb = magnitudesDbfs[i]
            }
            val minDb = maxDb - 55f
            val path = Path()
            val steps = 160
            for (s in 0..steps) {
                val frac = s.toDouble() / steps
                val hz = minHz * Math.pow(maxHz / minHz, frac)
                val idx = ((hz / freqStepHz).toInt() - binStart).coerceIn(startIdx, endIdx)
                val v = magnitudesDbfs[idx]
                val normY = ((v - minDb) / 55f).coerceIn(0f, 1f)
                val x = size.width * frac.toFloat()
                val y = size.height * (1f - normY)
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // 网格基线
            drawLine(
                color = FretWire.copy(alpha = 0.4f),
                start = Offset(0f, size.height * 0.5f),
                end = Offset(size.width, size.height * 0.5f),
                strokeWidth = 1f
            )
            drawPath(
                path = path,
                color = Accent,
                style = Stroke(width = 2.4f, cap = StrokeCap.Round)
            )
        }
        Text(
            text = "实时平均 FFT 预览 (60–1200 Hz)",
            color = TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

@Composable
private fun CaptureBottomActionBar(
    pos: TapPosition,
    snap: TapCaptureProcessor.Snapshot,
    onConfirmNext: (allowOverride: Boolean) -> Unit,
    onSkipOptionalStep: () -> Unit
) {
    val isLast = pos == TapPosition.ordered.last()
    val fullCount = snap.acceptedTaps.size >= snap.targetTapCount
    val passed = snap.coherencePassed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (fullCount && !passed) {
            OutlinedButton(
                onClick = { onConfirmNext(true) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Warm)
            ) {
                Text(
                    text = "嘈杂环境兜底确认继续（将在报告标注放宽校验）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (pos.optional) {
                OutlinedButton(
                    onClick = onSkipOptionalStep,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(0.9f)
                        .height(48.dp)
                ) {
                    Text("跳过封音孔", color = InfoBlue, fontWeight = FontWeight.SemiBold)
                }
            }
            Button(
                onClick = { onConfirmNext(false) },
                enabled = passed,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF04211A),
                    disabledContainerColor = Surface3,
                    disabledContentColor = TextMuted
                ),
                modifier = Modifier
                    .weight(1.3f)
                    .height(48.dp)
            ) {
                val nextTitle = TapPosition.ordered.getOrNull(pos.stepIndex)?.title ?: ""
                Text(
                    text = when {
                        !fullCount -> "请完成 ${snap.acceptedTaps.size}/${snap.targetTapCount} 次有效敲击"
                        !passed -> "一致性需 ≥ 90%（请重敲低分格）"
                        isLast -> "完成采集，生成声学报告"
                        else -> "下一步：$nextTitle"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
