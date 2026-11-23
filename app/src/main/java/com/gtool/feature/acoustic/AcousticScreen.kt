package com.gtool.feature.acoustic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gtool.ui.AppPosture
import com.gtool.ui.LocalImmersiveChromeSetter
import com.gtool.ui.LocalWindowPosture
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.TextMain

/**
 * 第 4 个顶层 Tab「声学」无参入口（严格保持无参签名与 NavHost 单一槽位约束）。
 */
@Composable
fun AcousticScreen() {
    val vm: AcousticViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val posture = LocalWindowPosture.current.posture
    val setImmersiveChrome = LocalImmersiveChromeSetter.current
    val snackbarHostState = remember { SnackbarHostState() }

    // 生命周期联动：切到后台或切离 Tab 时释放麦克风
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> vm.onPause()
                Lifecycle.Event.ON_RESUME -> vm.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.onPause()
        }
    }

    // 横屏全屏沉浸隐藏底部导航栏：
    // 1) 声学报告详情页横屏 -> 全屏专注只展示选中频谱图
    // 2) 双记录对比页横屏 / 强制横屏
    val inDetailLandscapeFocus = state.page == AcousticPage.DETAIL &&
        posture == AppPosture.LANDSCAPE_COMPACT
    val inCompareLandscape = state.page == AcousticPage.COMPARE &&
        (state.compareForceLandscape || posture == AppPosture.LANDSCAPE_COMPACT)
    LaunchedEffect(inDetailLandscapeFocus, inCompareLandscape) {
        setImmersiveChrome(inDetailLandscapeFocus || inCompareLandscape)
    }
    LaunchedEffect(state.page, state.compareForceLandscape) {
        if (activity != null) {
            activity.requestedOrientation = if (state.page == AcousticPage.COMPARE && state.compareForceLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            setImmersiveChrome(false)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 系统返回键优先处理声学模块内部子页回退
    BackHandler(enabled = state.page != AcousticPage.HOME || state.compareSelectMode) {
        vm.navigateBack()
    }

    // 麦克风权限启动器
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        vm.onPermissionResult(granted)
    }

    // SAF 另存为启动器（按文件种类注入精准 MIME Type 与默认文件名，避免 OEM 丢失扩展名）
    val pendingSave = state.pendingSaveRequest
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = remember { CreateTypedDocumentContract() }
    ) { uri ->
        vm.fulfillSaveDocument(uri)
    }
    LaunchedEffect(pendingSave) {
        if (pendingSave != null) {
            saveDocumentLauncher.launch(pendingSave)
        }
    }

    // Snackbar 提示
    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            vm.consumeSnackbar()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (state.page) {
            AcousticPage.HOME -> AcousticHomeView(
                state = state,
                onStartNew = { prefill -> vm.openNewAnalysisPrep(prefill) },
                onOpenRecord = { id -> vm.openRecordDetail(id) },
                onDeleteRecord = { id -> vm.deleteRecord(id) },
                onToggleCompareMode = { vm.toggleCompareSelectMode() },
                onToggleSelectCompare = { id -> vm.toggleRecordForCompare(id) },
                onLaunchCompare = { a, b -> vm.openCompareWithRecords(a, b) }
            )

            AcousticPage.PREP -> AcousticPrepView(
                state = state,
                onBack = { vm.navigateBack() },
                onGuitarNameChange = { vm.updateDraftGuitarName(it) },
                onBodyShapeChange = { vm.updateDraftBodyShape(it) },
                onNotesChange = { vm.updateDraftNotes(it) },
                onSelectInputDevice = { vm.selectInputDevice(it) },
                onRefreshDevices = { vm.refreshInputDevices() },
                onTogglePrepItem = { vm.togglePrepItem(it) },
                onCheckAllPrep = { vm.checkAllPrepItems() },
                onBeginCapture = {
                    vm.beginCaptureFlow()
                    if (!state.permissionGranted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )

            AcousticPage.CAPTURE -> AcousticCaptureView(
                state = state,
                onBackToPrep = { vm.navigateBack() },
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onStartMeasurement = {
                    vm.startCurrentStepMeasurement()
                    if (!state.permissionGranted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onTogglePause = { vm.toggleListeningPause() },
                onResetStep = { vm.resetCurrentStepTaps() },
                onRemoveTap = { idx -> vm.removeSingleTap(idx) },
                onPrevStep = { vm.goToPreviousStep() },
                onConfirmNext = { override -> vm.confirmStepAndProceed(override) },
                onSkipOptionalStep = { vm.skipOptionalDecoupleStep() }
            )

            AcousticPage.DETAIL -> AcousticReportView(
                state = state,
                onBack = { vm.navigateBack() },
                onSelectTab = { vm.selectDetailTab(it) },
                onSelectSpectrumPosition = { vm.selectSpectrumPosition(it) },
                onSelectRawDataPosition = { vm.selectRawDataPosition(it) },
                onUpdateChartOptions = { vm.updateChartOptions(it) },
                onSetTuningModal = { vm.setTuningModalTarget(it) },
                onApplyManualPeak = { mid, hz -> vm.applyManualPeakOverride(mid, hz) },
                onResetManualPeaks = { vm.resetManualPeakOverrides() },
                onSaveSpectrumPng = { share -> vm.saveSingleSpectrumPng(share) },
                onShareDoc = { kind, pos -> vm.shareDocument(kind, pos) },
                onSaveDoc = { kind, pos -> vm.requestSaveDocument(kind, pos) },
                onOpenCompare = { vm.openCompareFromActiveDetail() },
                onUpdateMetadata = { name, shape, notes ->
                    state.activeRecord?.let { rec ->
                        vm.updateRecordMetadata(rec.id, name, shape, notes)
                    }
                }
            )

            AcousticPage.COMPARE -> AcousticCompareView(
                state = state,
                onBack = { vm.navigateBack() },
                onSelectPosition = { vm.selectComparePosition(it) },
                onToggleStackOrder = { vm.toggleCompareStackOrder() },
                onSelectColor = { isA, argb -> vm.setCompareColor(isA, argb) },
                onUpdateOptions = { vm.updateCompareOptions(it) },
                onToggleForceLandscape = { vm.toggleCompareForceLandscape() },
                onSwapRecord = { isSlotA, newId -> vm.swapCompareRecord(isSlotA, newId) },
                onSaveComparePng = { share -> vm.saveComparisonPng(share) }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = Surface2,
                contentColor = TextMain,
                actionColor = Accent
            )
        }
    }
}

private class CreateTypedDocumentContract : ActivityResultContract<PendingSaveRequest, Uri?>() {
    override fun createIntent(context: Context, input: PendingSaveRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.kind.mimeType
            putExtra(Intent.EXTRA_TITLE, input.suggestedFileName)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private fun Context.findActivity(): Activity? {
    var cur: Context? = this
    while (cur is ContextWrapper) {
        if (cur is Activity) return cur
        val next = cur.baseContext
        if (next === cur) return null
        cur = next
    }
    return cur as? Activity
}
