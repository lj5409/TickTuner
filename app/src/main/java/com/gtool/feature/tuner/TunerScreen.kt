package com.gtool.feature.tuner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gtool.core.music.Tuning
import com.gtool.ui.AppPosture
import com.gtool.ui.LocalWindowPosture
import com.gtool.ui.WindowPostureState
import com.gtool.ui.theme.Bg
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted

/**
 * 调音器主页面。签名固定为无参数，由 GToolApp 的导航壳直接调用。
 *
 * 根据 [LocalWindowPosture] 自动适配四种形态（与节拍器保持一致）：
 * - 竖屏（手机 / 折叠屏外屏）：顶部调弦栏 + 表盘 + 紧凑弦位列表；
 * - 横屏 / 帐篷模式：隐藏导航栏与设置，左满屏调音表 + 右两列满屏弦位（无需滑动）；
 * - 半折叠悬停模式（Tabletop）：隐藏导航栏，按物理折痕上半屏纯净大调音表，下半屏展示弦位与完整设置；
 * - 折叠屏完全展开模式（Unfolded）：左侧 NavigationRail + 左右双栏（左大调音表与快捷参数 + 右弦位与调弦快速切换，避让竖向折痕）。
 */
@Composable
fun TunerScreen() {
    val tunerViewModel: TunerViewModel = viewModel()
    val state by tunerViewModel.state.collectAsStateWithLifecycle()
    val postureState = LocalWindowPosture.current
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

    val requestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    val openSettings = { openAppSettings(context) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        when (postureState.posture) {
            AppPosture.LANDSCAPE_COMPACT -> LandscapeTunerLayout(
                state = state,
                hasPermission = hasPermission,
                onRequestPermission = requestPermission,
                onDismissError = tunerViewModel::clearError,
                onToggleLock = tunerViewModel::toggleStringLock,
                onPlayReference = tunerViewModel::playReference,
                onStopReference = tunerViewModel::stopReference
            )

            AppPosture.TABLETOP -> TabletopTunerLayout(
                postureState = postureState,
                state = state,
                hasPermission = hasPermission,
                onRequestPermission = requestPermission,
                onOpenSettings = openSettings,
                onDismissError = tunerViewModel::clearError,
                onA4Click = { showA4Dialog = true },
                onFlatsToggle = tunerViewModel::toggleFlats,
                onManageClick = { showManager = true },
                onSelectTuning = { tunerViewModel.selectTuning(it.id) },
                onToggleLock = tunerViewModel::toggleStringLock,
                onPlayReference = tunerViewModel::playReference,
                onStopReference = tunerViewModel::stopReference
            )

            AppPosture.UNFOLDED_EXPANDED -> UnfoldedDualPaneTunerLayout(
                postureState = postureState,
                state = state,
                hasPermission = hasPermission,
                onRequestPermission = requestPermission,
                onOpenSettings = openSettings,
                onDismissError = tunerViewModel::clearError,
                onA4Click = { showA4Dialog = true },
                onFlatsToggle = tunerViewModel::toggleFlats,
                onManageClick = { showManager = true },
                onSelectTuning = { tunerViewModel.selectTuning(it.id) },
                onToggleLock = tunerViewModel::toggleStringLock,
                onPlayReference = tunerViewModel::playReference,
                onStopReference = tunerViewModel::stopReference
            )

            AppPosture.PORTRAIT -> PortraitTunerLayout(
                state = state,
                hasPermission = hasPermission,
                onTuningClick = { showPicker = true },
                onA4Click = { showA4Dialog = true },
                onFlatsToggle = tunerViewModel::toggleFlats,
                onManageClick = { showManager = true },
                onRequestPermission = requestPermission,
                onOpenSettings = openSettings,
                onDismissError = tunerViewModel::clearError,
                onToggleLock = tunerViewModel::toggleStringLock,
                onPlayReference = tunerViewModel::playReference,
                onStopReference = tunerViewModel::stopReference
            )
        }
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
            onReorder = tunerViewModel::reorderTunings,
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

// ------------------------------------------------------------------ 1. 竖屏布局

@Composable
private fun PortraitTunerLayout(
    state: TunerUiState,
    hasPermission: Boolean,
    onTuningClick: () -> Unit,
    onA4Click: () -> Unit,
    onFlatsToggle: () -> Unit,
    onManageClick: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    onToggleLock: (Int) -> Unit,
    onPlayReference: (Int) -> Unit,
    onStopReference: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TunerHeader(
            state = state,
            onTuningClick = onTuningClick,
            onA4Click = onA4Click,
            onFlatsToggle = onFlatsToggle,
            onManageClick = onManageClick
        )

        if (!hasPermission) {
            PermissionRequestCard(
                onRequest = onRequestPermission,
                onOpenSettings = onOpenSettings
            )
        }

        state.errorMessage?.let { message ->
            ErrorBanner(message = message, onDismiss = onDismissError)
        }

        PitchPanel(
            state = state,
            hasPermission = hasPermission,
            onPlayTarget = onPlayReference,
            onStopReference = onStopReference
        )

        StringsSection(
            state = state,
            onToggleLock = onToggleLock,
            onPlayReference = onPlayReference,
            onStopReference = onStopReference
        )

        Text(
            text = "提示：轻点弦位可锁定目标弦；点弦号或 ▶ 循环播放参考音，点击 ■ 停止。" +
                "内置调弦不可修改，可在「调弦管理」里另存为自定义。",
            fontSize = 12.sp,
            color = TextMuted.copy(alpha = 0.85f),
            lineHeight = 18.sp
        )
    }
}

// ------------------------------------------------------------------ 2. 横屏 / 帐篷专注调音布局

@Composable
private fun LandscapeTunerLayout(
    state: TunerUiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onDismissError: () -> Unit,
    onToggleLock: (Int) -> Unit,
    onPlayReference: (Int) -> Unit,
    onStopReference: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：自适应大调音表，填满左侧高度
            PitchPanel(
                state = state,
                hasPermission = hasPermission,
                onPlayTarget = onPlayReference,
                onStopReference = onStopReference,
                fillAvailableHeight = true,
                showTuningNameInHint = true,
                onRequestPermission = onRequestPermission,
                modifier = Modifier
                    .weight(1.12f)
                    .fillMaxHeight()
            )

            // 右侧：两列满屏弦位网格（无需滑动）
            StringsTwoColumnGrid(
                state = state,
                onToggleLock = onToggleLock,
                onPlayReference = onPlayReference,
                onStopReference = onStopReference,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        // 错误提示浮层：不破坏全屏无滚动布局
        state.errorMessage?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            ) {
                ErrorBanner(message = message, onDismiss = onDismissError)
            }
        }
    }
}

// ------------------------------------------------------------------ 3. 折叠屏半折叠悬停模式（Tabletop）

@Composable
private fun TabletopTunerLayout(
    postureState: WindowPostureState,
    state: TunerUiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    onA4Click: () -> Unit,
    onFlatsToggle: () -> Unit,
    onManageClick: () -> Unit,
    onSelectTuning: (Tuning) -> Unit,
    onToggleLock: (Int) -> Unit,
    onPlayReference: (Int) -> Unit,
    onStopReference: () -> Unit
) {
    val density = LocalDensity.current
    var containerTopInWindowPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                containerTopInWindowPx = coords.positionInWindow().y
            }
    ) {
        val containerWidth = maxWidth
        val hinge = postureState.hingeBounds
        val topPaneHeight = if (hinge != null && hinge.isHorizontal && hinge.topPx > 0) {
            val localTopPx = (hinge.topPx - containerTopInWindowPx).coerceAtLeast(0f)
            with(density) { localTopPx.toDp() }.coerceIn(160.dp, (maxHeight - 180.dp).coerceAtLeast(160.dp))
        } else {
            maxHeight * 0.46f
        }
        val hingeGap = if (hinge != null && hinge.isHorizontal) {
            with(density) { (hinge.bottomPx - hinge.topPx).coerceAtLeast(0).toDp() }.coerceAtLeast(16.dp)
        } else {
            16.dp
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // 上半屏（折痕以上，立起面向演奏者）：纯净大号调音表
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topPaneHeight)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                PitchPanel(
                    state = state,
                    hasPermission = hasPermission,
                    onPlayTarget = onPlayReference,
                    onStopReference = onStopReference,
                    fillAvailableHeight = true,
                    showTuningNameInHint = true,
                    onRequestPermission = onRequestPermission,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 物理铰链安全避让区
            Spacer(Modifier.height(hingeGap))

            // 下半屏（折痕以下，平放桌面）：大折叠左右双栏（左两列弦位 + 右全部设置与调弦列表），窄屏单栏滚动
            if (containerWidth >= 560.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StringsTwoColumnGrid(
                        state = state,
                        onToggleLock = onToggleLock,
                        onPlayReference = onPlayReference,
                        onStopReference = onStopReference,
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                    )

                    Column(
                        modifier = Modifier
                            .weight(0.9f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!hasPermission) {
                            PermissionRequestCard(
                                onRequest = onRequestPermission,
                                onOpenSettings = onOpenSettings
                            )
                        }
                        state.errorMessage?.let { message ->
                            ErrorBanner(message = message, onDismiss = onDismissError)
                        }
                        TunerQuickParamsCard(
                            state = state,
                            onA4Click = onA4Click,
                            onFlatsToggle = onFlatsToggle,
                            onManageClick = onManageClick
                        )
                        QuickTuningSwitcherCard(
                            tunings = state.allTunings,
                            activeId = state.tuning.id,
                            useFlats = state.useFlats,
                            onSelect = onSelectTuning,
                            onManage = onManageClick
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!hasPermission) {
                        PermissionRequestCard(
                            onRequest = onRequestPermission,
                            onOpenSettings = onOpenSettings
                        )
                    }
                    state.errorMessage?.let { message ->
                        ErrorBanner(message = message, onDismiss = onDismissError)
                    }
                    StringsTwoColumnGrid(
                        state = state,
                        onToggleLock = onToggleLock,
                        onPlayReference = onPlayReference,
                        onStopReference = onStopReference,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(228.dp)
                    )
                    TunerQuickParamsCard(
                        state = state,
                        onA4Click = onA4Click,
                        onFlatsToggle = onFlatsToggle,
                        onManageClick = onManageClick
                    )
                    QuickTuningSwitcherCard(
                        tunings = state.allTunings,
                        activeId = state.tuning.id,
                        useFlats = state.useFlats,
                        onSelect = onSelectTuning,
                        onManage = onManageClick
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 4. 折叠屏完全展开双栏模式（Unfolded）

@Composable
private fun UnfoldedDualPaneTunerLayout(
    postureState: WindowPostureState,
    state: TunerUiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    onA4Click: () -> Unit,
    onFlatsToggle: () -> Unit,
    onManageClick: () -> Unit,
    onSelectTuning: (Tuning) -> Unit,
    onToggleLock: (Int) -> Unit,
    onPlayReference: (Int) -> Unit,
    onStopReference: () -> Unit
) {
    val density = LocalDensity.current
    var containerLeftInWindowPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                containerLeftInWindowPx = coords.positionInWindow().x
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        val hinge = postureState.hingeBounds
        val verticalHinge = hinge?.takeIf {
            postureState.isVerticalBookFold && !it.isHorizontal && it.leftPx > 0
        }
        val hingeGap = if (verticalHinge != null) {
            with(density) { (verticalHinge.rightPx - verticalHinge.leftPx).coerceAtLeast(0).toDp() }
                .coerceAtLeast(18.dp)
        } else {
            16.dp
        }
        val leftPaneFixedWidth = if (verticalHinge != null) {
            val localLeftPx = (verticalHinge.leftPx - containerLeftInWindowPx).coerceAtLeast(0f)
            with(density) { localLeftPx.toDp() }.coerceIn(260.dp, (maxWidth - 260.dp).coerceAtLeast(260.dp))
        } else {
            null
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(hingeGap)
        ) {
            // 左栏：大调音表主舞台 + 调音参数快捷卡片
            Column(
                modifier = (if (leftPaneFixedWidth != null) {
                    Modifier.width(leftPaneFixedWidth)
                } else {
                    Modifier.weight(1.08f)
                }).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!hasPermission) {
                    PermissionRequestCard(
                        onRequest = onRequestPermission,
                        onOpenSettings = onOpenSettings
                    )
                }

                state.errorMessage?.let { message ->
                    ErrorBanner(message = message, onDismiss = onDismissError)
                }

                PitchPanel(
                    state = state,
                    hasPermission = hasPermission,
                    onPlayTarget = onPlayReference,
                    onStopReference = onStopReference,
                    fillAvailableHeight = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                TunerQuickParamsCard(
                    state = state,
                    onA4Click = onA4Click,
                    onFlatsToggle = onFlatsToggle,
                    onManageClick = onManageClick
                )
            }

            // 右栏：独立滚动的弦位列表 + 快速切换调弦列表
            Column(
                modifier = Modifier
                    .weight(0.92f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StringsSection(
                    state = state,
                    onToggleLock = onToggleLock,
                    onPlayReference = onPlayReference,
                    onStopReference = onStopReference
                )

                QuickTuningSwitcherCard(
                    tunings = state.allTunings,
                    activeId = state.tuning.id,
                    useFlats = state.useFlats,
                    onSelect = onSelectTuning,
                    onManage = onManageClick
                )

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ 顶部调弦栏（竖屏使用）

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
