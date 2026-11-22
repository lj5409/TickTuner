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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.acoustic.GuitarBodyShape
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

private data class PrepStepItem(
    val index: Int,
    val badge: String,
    val title: String,
    val detail: String
)

private val prepChecklistItems = listOf(
    PrepStepItem(
        index = 0,
        badge = "消音",
        title = "琴弦全阻尼消音",
        detail = "将软布条、开孔海绵或毛毡编织穿插在六根琴弦之间彻底闷音，避免裸弦极高 Q 值谐波淹没木材面板共振峰。"
    ),
    PrepStepItem(
        index = 1,
        badge = "悬空",
        title = "自由边界悬挂（勿贴桌面或身体）",
        detail = "单手轻握琴头让琴身自然悬空，或仅用柔软吸音海绵块承托琴底，避免人体/硬桌面引入附加阻尼与杂散质量。"
    ),
    PrepStepItem(
        index = 2,
        badge = "25cm",
        title = "麦克风垂直距面板 25 cm · 离地 ≥ 80 cm",
        detail = "将手机主麦克风（或外接指向麦）垂直对准敲击区域上方约 25 cm，琴体离地不低于 80 cm 以避开地面反射干涉。"
    ),
    PrepStepItem(
        index = 3,
        badge = "轻敲",
        title = "干脆单次轻敲（接触 < 5 ms，严禁连击）",
        detail = "推荐使用指关节/硬指甲垫、木质轻槌或尼龙轻槌短促轻敲，每点位连续采集 5 次合格脉冲，系统自动拦截连击与削波。"
    )
)

/**
 * 准备说明、乐器建档与麦克风输入设备选择页。
 */
@Composable
fun AcousticPrepView(
    state: AcousticUiState,
    onBack: () -> Unit,
    onGuitarNameChange: (String) -> Unit,
    onBodyShapeChange: (GuitarBodyShape) -> Unit,
    onNotesChange: (String) -> Unit,
    onSelectInputDevice: (Int) -> Unit,
    onRefreshDevices: () -> Unit,
    onTogglePrepItem: (Int) -> Unit,
    onCheckAllPrep: () -> Unit,
    onBeginCapture: () -> Unit
) {
    val scroll = rememberScrollState()
    val allChecked = state.checkedPrepItems.size == prepChecklistItems.size
    val canStart = state.draftGuitarName.trim().isNotEmpty() && allChecked

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextMain)
            }
            Column {
                Text("检测准备与建档", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("步骤 0/5 · 确认乐器信息、麦克风与物理环境", color = TextMuted, fontSize = 12.sp)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 乐器档案卡
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface1)
                    .border(1.dp, FretWire.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("1. 乐器档案（用于归类存储与多次追踪）", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = state.draftGuitarName,
                    onValueChange = onGuitarNameChange,
                    label = { Text("吉他名称 / 型号（必填）") },
                    placeholder = { Text("例如：Martin OM-28 / Taylor 314ce") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = FretWire,
                        focusedLabelColor = Accent,
                        unfocusedLabelColor = TextMuted,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain,
                        cursorColor = Accent
                    )
                )

                // 已有吉他名称快捷复用
                if (state.knownGuitarNames.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (known in state.knownGuitarNames) {
                            val selected = state.draftGuitarName.trim() == known
                            FilterChip(
                                selected = selected,
                                onClick = { onGuitarNameChange(known) },
                                label = { Text(known, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Accent.copy(alpha = 0.22f),
                                    selectedLabelColor = Accent
                                )
                            )
                        }
                    }
                }

                Text("箱体形制（选填）", color = TextMuted, fontSize = 12.sp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (shape in GuitarBodyShape.entries) {
                        val selected = state.draftBodyShape == shape
                        FilterChip(
                            selected = selected,
                            onClick = { onBodyShapeChange(shape) },
                            label = { Text(shape.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent.copy(alpha = 0.22f),
                                selectedLabelColor = Accent
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = state.draftNotes,
                    onValueChange = onNotesChange,
                    label = { Text("测试备注（选填）") },
                    placeholder = { Text("例如：新换磷青铜 012 弦 / 室内湿度 48%") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = FretWire,
                        focusedLabelColor = Accent,
                        unfocusedLabelColor = TextMuted,
                        focusedTextColor = TextMain,
                        unfocusedTextColor = TextMain,
                        cursorColor = Accent
                    )
                )
            }

            // 2. 音频输入设备选择卡（内置麦 + 外接 USB/有线指向麦）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface1)
                    .border(1.dp, FretWire.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("2. 音频输入设备（支持外接 USB / 指向麦）", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onRefreshDevices, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新设备", tint = InfoBlue, modifier = Modifier.size(18.dp))
                    }
                }
                for (opt in state.availableInputDevices) {
                    val selected = opt.id == state.selectedInputDeviceId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Surface3 else Surface2)
                            .border(
                                1.dp,
                                if (selected) (if (opt.isExternal) InfoBlue else Accent) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectInputDevice(opt.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (selected) (if (opt.isExternal) InfoBlue else Accent) else Surface1),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF04211A),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = opt.label,
                                color = if (opt.isExternal) InfoBlue else TextMain,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(text = opt.detail, color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            }

            // 3. 物理测试准备说明清单
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface1)
                    .border(1.dp, FretWire.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("3. 测量环境准备清单（勾选全部 4 项）", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (!allChecked) {
                        TextButton(
                            onClick = onCheckAllPrep,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("一键全选", color = InfoBlue, fontSize = 12.sp)
                        }
                    }
                }

                for (item in prepChecklistItems) {
                    val checked = item.index in state.checkedPrepItems
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (checked) Accent.copy(alpha = 0.12f) else Surface2)
                            .border(
                                1.dp,
                                if (checked) Accent.copy(alpha = 0.65f) else FretWire.copy(alpha = 0.4f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onTogglePrepItem(item.index) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (checked) Accent else Surface3),
                            contentAlignment = Alignment.Center
                        ) {
                            if (checked) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF04211A))
                            } else {
                                Text(item.badge, color = Warm, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(item.detail, color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        // 底部固定操作栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface1)
                .border(1.dp, FretWire.copy(alpha = 0.4f))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Button(
                onClick = onBeginCapture,
                enabled = canStart,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Color(0xFF04211A),
                    disabledContainerColor = Surface3,
                    disabledContentColor = TextMuted
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = when {
                        state.draftGuitarName.trim().isEmpty() -> "请先填写吉他名称"
                        !allChecked -> "请确认上述准备清单 (${state.checkedPrepItems.size}/4)"
                        else -> "准备就绪，开始第 1 步（琴码中心）"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
