package com.gtool.feature.tuner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.music.Note
import com.gtool.core.music.PitchClass
import com.gtool.core.music.Tuning
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Danger
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import kotlin.math.abs

// ------------------------------------------------------------------ 调弦选择

@Composable
fun TuningPickerDialog(
    tunings: List<Tuning>,
    activeId: String,
    useFlats: Boolean,
    onSelect: (Tuning) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择调弦", color = TextMain) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DialogSectionLabel("内置")
                tunings.filter { it.builtIn }.forEach { tuning ->
                    TuningPickRow(
                        tuning = tuning,
                        active = tuning.id == activeId,
                        useFlats = useFlats,
                        onSelect = { onSelect(tuning) }
                    )
                }
                val customs = tunings.filterNot { it.builtIn }
                if (customs.isNotEmpty()) {
                    DialogSectionLabel("自定义")
                    customs.forEach { tuning ->
                        TuningPickRow(
                            tuning = tuning,
                            active = tuning.id == activeId,
                            useFlats = useFlats,
                            onSelect = { onSelect(tuning) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onManage) { Text("管理调弦") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        containerColor = Surface1,
        titleContentColor = TextMain,
        textContentColor = TextMuted
    )
}

@Composable
private fun TuningPickRow(
    tuning: Tuning,
    active: Boolean,
    useFlats: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (active) Accent.copy(alpha = 0.14f) else Surface2.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = tuning.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) Accent else TextMain
                )
                Text(
                    text = tuning.joinedNames(useFlats),
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
            if (active) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "当前调弦",
                    tint = Accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 调弦管理

@Composable
fun TuningManagerDialog(
    tunings: List<Tuning>,
    activeId: String,
    useFlats: Boolean,
    activeIsBuiltIn: Boolean,
    onSelect: (Tuning) -> Unit,
    onCreate: () -> Unit,
    onEdit: (Tuning) -> Unit,
    onDelete: (Tuning) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调弦管理", color = TextMain) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DialogSectionLabel("内置（只读，可另存为自定义）")
                tunings.filter { it.builtIn }.forEach { tuning ->
                    ManageTuningRow(
                        tuning = tuning,
                        active = tuning.id == activeId,
                        useFlats = useFlats,
                        onSelect = { onSelect(tuning) },
                        onEdit = null,
                        onDelete = null
                    )
                }
                val customs = tunings.filterNot { it.builtIn }
                DialogSectionLabel("自定义")
                if (customs.isEmpty()) {
                    Text(
                        text = "还没有自定义调弦，点下方按钮基于当前调弦创建。",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                } else {
                    customs.forEach { tuning ->
                        ManageTuningRow(
                            tuning = tuning,
                            active = tuning.id == activeId,
                            useFlats = useFlats,
                            onSelect = { onSelect(tuning) },
                            onEdit = { onEdit(tuning) },
                            onDelete = { onDelete(tuning) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate) {
                Text(if (activeIsBuiltIn) "另存为自定义" else "新建自定义")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        containerColor = Surface1,
        titleContentColor = TextMain,
        textContentColor = TextMuted
    )
}

@Composable
private fun ManageTuningRow(
    tuning: Tuning,
    active: Boolean,
    useFlats: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (active) Accent.copy(alpha = 0.12f) else Surface2.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = tuning.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) Accent else TextMain
                )
                Text(
                    text = tuning.joinedNames(useFlats),
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "编辑${tuning.name}",
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除${tuning.name}",
                        tint = Danger.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (onEdit == null) {
                Text(
                    text = "内置",
                    fontSize = 11.sp,
                    color = TextMuted.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 编辑自定义调弦

@Composable
fun TuningEditorDialog(
    tuning: Tuning,
    useFlats: Boolean,
    onSave: (Tuning) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(tuning.id) { mutableStateOf(tuning.name) }
    var strings by remember(tuning.id) { mutableStateOf(tuning.strings) }
    var editingIndex by remember(tuning.id) { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑调弦", color = TextMain) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 24) name = it },
                    label = { Text("调弦名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "逐弦音高（低音弦 → 高音弦），点一行修改音名与八度。",
                    fontSize = 12.sp,
                    color = TextMuted
                )
                strings.forEachIndexed { index, note ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Surface2.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingIndex = index }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "第${strings.size - index}弦",
                                fontSize = 13.sp,
                                color = TextMuted,
                                modifier = Modifier.width(58.dp)
                            )
                            Text(
                                text = note.name(useFlats),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMain,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "修改第${strings.size - index}弦音高",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(tuning.copy(name = name, strings = strings)) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = Surface1,
        titleContentColor = TextMain,
        textContentColor = TextMuted
    )

    val index = editingIndex
    val editingNote = index?.let { strings.getOrNull(it) }
    if (index != null && editingNote != null) {
        StringNoteDialog(
            note = editingNote,
            useFlats = useFlats,
            onConfirm = { newNote ->
                strings = strings.toMutableList().also { it[index] = newNote }
                editingIndex = null
            },
            onDismiss = { editingIndex = null }
        )
    }
}

@Composable
fun StringNoteDialog(
    note: Note,
    useFlats: Boolean,
    onConfirm: (Note) -> Unit,
    onDismiss: () -> Unit
) {
    var pitchClass by remember(note) { mutableStateOf(note.pitchClass) }
    var octave by remember(note) { mutableIntStateOf(note.octave) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择音名", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PitchClass.entries.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { pc ->
                            val selected = pc == pitchClass
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) Accent.copy(alpha = 0.25f) else Surface2,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clickable { pitchClass = pc }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = pc.name(useFlats),
                                        fontSize = 16.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) Accent else TextMain
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { octave = (octave - 1).coerceAtLeast(0) }) {
                        Text("−", fontSize = 20.sp)
                    }
                    Text(
                        text = "八度 $octave",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    TextButton(onClick = { octave = (octave + 1).coerceAtMost(8) }) {
                        Text("+", fontSize = 20.sp)
                    }
                }

                Text(
                    text = "当前：${Note(pitchClass, octave).name(useFlats)}",
                    fontSize = 13.sp,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(Note(pitchClass, octave)) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = Surface1,
        titleContentColor = TextMain,
        textContentColor = TextMuted
    )
}

// ------------------------------------------------------------------ 新建/删除

@Composable
fun CreateTuningDialog(
    base: Tuning,
    useFlats: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(base.id) { mutableStateOf("${base.name} 副本") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建自定义调弦", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 24) name = it },
                    label = { Text("调弦名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "基于「${base.name}」创建：",
                    fontSize = 13.sp,
                    color = TextMuted
                )
                Text(
                    text = base.joinedNames(useFlats),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain
                )
                Text(
                    text = "创建后可在「调弦管理」里逐弦修改音名和八度。",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = Surface1,
        titleContentColor = TextMain,
        textContentColor = TextMuted
    )
}

@Composable
fun DeleteTuningDialog(
    tuning: Tuning,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除调弦", color = TextMain) },
        text = {
            Text(
                text = "确定删除「${tuning.name}」吗？删除后不可恢复。",
                fontSize = 14.sp,
                color = TextMuted,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = Danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = Surface1,
        titleContentColor = TextMain,
        textContentColor = TextMuted
    )
}

// ------------------------------------------------------------------ A4 基准

@Composable
fun A4Dialog(
    a4: Double,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableFloatStateOf(a4.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A4 基准音", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${formatOneDecimal(value.toDouble())} Hz",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 430f..450f,
                    steps = 39
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("430", fontSize = 11.sp, color = TextMuted)
                    Text("440", fontSize = 11.sp, color = TextMuted)
                    Text("450", fontSize = 11.sp, color = TextMuted)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(432.0, 440.0, 442.0).forEach { preset ->
                        TunerChip(
                            text = formatOneDecimal(preset),
                            onClick = { value = preset.toFloat() },
                            selected = abs(value - preset) < 0.01f
                        )
                    }
                }
                Text(
                    text = "影响所有目标音的频率与音分偏差，默认 440 Hz。",
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.toDouble()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        containerColor = Surface1,
        titleContentColor = TextMain,
        textContentColor = TextMuted
    )
}

@Composable
private fun DialogSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextMuted,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}
