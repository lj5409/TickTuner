package com.gtool.feature.tuner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gtool.core.music.Tuning
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted

/**
 * 调音器主页面。签名固定为无参数，由 GToolApp 的底部导航直接调用。
 */
@Composable
fun TunerScreen() {
    val tunerViewModel: TunerViewModel = viewModel()
    val state by tunerViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(tunerViewModel.hasRecordPermission()) }

    var showPicker by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }
    var showA4Dialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingTuning by remember { mutableStateOf<Tuning?>(null) }
    var deletingTuning by remember { mutableStateOf<Tuning?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        tunerViewModel.onPermissionResult(granted)
    }

    // 页面不可见（切 Tab / 退后台）时停止录音；销毁时释放 AudioRecord 与 AudioTrack。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val granted = tunerViewModel.hasRecordPermission()
                    hasPermission = granted
                    tunerViewModel.onPermissionResult(granted)
                }

                Lifecycle.Event.ON_PAUSE -> tunerViewModel.stopListening()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val granted = tunerViewModel.hasRecordPermission()
            hasPermission = granted
            if (granted) tunerViewModel.startListening()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tunerViewModel.releaseAudio()
        }
    }

    // 首次进入：已授权则直接开始，否则自动请求一次麦克风权限。
    LaunchedEffect(Unit) {
        val granted = tunerViewModel.hasRecordPermission()
        hasPermission = granted
        if (granted) {
            tunerViewModel.startListening()
        } else if (!tunerViewModel.wasPermissionRequested) {
            tunerViewModel.markPermissionRequested()
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TunerHeader(
            state = state,
            onTuningClick = { showPicker = true },
            onA4Click = { showA4Dialog = true },
            onFlatsToggle = tunerViewModel::toggleFlats,
            onManageClick = { showManager = true }
        )

        if (!hasPermission) {
            PermissionRequestCard(
                onRequest = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onOpenSettings = { openAppSettings(context) }
            )
        }

        state.errorMessage?.let { message ->
            ErrorBanner(message = message, onDismiss = tunerViewModel::clearError)
        }

        PitchPanel(
            state = state,
            hasPermission = hasPermission,
            onPlayTarget = tunerViewModel::playReference
        )

        StringsSection(
            state = state,
            onToggleLock = tunerViewModel::toggleStringLock,
            onPlayReference = tunerViewModel::playReference
        )

        Text(
            text = "提示：轻点弦位可锁定目标弦；点弦号或 ▶ 播放 2 秒参考音。" +
                "内置调弦不可修改，可在「调弦管理」里另存为自定义。",
            fontSize = 12.sp,
            color = TextMuted.copy(alpha = 0.85f),
            lineHeight = 18.sp
        )
    }

    if (showPicker) {
        TuningPickerDialog(
            tunings = state.allTunings,
            activeId = state.tuning.id,
            useFlats = state.useFlats,
            onSelect = { tuning ->
                tunerViewModel.selectTuning(tuning.id)
                showPicker = false
            },
            onManage = {
                showPicker = false
                showManager = true
            },
            onDismiss = { showPicker = false }
        )
    }

    if (showManager) {
        TuningManagerDialog(
            tunings = state.allTunings,
            activeId = state.tuning.id,
            useFlats = state.useFlats,
            activeIsBuiltIn = state.tuning.builtIn,
            onSelect = { tuning -> tunerViewModel.selectTuning(tuning.id) },
            onCreate = {
                showManager = false
                showCreateDialog = true
            },
            onEdit = { tuning ->
                showManager = false
                editingTuning = tuning
            },
            onDelete = { tuning -> deletingTuning = tuning },
            onDismiss = { showManager = false }
        )
    }

    if (showCreateDialog) {
        CreateTuningDialog(
            base = state.tuning,
            useFlats = state.useFlats,
            onConfirm = { name ->
                tunerViewModel.createCustomTuning(name)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    editingTuning?.let { tuning ->
        TuningEditorDialog(
            tuning = tuning,
            useFlats = state.useFlats,
            onSave = { updated ->
                tunerViewModel.updateCustomTuning(updated)
                editingTuning = null
            },
            onDismiss = { editingTuning = null }
        )
    }

    deletingTuning?.let { tuning ->
        DeleteTuningDialog(
            tuning = tuning,
            onConfirm = {
                tunerViewModel.deleteCustomTuning(tuning)
                deletingTuning = null
            },
            onDismiss = { deletingTuning = null }
        )
    }

    if (showA4Dialog) {
        A4Dialog(
            a4 = state.a4,
            onConfirm = { value ->
                tunerViewModel.setA4(value)
                showA4Dialog = false
            },
            onDismiss = { showA4Dialog = false }
        )
    }
}

@Composable
private fun TunerHeader(
    state: TunerUiState,
    onTuningClick: () -> Unit,
    onA4Click: () -> Unit,
    onFlatsToggle: () -> Unit,
    onManageClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.tuning.name,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain
                )
                Text(
                    text = state.tuning.joinedNames(state.useFlats),
                    fontSize = 13.sp,
                    color = TextMuted
                )
            }
            OutlinedButton(onClick = onTuningClick) {
                Text("切换")
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TunerChip(
                text = "A4 ${formatOneDecimal(state.a4)} Hz",
                onClick = onA4Click
            )
            TunerChip(
                text = if (state.useFlats) "♭ 降号" else "♯ 升号",
                onClick = onFlatsToggle
            )
            TunerChip(
                text = "调弦管理",
                onClick = onManageClick
            )
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
