package com.gtool.feature.chords

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gtool.core.audio.ChordStyle
import com.gtool.core.music.ChordQuality
import com.gtool.core.music.Note
import com.gtool.core.music.PitchClass
import com.gtool.core.music.Voicing
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Bg
import com.gtool.ui.theme.ChordBlue
import com.gtool.ui.theme.Danger
import com.gtool.ui.theme.RootRed
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ChordsScreen(viewModel: ChordsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val energies = remember { List(8) { Animatable(0f) } }
    val animationJobs = remember { arrayOfNulls<Job>(8) }

    LaunchedEffect(viewModel) {
        viewModel.pluckEvents.collect { event ->
            val index = event.stringIndex
            if (index in energies.indices) {
                animationJobs[index]?.cancel()
                animationJobs[index] = launch {
                    energies[index].snapTo(1f)
                    energies[index].animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 220, easing = LinearEasing)
                    )
                }
            }
        }
    }

    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) {
            animationJobs.forEach { it?.cancel() }
            energies.forEach { it.snapTo(0f) }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // 页面不可见（退到后台）时立即停止并释放 AudioTrack。
                viewModel.onScreenDisposed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenDisposed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header(state)
        RootSelector(state = state, onSelect = viewModel::setRoot)
        QualitySelector(state = state, onSelect = viewModel::setQuality)
        ToneCard(state)
        FretboardCard(state = state, energies = energies)
        VoicingCard(
            state = state,
            onPrevious = viewModel::selectPreviousVoicing,
            onNext = viewModel::selectNextVoicing
        )
        CapoCard(state = state, onSelect = viewModel::setCapo)
        PlaybackCard(
            state = state,
            onStyle = viewModel::setStyle,
            onBpm = viewModel::setBpm,
            onLoop = viewModel::setLoop,
            onTogglePlay = viewModel::togglePlayback
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Header(state: ChordsUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "和弦查找",
                color = TextMain,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${state.tuning.name} · " +
                    if (state.capo > 0) "Capo ${state.capo} 品" else "开放把位",
                color = TextMuted,
                fontSize = 12.sp
            )
        }
        Surface(
            color = if (state.isPlaying) Accent.copy(alpha = 0.16f) else Surface2,
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.chordName,
                    color = if (state.isPlaying) Accent else TextMain,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = state.quality.displayName,
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun RootSelector(state: ChordsUiState, onSelect: (PitchClass) -> Unit) {
    SectionCard {
        SectionTitle(title = "根音", trailing = if (state.useFlats) "降号显示" else "升号显示")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(PitchClass.entries) { pitchClass ->
                FilterChip(
                    selected = pitchClass == state.root,
                    onClick = { onSelect(pitchClass) },
                    label = {
                        Text(
                            text = pitchClass.name(state.useFlats),
                            fontSize = 15.sp,
                            fontWeight = if (pitchClass == state.root) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = chipColors()
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualitySelector(state: ChordsUiState, onSelect: (ChordQuality) -> Unit) {
    SectionCard {
        SectionTitle(title = "和弦类型", trailing = state.quality.displayName)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChordQuality.entries.forEach { quality ->
                FilterChip(
                    selected = quality == state.quality,
                    onClick = { onSelect(quality) },
                    label = {
                        Text(
                            text = quality.id,
                            fontSize = 13.sp,
                            fontWeight = if (quality == state.quality) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = chipColors()
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToneCard(state: ChordsUiState) {
    SectionCard {
        SectionTitle(
            title = "组成音 · ${state.quality.displayName}",
            trailing = state.chord.quality.intervals.joinToString(" · ")
        )
        val notes = state.chord.notes(baseOctave = 3)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.chord.tones.forEachIndexed { index, tone ->
                val isRoot = tone.pitchClass == state.chord.root
                val frequency = notes.getOrNull(index)?.frequency() ?: 0.0
                Surface(
                    color = Surface2,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = tone.pitchClass.name(state.useFlats),
                            color = if (isRoot) RootRed else ChordBlue,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tone.degree,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f Hz", frequency),
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
        Text(
            text = "变调夹 ${state.capo} 品（${if (state.capo == 0) "关闭" else "开启"}） · " +
                "实际发声音高为 ${state.chordName}，指法按相对品位显示",
            color = TextMuted,
            fontSize = 11.sp,
            minLines = 2
        )
    }
}

@Composable
private fun FretboardCard(
    state: ChordsUiState,
    energies: List<Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>
) {
    SectionCard {
        SectionTitle(title = "指板", trailing = "0–15 品 · 左右滑动")
        Fretboard(
            tuning = state.tuning,
            chord = state.chord,
            capo = state.capo,
            useFlats = state.useFlats,
            voicing = state.voicing,
            stringEnergies = energies
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendDot(RootRed, "根音")
            LegendDot(ChordBlue, "和弦音")
            LegendDot(TextMuted.copy(alpha = 0.4f), "非和弦音")
            Text("× 闷音  ○ 空弦", color = TextMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun VoicingCard(
    state: ChordsUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "推荐指法",
                color = TextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            val total = state.voicings.size
            Text(
                text = if (total > 0) "${state.voicingIndex + 1} / $total" else "—",
                color = TextMuted,
                fontSize = 12.sp
            )
            TextButton(
                onClick = onPrevious,
                enabled = state.voicingIndex > 0,
                modifier = Modifier.size(48.dp)
            ) {
                Text("‹", fontSize = 26.sp, color = if (state.voicingIndex > 0) TextMain else TextMuted)
            }
            TextButton(
                onClick = onNext,
                enabled = state.voicingIndex < state.voicings.lastIndex,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    "›",
                    fontSize = 26.sp,
                    color = if (state.voicingIndex < state.voicings.lastIndex) TextMain else TextMuted
                )
            }
        }

        val voicing = state.voicing
        if (voicing != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChordDiagram(
                    tuning = state.tuning,
                    chord = state.chord,
                    capo = state.capo,
                    voicing = voicing,
                    modifier = Modifier
                        .width(232.dp)
                        .height(272.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                VoicingInfo(
                    label = "按品",
                    value = voicing.frets.joinToString(" ") { fret ->
                        if (fret < 0) "×" else fret.toString()
                    }
                )
                VoicingInfo(
                    label = "手指",
                    value = voicing.fingers.mapIndexed { index, finger ->
                        if (voicing.frets.getOrElse(index) { -1 } < 0) "×" else finger.toString()
                    }.joinToString(" ")
                )
                VoicingInfo(
                    label = "低音",
                    value = bassName(state, voicing) ?: "—"
                )
            }
            Text(
                text = if (voicing.baseFret == 0) {
                    "开放把位"
                } else {
                    "第 ${voicing.baseFret} 品起 · 跨度 ${voicing.span} 品"
                },
                color = TextMuted,
                fontSize = 11.sp
            )
        } else if (state.isLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("正在计算指法…", color = TextMuted, fontSize = 13.sp)
            }
        } else {
            Text("当前调弦下暂无可用指法", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapoCard(state: ChordsUiState, onSelect: (Int) -> Unit) {
    SectionCard {
        SectionTitle(
            title = "变调夹 Capo",
            trailing = if (state.capo == 0) "关闭" else "${state.capo} 品"
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            (0..7).forEach { capo ->
                FilterChip(
                    selected = state.capo == capo,
                    onClick = { onSelect(capo) },
                    label = {
                        Text(
                            text = capo.toString(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    modifier = Modifier
                        .width(48.dp)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = chipColors()
                )
            }
        }
        Text(
            text = "发声音高 = 空弦 + Capo + 品位；指法图与品位按相对位置显示。",
            color = TextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun PlaybackCard(
    state: ChordsUiState,
    onStyle: (ChordStyle) -> Unit,
    onBpm: (Int) -> Unit,
    onLoop: (Boolean) -> Unit,
    onTogglePlay: () -> Unit
) {
    SectionCard {
        SectionTitle(
            title = "播放",
            trailing = if (state.isPlaying) "播放中" else "已停止"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChordStyle.entries.forEach { style ->
                FilterChip(
                    selected = state.style == style,
                    onClick = { onStyle(style) },
                    label = {
                        Text(
                            text = style.label,
                            fontSize = 13.sp,
                            fontWeight = if (state.style == style) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = chipColors()
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("速度", color = TextMuted, fontSize = 12.sp, modifier = Modifier.width(34.dp))
            Slider(
                value = state.bpm.toFloat(),
                onValueChange = { onBpm(it.roundToInt()) },
                valueRange = 60f..120f,
                steps = 59,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${state.bpm}",
                color = TextMain,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(58.dp)
            )
            Text("BPM", color = TextMuted, fontSize = 10.sp)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("循环", color = TextMuted, fontSize = 13.sp)
            Switch(
                checked = state.loop,
                onCheckedChange = onLoop,
                modifier = Modifier.padding(start = 6.dp)
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onTogglePlay,
                enabled = state.voicing != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isPlaying) Danger else Accent,
                    contentColor = Color(0xFF04211A)
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (state.isPlaying) "■ 停止" else "▶ 播放",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            text = "扫弦每弦间隔 30 ms；分解为 53231323 八分音符。播放时指板逐弦高亮。",
            color = TextMuted,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Surface1,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            color = TextMain,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(text = trailing, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun VoicingInfo(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextMuted, fontSize = 10.sp)
        Text(value, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Surface2,
    labelColor = TextMain,
    selectedContainerColor = Accent,
    selectedLabelColor = Color(0xFF04211A)
)

private fun bassName(state: ChordsUiState, voicing: Voicing): String? {
    val bassIndex = voicing.frets.indexOfFirst { it >= 0 }
    if (bassIndex < 0) return null
    val note: Note = com.gtool.core.music.VoicingGenerator.soundingNote(
        tuning = state.tuning,
        stringIndex = bassIndex,
        capo = state.capo,
        fret = voicing.frets[bassIndex]
    ) ?: return null
    return note.pitchClass.name(state.useFlats)
}
