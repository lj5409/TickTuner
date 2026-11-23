package com.gtool.feature.acoustic

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gtool.GtoolApplication
import com.gtool.core.acoustic.AcousticAnalyzer
import com.gtool.core.acoustic.AcousticChartBitmapRenderer
import com.gtool.core.acoustic.AcousticFileExportHelper
import com.gtool.core.acoustic.AcousticPdfExporter
import com.gtool.core.acoustic.AcousticRecord
import com.gtool.core.acoustic.AcousticRecordSummary
import com.gtool.core.acoustic.AcousticReportFormatter
import com.gtool.core.acoustic.GuitarBodyShape
import com.gtool.core.acoustic.ModalId
import com.gtool.core.acoustic.PositionSpectrum
import com.gtool.core.acoustic.SpectrumChartOptions
import com.gtool.core.acoustic.TapCaptureProcessor
import com.gtool.core.acoustic.TapPosition
import com.gtool.core.audio.AudioInputDeviceManager
import com.gtool.core.audio.AudioInputOption
import com.gtool.core.audio.TapAudioCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class AcousticPage {
    HOME,
    PREP,
    CAPTURE,
    DETAIL,
    COMPARE
}

enum class DetailTab(val label: String) {
    REPORT("声学报告"),
    SPECTRUM("FFT 频谱"),
    RAW_DATA("原始数据")
}

data class CurveColorPreset(
    val name: String,
    val argb: Long
)

val CompareColorPresets = listOf(
    CurveColorPreset("翡翠绿", 0xFF2EE6A8),
    CurveColorPreset("琥珀金", 0xFFFFB86B),
    CurveColorPreset("天空蓝", 0xFF4DA3FF),
    CurveColorPreset("珊瑚红", 0xFFFF5A6F),
    CurveColorPreset("亮紫", 0xFFC084FC),
    CurveColorPreset("柠檬黄", 0xFFFDE047),
    CurveColorPreset("青蓝", 0xFF22D3EE),
    CurveColorPreset("纯白", 0xFFF1F5F9)
)

enum class ExportDocumentKind(val mimeType: String, val extension: String, val label: String) {
    MARKDOWN("text/markdown", "md", "Markdown 报告"),
    PDF("application/pdf", "pdf", "PDF 诊断报告"),
    RAW_CSV("text/csv", "csv", "原始 FFT CSV"),
    RAW_JSON("application/json", "json", "原始 JSON")
}

data class PendingSaveRequest(
    val kind: ExportDocumentKind,
    val suggestedFileName: String,
    val record: AcousticRecord,
    val onlyPosition: TapPosition? = null
)

data class AcousticUiState(
    val page: AcousticPage = AcousticPage.HOME,
    val summaries: List<AcousticRecordSummary> = emptyList(),
    val a4Hz: Double = 440.0,
    val useFlats: Boolean = false,
    val permissionGranted: Boolean = false,
    val availableInputDevices: List<AudioInputOption> = listOf(AudioInputOption.AUTO),
    val selectedInputDeviceId: Int = AudioInputOption.AUTO_ID,

    // 建档与准备页状态
    val draftGuitarName: String = "",
    val draftBodyShape: GuitarBodyShape = GuitarBodyShape.DREADNOUGHT,
    val draftNotes: String = "",
    val checkedPrepItems: Set<Int> = emptySet(),

    // 5 点敲击采集状态
    val currentStepIndex: Int = 0,
    val stepMeasurementStarted: Boolean = false,
    val capturedSpectra: Map<TapPosition, PositionSpectrum> = emptyMap(),
    val captureSnapshot: TapCaptureProcessor.Snapshot = TapCaptureProcessor().snapshot(),
    val isListening: Boolean = false,
    val captureError: String? = null,

    // 详情页状态（报告 / 频谱 / 原始数据）
    val activeRecord: AcousticRecord? = null,
    val detailTab: DetailTab = DetailTab.REPORT,
    val selectedSpectrumPosition: TapPosition = TapPosition.BRIDGE,
    val chartOptions: SpectrumChartOptions = SpectrumChartOptions(
        minHz = 50.0,
        maxHz = 1200.0,
        logScale = true,
        normalizeToPeak = true
    ),
    val tuningModalTarget: ModalId? = null,
    val rawDataPosition: TapPosition = TapPosition.BRIDGE,

    // 双记录对比状态
    val compareSelectMode: Boolean = false,
    val selectedForCompareIds: List<String> = emptyList(),
    val compareRecordA: AcousticRecord? = null,
    val compareRecordB: AcousticRecord? = null,
    val comparePosition: TapPosition = TapPosition.BRIDGE,
    val compareColorA: Long = CompareColorPresets[0].argb,
    val compareColorB: Long = CompareColorPresets[1].argb,
    val compareAOnTop: Boolean = true,
    val compareOptions: SpectrumChartOptions = SpectrumChartOptions(
        minHz = 50.0,
        maxHz = 1200.0,
        logScale = true,
        normalizeToPeak = true
    ),
    val compareForceLandscape: Boolean = false,

    // 提示 & 导出
    val exporting: Boolean = false,
    val snackbarMessage: String? = null,
    val pendingSaveRequest: PendingSaveRequest? = null,
    val lastSavedImageUri: Uri? = null
) {
    val currentTapPosition: TapPosition
        get() = TapPosition.ordered.getOrElse(currentStepIndex) { TapPosition.BRIDGE }

    val selectedInputOption: AudioInputOption
        get() = availableInputDevices.firstOrNull { it.id == selectedInputDeviceId }
            ?: availableInputDevices.firstOrNull()
            ?: AudioInputOption.AUTO

    val knownGuitarNames: List<String>
        get() = summaries.map { it.guitarName }.distinct()

    val commonComparePositions: List<TapPosition>
        get() {
            val a = compareRecordA ?: return emptyList()
            val b = compareRecordB ?: return emptyList()
            return TapPosition.ordered.filter { a.spectra.containsKey(it) && b.spectra.containsKey(it) }
        }
}

/**
 * 吉他敲击 FFT 声学分析模块 ViewModel：
 * 统筹设备枚举、5 点引导采集状态机、本地档案存取、共振峰人工微调重算、双记录对比及离线导出。
 */
class AcousticViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as GtoolApplication
    private val settingsRepo = app.repository
    private val acousticRepo = app.acousticRepository
    private val deviceManager = AudioInputDeviceManager(application)

    private var processor = TapCaptureProcessor()
    private var tapCapture: TapAudioCapture? = null
    private var lastUiEmitMs = 0L

    private val _state = MutableStateFlow(AcousticUiState())
    val state: StateFlow<AcousticUiState> = _state.asStateFlow()

    init {
        refreshPermission()
        deviceManager.start()
        viewModelScope.launch {
            acousticRepo.refreshIndex()
        }
        viewModelScope.launch {
            acousticRepo.summaries.collect { list ->
                _state.update { it.copy(summaries = list) }
            }
        }
        viewModelScope.launch {
            settingsRepo.state.collect { appState ->
                _state.update {
                    it.copy(
                        a4Hz = appState.settings.a4,
                        useFlats = appState.settings.useFlats
                    )
                }
            }
        }
        viewModelScope.launch {
            deviceManager.devices.collect { devs ->
                _state.update { cur ->
                    val validId = if (devs.any { it.id == cur.selectedInputDeviceId }) {
                        cur.selectedInputDeviceId
                    } else {
                        AudioInputOption.AUTO_ID
                    }
                    cur.copy(availableInputDevices = devs, selectedInputDeviceId = validId)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 权限与生命周期

    fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        _state.update { it.copy(permissionGranted = granted) }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update {
            it.copy(
                permissionGranted = granted,
                captureError = if (granted) null else "需要麦克风权限才能采集吉他敲击频谱"
            )
        }
        val cur = _state.value
        if (granted && cur.page == AcousticPage.CAPTURE && cur.stepMeasurementStarted && !cur.isListening) {
            startListeningCurrentStep(recalibrate = true)
        }
    }

    fun onPause() {
        stopCaptureInternal()
    }

    fun onResume() {
        refreshPermission()
        deviceManager.refresh()
    }

    override fun onCleared() {
        stopCaptureInternal()
        deviceManager.stop()
        super.onCleared()
    }

    // ------------------------------------------------------------------ 页面导航

    fun navigateBack(): Boolean {
        val cur = _state.value
        return when (cur.page) {
            AcousticPage.HOME -> {
                if (cur.compareSelectMode) {
                    _state.update { it.copy(compareSelectMode = false, selectedForCompareIds = emptyList()) }
                    true
                } else false
            }

            AcousticPage.PREP -> {
                _state.update { it.copy(page = AcousticPage.HOME) }
                true
            }

            AcousticPage.CAPTURE -> {
                stopCaptureInternal()
                _state.update { it.copy(page = AcousticPage.PREP) }
                true
            }

            AcousticPage.DETAIL -> {
                _state.update {
                    it.copy(
                        page = AcousticPage.HOME,
                        tuningModalTarget = null
                    )
                }
                true
            }

            AcousticPage.COMPARE -> {
                _state.update {
                    it.copy(
                        page = AcousticPage.HOME,
                        compareForceLandscape = false
                    )
                }
                true
            }
        }
    }

    fun openNewAnalysisPrep(prefillGuitarName: String? = null) {
        stopCaptureInternal()
        deviceManager.refresh()
        _state.update {
            it.copy(
                page = AcousticPage.PREP,
                draftGuitarName = prefillGuitarName ?: it.draftGuitarName,
                checkedPrepItems = emptySet(),
                capturedSpectra = emptyMap(),
                currentStepIndex = 0,
                captureError = null,
                compareSelectMode = false,
                selectedForCompareIds = emptyList()
            )
        }
    }

    fun updateDraftGuitarName(name: String) {
        _state.update { it.copy(draftGuitarName = name) }
    }

    fun updateDraftBodyShape(shape: GuitarBodyShape) {
        _state.update { it.copy(draftBodyShape = shape) }
    }

    fun updateDraftNotes(notes: String) {
        _state.update { it.copy(draftNotes = notes) }
    }

    fun togglePrepItem(index: Int) {
        _state.update { cur ->
            val next = cur.checkedPrepItems.toMutableSet()
            if (!next.add(index)) next.remove(index)
            cur.copy(checkedPrepItems = next)
        }
    }

    fun checkAllPrepItems() {
        _state.update { it.copy(checkedPrepItems = setOf(0, 1, 2, 3)) }
    }

    fun selectInputDevice(deviceId: Int) {
        _state.update { it.copy(selectedInputDeviceId = deviceId) }
    }

    fun refreshInputDevices() {
        deviceManager.refresh()
    }

    // ------------------------------------------------------------------ 5 点采集向导

    fun beginCaptureFlow() {
        val cur = _state.value
        if (cur.draftGuitarName.trim().isEmpty()) {
            showSnackbar("请先输入或选择吉他名称")
            return
        }
        stopCaptureInternal()
        processor = TapCaptureProcessor()
        _state.update {
            it.copy(
                page = AcousticPage.CAPTURE,
                currentStepIndex = 0,
                stepMeasurementStarted = false,
                capturedSpectra = emptyMap(),
                captureSnapshot = processor.snapshot(),
                captureError = null
            )
        }
        refreshPermission()
    }

    /** 用户阅毕当前点位的敲击动作说明后点击开始测量：收起说明卡片并立即做 1 秒底噪校准与拾音。 */
    fun startCurrentStepMeasurement() {
        refreshPermission()
        _state.update { it.copy(stepMeasurementStarted = true, captureError = null) }
        if (_state.value.permissionGranted) {
            startListeningCurrentStep(recalibrate = true)
        }
    }

    fun startListeningCurrentStep(recalibrate: Boolean = true) {
        if (!_state.value.permissionGranted) {
            _state.update { it.copy(captureError = "尚未授予麦克风权限") }
            return
        }
        stopCaptureInternal()
        if (recalibrate) {
            processor.startCalibration()
        } else {
            processor.recalibrateNoiseOnly()
        }
        _state.update {
            it.copy(
                captureSnapshot = processor.snapshot(),
                captureError = null
            )
        }

        val capture = TapAudioCapture(
            sampleRate = TapAudioCapture.DEFAULT_SAMPLE_RATE,
            onSamples = { samples, count ->
                val snap = processor.feed(samples, count)
                val now = System.currentTimeMillis()
                // 节流 UI 刷新（每 50 ms 或关键阶段/反馈变化立即推送）
                val importantChange = snap.phase != _state.value.captureSnapshot.phase ||
                    snap.acceptedTaps.size != _state.value.captureSnapshot.acceptedTaps.size ||
                    snap.lastFeedback?.timestampSeq != _state.value.captureSnapshot.lastFeedback?.timestampSeq
                if (importantChange || now - lastUiEmitMs >= 50L) {
                    lastUiEmitMs = now
                    _state.update { it.copy(captureSnapshot = snap) }
                }
            },
            onError = { msg ->
                _state.update { it.copy(isListening = false, captureError = msg) }
            }
        )
        tapCapture = capture
        val preferred = _state.value.selectedInputOption.deviceInfo
        val ok = capture.start(preferred)
        _state.update { it.copy(isListening = ok) }
    }

    fun toggleListeningPause() {
        if (_state.value.isListening) {
            stopCaptureInternal()
        } else {
            startListeningCurrentStep(recalibrate = false)
        }
    }

    fun resetCurrentStepTaps() {
        startListeningCurrentStep(recalibrate = true)
    }

    fun removeSingleTap(index: Int) {
        val snap = processor.removeTap(index)
        _state.update { it.copy(captureSnapshot = snap) }
        if (!_state.value.isListening && _state.value.permissionGranted) {
            startListeningCurrentStep(recalibrate = false)
        }
    }

    fun confirmStepAndProceed(allowQualityOverride: Boolean = false) {
        val cur = _state.value
        val snap = processor.snapshot()
        val pos = cur.currentTapPosition
        if (snap.acceptedTaps.size < snap.targetTapCount) {
            showSnackbar("还需采集 ${snap.targetTapCount - snap.acceptedTaps.size} 次有效敲击")
            return
        }
        if (!snap.coherencePassed && !allowQualityOverride) {
            showSnackbar("多击一致性未达 90%，请删除低分敲击补敲，或选择嘈杂环境确认继续")
            return
        }

        val built = processor.buildPositionSpectrum(
            position = pos,
            qualityOverride = !snap.coherencePassed && allowQualityOverride
        )
        val updatedSpectra = cur.capturedSpectra + (pos to built)
        stopCaptureInternal()

        val nextStep = cur.currentStepIndex + 1
        if (nextStep < TapPosition.ordered.size) {
            processor = TapCaptureProcessor()
            _state.update {
                it.copy(
                    capturedSpectra = updatedSpectra,
                    currentStepIndex = nextStep,
                    stepMeasurementStarted = false,
                    captureSnapshot = processor.snapshot(),
                    captureError = null
                )
            }
        } else {
            finalizeAndSaveRecord(updatedSpectra)
        }
    }

    fun skipOptionalDecoupleStep() {
        val cur = _state.value
        if (cur.currentTapPosition != TapPosition.DECOUPLE) return
        stopCaptureInternal()
        finalizeAndSaveRecord(cur.capturedSpectra)
    }

    fun goToPreviousStep() {
        val cur = _state.value
        if (cur.currentStepIndex <= 0) return
        stopCaptureInternal()
        processor = TapCaptureProcessor()
        _state.update {
            it.copy(
                currentStepIndex = cur.currentStepIndex - 1,
                stepMeasurementStarted = false,
                captureSnapshot = processor.snapshot(),
                captureError = null
            )
        }
    }

    private fun finalizeAndSaveRecord(spectra: Map<TapPosition, PositionSpectrum>) {
        val cur = _state.value
        viewModelScope.launch {
            val report = withContext(Dispatchers.Default) {
                AcousticAnalyzer.analyze(
                    spectra = spectra,
                    bodyShape = cur.draftBodyShape,
                    manualOverrides = emptyMap(),
                    a4Hz = cur.a4Hz,
                    useFlats = cur.useFlats
                )
            }
            val record = AcousticRecord(
                id = UUID.randomUUID().toString(),
                guitarName = cur.draftGuitarName.trim(),
                bodyShape = cur.draftBodyShape,
                notes = cur.draftNotes.trim(),
                inputDeviceName = cur.selectedInputOption.label,
                a4Hz = cur.a4Hz,
                createdAtEpochMs = System.currentTimeMillis(),
                spectra = spectra,
                manualPeakOverrides = emptyMap(),
                report = report
            )
            acousticRepo.saveRecord(record)
            _state.update {
                it.copy(
                    page = AcousticPage.DETAIL,
                    activeRecord = record,
                    detailTab = DetailTab.REPORT,
                    selectedSpectrumPosition = TapPosition.BRIDGE,
                    rawDataPosition = TapPosition.BRIDGE,
                    tuningModalTarget = null,
                    snackbarMessage = "声学体检报告已生成并归档至「${record.guitarName}」"
                )
            }
        }
    }

    private fun stopCaptureInternal() {
        tapCapture?.stop()
        tapCapture = null
        _state.update { it.copy(isListening = false) }
    }

    // ------------------------------------------------------------------ 历史档案管理与详情页

    fun openRecordDetail(id: String, initialTab: DetailTab = DetailTab.REPORT) {
        viewModelScope.launch {
            val loaded = acousticRepo.loadRecord(id)
            if (loaded == null) {
                showSnackbar("未找到该记录的完整数据")
                return@launch
            }
            val defaultPos = loaded.spectra.keys.minByOrNull { it.stepIndex } ?: TapPosition.BRIDGE
            _state.update {
                it.copy(
                    page = AcousticPage.DETAIL,
                    activeRecord = loaded,
                    detailTab = initialTab,
                    selectedSpectrumPosition = defaultPos,
                    rawDataPosition = defaultPos,
                    tuningModalTarget = null,
                    compareSelectMode = false,
                    selectedForCompareIds = emptyList()
                )
            }
        }
    }

    fun deleteRecord(id: String) {
        viewModelScope.launch {
            acousticRepo.deleteRecord(id)
            _state.update { cur ->
                val clearedDetail = cur.activeRecord?.id == id
                cur.copy(
                    activeRecord = if (clearedDetail) null else cur.activeRecord,
                    page = if (clearedDetail && cur.page == AcousticPage.DETAIL) AcousticPage.HOME else cur.page,
                    selectedForCompareIds = cur.selectedForCompareIds.filterNot { it == id },
                    snackbarMessage = "已删除该条声学记录"
                )
            }
        }
    }

    fun updateRecordMetadata(
        id: String,
        guitarName: String,
        bodyShape: GuitarBodyShape,
        notes: String
    ) {
        viewModelScope.launch {
            val cur = _state.value
            val updated = acousticRepo.updateMetadata(
                id = id,
                guitarName = guitarName,
                bodyShape = bodyShape,
                notes = notes,
                a4Hz = cur.a4Hz,
                useFlats = cur.useFlats
            )
            if (updated != null) {
                _state.update { st ->
                    st.copy(
                        activeRecord = if (st.activeRecord?.id == id) updated else st.activeRecord,
                        snackbarMessage = "乐器档案信息已更新"
                    )
                }
            }
        }
    }

    fun selectDetailTab(tab: DetailTab) {
        _state.update { it.copy(detailTab = tab, tuningModalTarget = null) }
    }

    fun selectSpectrumPosition(position: TapPosition) {
        _state.update { it.copy(selectedSpectrumPosition = position, tuningModalTarget = null) }
    }

    fun selectRawDataPosition(position: TapPosition) {
        _state.update { it.copy(rawDataPosition = position) }
    }

    fun updateChartOptions(transform: (SpectrumChartOptions) -> SpectrumChartOptions) {
        _state.update { it.copy(chartOptions = transform(it.chartOptions)) }
    }

    fun setTuningModalTarget(modalId: ModalId?) {
        _state.update { cur ->
            if (modalId == null) {
                cur.copy(tuningModalTarget = null)
            } else {
                val rec = cur.activeRecord
                val targetPos = if (rec != null && rec.spectra.containsKey(modalId.primaryPosition)) {
                    modalId.primaryPosition
                } else cur.selectedSpectrumPosition
                cur.copy(
                    tuningModalTarget = modalId,
                    selectedSpectrumPosition = targetPos
                )
            }
        }
    }

    fun applyManualPeakOverride(modalId: ModalId, frequencyHz: Double) {
        val rec = _state.value.activeRecord ?: return
        val newOverrides = rec.manualPeakOverrides + (modalId to frequencyHz)
        viewModelScope.launch {
            val cur = _state.value
            val updated = acousticRepo.updateManualPeakOverrides(
                id = rec.id,
                overrides = newOverrides,
                a4Hz = cur.a4Hz,
                useFlats = cur.useFlats
            )
            if (updated != null) {
                val snapped = updated.report.peaks[modalId]?.frequencyHz ?: frequencyHz
                _state.update {
                    it.copy(
                        activeRecord = updated,
                        tuningModalTarget = null,
                        snackbarMessage = "已微调 ${modalId.symbol} 至 ${String.format("%.1f", snapped)} Hz，报告已重算"
                    )
                }
            }
        }
    }

    fun resetManualPeakOverrides() {
        val rec = _state.value.activeRecord ?: return
        if (rec.manualPeakOverrides.isEmpty()) return
        viewModelScope.launch {
            val cur = _state.value
            val updated = acousticRepo.updateManualPeakOverrides(
                id = rec.id,
                overrides = emptyMap(),
                a4Hz = cur.a4Hz,
                useFlats = cur.useFlats
            )
            if (updated != null) {
                _state.update {
                    it.copy(
                        activeRecord = updated,
                        tuningModalTarget = null,
                        snackbarMessage = "已恢复全部共振峰自动识别结果"
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 双记录对比

    fun toggleCompareSelectMode() {
        _state.update { cur ->
            val next = !cur.compareSelectMode
            cur.copy(
                compareSelectMode = next,
                selectedForCompareIds = if (next) cur.selectedForCompareIds else emptyList()
            )
        }
    }

    fun toggleRecordForCompare(id: String) {
        _state.update { cur ->
            val list = cur.selectedForCompareIds.toMutableList()
            if (id in list) {
                list.remove(id)
            } else {
                if (list.size >= 2) list.removeAt(0)
                list.add(id)
            }
            cur.copy(selectedForCompareIds = list)
        }
    }

    fun openCompareWithRecords(idA: String, idB: String) {
        if (idA == idB) {
            showSnackbar("请选择两条不同的记录进行对比")
            return
        }
        viewModelScope.launch {
            val recA = acousticRepo.loadRecord(idA)
            val recB = acousticRepo.loadRecord(idB)
            if (recA == null || recB == null) {
                showSnackbar("无法读取所选对比记录")
                return@launch
            }
            val common = TapPosition.ordered.filter {
                recA.spectra.containsKey(it) && recB.spectra.containsKey(it)
            }
            val pos = common.firstOrNull() ?: TapPosition.BRIDGE
            _state.update {
                it.copy(
                    page = AcousticPage.COMPARE,
                    compareSelectMode = false,
                    selectedForCompareIds = emptyList(),
                    compareRecordA = recA,
                    compareRecordB = recB,
                    comparePosition = pos
                )
            }
        }
    }

    fun openCompareFromActiveDetail() {
        val active = _state.value.activeRecord ?: return
        val other = _state.value.summaries.firstOrNull { it.id != active.id }
        if (other == null) {
            showSnackbar("至少需要两条分析记录才能对比")
            return
        }
        openCompareWithRecords(active.id, other.id)
    }

    fun swapCompareRecord(isSlotA: Boolean, newId: String) {
        val cur = _state.value
        val otherId = if (isSlotA) cur.compareRecordB?.id else cur.compareRecordA?.id
        if (newId == otherId) {
            showSnackbar("两条对比记录不能相同")
            return
        }
        viewModelScope.launch {
            val loaded = acousticRepo.loadRecord(newId) ?: return@launch
            _state.update { st ->
                val a = if (isSlotA) loaded else st.compareRecordA
                val b = if (isSlotA) st.compareRecordB else loaded
                val common = if (a != null && b != null) {
                    TapPosition.ordered.filter { a.spectra.containsKey(it) && b.spectra.containsKey(it) }
                } else emptyList()
                val pos = if (st.comparePosition in common) st.comparePosition else (common.firstOrNull() ?: TapPosition.BRIDGE)
                st.copy(compareRecordA = a, compareRecordB = b, comparePosition = pos)
            }
        }
    }

    fun selectComparePosition(position: TapPosition) {
        _state.update { it.copy(comparePosition = position) }
    }

    fun toggleCompareStackOrder() {
        _state.update { it.copy(compareAOnTop = !it.compareAOnTop) }
    }

    fun setCompareColor(isRecordA: Boolean, argb: Long) {
        _state.update {
            if (isRecordA) it.copy(compareColorA = argb) else it.copy(compareColorB = argb)
        }
    }

    fun updateCompareOptions(transform: (SpectrumChartOptions) -> SpectrumChartOptions) {
        _state.update { it.copy(compareOptions = transform(it.compareOptions)) }
    }

    fun toggleCompareForceLandscape() {
        _state.update { it.copy(compareForceLandscape = !it.compareForceLandscape) }
    }

    // ------------------------------------------------------------------ 导出（PNG / MD / PDF / CSV / JSON）

    fun saveSingleSpectrumPng(alsoShare: Boolean = false) {
        val cur = _state.value
        val rec = cur.activeRecord ?: return
        val pos = cur.selectedSpectrumPosition
        if (!rec.spectra.containsKey(pos)) return

        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            val result = withContext(Dispatchers.IO) {
                val bmp = AcousticChartBitmapRenderer.renderSingleSpectrumBitmap(
                    record = rec,
                    position = pos,
                    options = cur.chartOptions,
                    lightTheme = false
                )
                try {
                    val stem = "${AcousticReportFormatter.safeFileStem(rec.guitarName, rec.createdAtEpochMs)}_${pos.code}"
                    AcousticFileExportHelper.saveBitmapToGallery(getApplication(), bmp, stem)
                } finally {
                    bmp.recycle()
                }
            }
            _state.update { it.copy(exporting = false) }
            result.onSuccess { saved ->
                _state.update {
                    it.copy(
                        lastSavedImageUri = saved.uri,
                        snackbarMessage = "频谱图已保存至 ${saved.displayLocation}"
                    )
                }
                if (alsoShare) {
                    AcousticFileExportHelper.shareUri(getApplication(), saved.uri, "image/png", "分享 FFT 频谱图")
                }
            }.onFailure { err ->
                showSnackbar("保存频谱图失败：${err.message ?: "未知错误"}")
            }
        }
    }

    fun saveComparisonPng(alsoShare: Boolean = false) {
        val cur = _state.value
        val recA = cur.compareRecordA ?: return
        val recB = cur.compareRecordB ?: return
        val pos = cur.comparePosition

        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            val result = withContext(Dispatchers.IO) {
                val bmp = AcousticChartBitmapRenderer.renderComparisonBitmap(
                    recordA = recA,
                    recordB = recB,
                    position = pos,
                    colorA = cur.compareColorA.toInt(),
                    colorB = cur.compareColorB.toInt(),
                    recordAOnTop = cur.compareAOnTop,
                    options = cur.compareOptions
                )
                try {
                    val stem = "Compare_${AcousticReportFormatter.safeFileStem(recA.guitarName, System.currentTimeMillis())}_${pos.code}"
                    AcousticFileExportHelper.saveBitmapToGallery(getApplication(), bmp, stem)
                } finally {
                    bmp.recycle()
                }
            }
            _state.update { it.copy(exporting = false) }
            result.onSuccess { saved ->
                _state.update {
                    it.copy(
                        lastSavedImageUri = saved.uri,
                        snackbarMessage = "对比频谱图已保存至 ${saved.displayLocation}"
                    )
                }
                if (alsoShare) {
                    AcousticFileExportHelper.shareUri(getApplication(), saved.uri, "image/png", "分享对比 FFT 频谱图")
                }
            }.onFailure { err ->
                showSnackbar("保存对比图失败：${err.message ?: "未知错误"}")
            }
        }
    }

    fun shareDocument(kind: ExportDocumentKind, onlyPosition: TapPosition? = null) {
        val rec = _state.value.activeRecord ?: return
        val fileName = buildExportFileName(rec, kind, onlyPosition)
        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            val uriResult = withContext(Dispatchers.IO) {
                AcousticFileExportHelper.writeTempExportUri(getApplication(), fileName) { out ->
                    writeExportPayload(rec, kind, onlyPosition, out)
                }
            }
            _state.update { it.copy(exporting = false) }
            uriResult.onSuccess { uri ->
                AcousticFileExportHelper.shareUri(getApplication(), uri, kind.mimeType, "分享${kind.label}")
            }.onFailure { err ->
                showSnackbar("导出失败：${err.message ?: "未知错误"}")
            }
        }
    }

    fun requestSaveDocument(kind: ExportDocumentKind, onlyPosition: TapPosition? = null) {
        val rec = _state.value.activeRecord ?: return
        val fileName = buildExportFileName(rec, kind, onlyPosition)
        _state.update {
            it.copy(pendingSaveRequest = PendingSaveRequest(kind, fileName, rec, onlyPosition))
        }
    }

    fun fulfillSaveDocument(targetUri: Uri?) {
        val req = _state.value.pendingSaveRequest
        _state.update { it.copy(pendingSaveRequest = null) }
        if (req == null || targetUri == null) return

        viewModelScope.launch {
            _state.update { it.copy(exporting = true) }
            val res = withContext(Dispatchers.IO) {
                AcousticFileExportHelper.writeToContentUri(getApplication(), targetUri) { out ->
                    writeExportPayload(req.record, req.kind, req.onlyPosition, out)
                }
            }
            _state.update { it.copy(exporting = false) }
            res.onSuccess {
                showSnackbar("${req.kind.label}已保存")
            }.onFailure { err ->
                showSnackbar("保存失败：${err.message ?: "未知错误"}")
            }
        }
    }

    private fun buildExportFileName(
        record: AcousticRecord,
        kind: ExportDocumentKind,
        onlyPosition: TapPosition?
    ): String {
        val stem = AcousticReportFormatter.safeFileStem(record.guitarName, record.createdAtEpochMs)
        val suffix = when (kind) {
            ExportDocumentKind.MARKDOWN -> "_声学报告"
            ExportDocumentKind.PDF -> "_声学报告"
            ExportDocumentKind.RAW_CSV -> if (onlyPosition != null) "_${onlyPosition.code}_FFT" else "_全部点位_FFT"
            ExportDocumentKind.RAW_JSON -> "_原始数据"
        }
        return "${stem}${suffix}.${kind.extension}"
    }

    private fun writeExportPayload(
        record: AcousticRecord,
        kind: ExportDocumentKind,
        onlyPosition: TapPosition?,
        out: java.io.OutputStream
    ) {
        when (kind) {
            ExportDocumentKind.MARKDOWN -> {
                out.write(AcousticReportFormatter.toMarkdown(record).toByteArray(Charsets.UTF_8))
            }

            ExportDocumentKind.PDF -> {
                AcousticPdfExporter.writePdf(record, out)
            }

            ExportDocumentKind.RAW_CSV -> {
                out.write(AcousticReportFormatter.toRawCsv(record, onlyPosition).toByteArray(Charsets.UTF_8))
            }

            ExportDocumentKind.RAW_JSON -> {
                out.write(acousticRepo.encodePrettyJson(record).toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbarMessage = null) }
    }

    private fun showSnackbar(message: String) {
        _state.update { it.copy(snackbarMessage = message) }
    }
}
