package com.gtool.feature.tuner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
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

/**
 * 调音器主页面。签名固定为无参数，由 GToolApp 的导航壳直接调用。
 *
 * 四态均为「一页满屏、不翻页、不滑动」，由两块核心视觉组成：
 * - [CentsTrailIndicator]：横轴音分指示器（上方音高 / 频率 / 音分，指针向下走出渐隐轨迹）；
 * - [GuitarHeadstock]：3+3 钢弦琴头，弦钮旁标音高，点击锁定；琴头 Logo 为调弦名，点击切换调弦。
 * 锁定某根弦后，屏幕右下角出现参考音播放 / 停止按钮。
 *
 * - 竖屏：参数行 + 上指示器 + 下琴头；
 * - 横屏 / 帐篷：隐藏导航栏，左指示器 + 右琴头；
 * - 半折叠悬停（Tabletop）：上半屏指示器，下半屏琴头 + 参数；
 * - 完全展开（Unfolded）：左指示器 + 右参数与琴头（避让竖向折痕）。
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

    val actions = TunerActions(
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onOpenSettings = { openAppSettings(context) },
        onDismissError = tunerViewModel::clearError,
        onTuningClick = { showPicker = true },
        onA4Click = { showA4Dialog = true },
        onFlatsToggle = tunerViewModel::toggleFlats,
        onManageClick = { showManager = true },
        onToggleLock = tunerViewModel::toggleStringLock
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        when (postureState.posture) {
            AppPosture.LANDSCAPE_COMPACT -> LandscapeTunerLayout(state, hasPermission, actions)
            AppPosture.TABLETOP -> TabletopTunerLayout(postureState, state, hasPermission, actions)
            AppPosture.UNFOLDED_EXPANDED -> UnfoldedDualPaneTunerLayout(postureState, state, hasPermission, actions)
            AppPosture.PORTRAIT -> PortraitTunerLayout(state, hasPermission, actions)
        }

        // 锁定弦后：右下角参考音播放 / 停止
        val locked = state.lockedIndex
        val lockedNote = locked?.let { state.tuning.strings.getOrNull(it) }
        if (locked != null && lockedNote != null) {
            val playing = state.playingIndex == locked
            ReferenceToneButton(
                playing = playing,
                noteName = lockedNote.name(state.useFlats),
                onClick = {
                    if (playing) tunerViewModel.stopReference() else tunerViewModel.playReference(locked)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
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

/** 四态布局共用的交互回调。 */
private class TunerActions(
    val onRequestPermission: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onDismissError: () -> Unit,
    val onTuningClick: () -> Unit,
    val onA4Click: () -> Unit,
    val onFlatsToggle: () -> Unit,
    val onManageClick: () -> Unit,
    val onToggleLock: (Int) -> Unit
)

@Composable
private fun Indicator(
    state: TunerUiState,
    hasPermission: Boolean,
    actions: TunerActions,
    modifier: Modifier
) {
    CentsTrailIndicator(
        state = state,
        hasPermission = hasPermission,
        onRequestPermission = actions.onRequestPermission,
        onOpenSettings = actions.onOpenSettings,
        modifier = modifier
    )
}

@Composable
private fun Headstock(
    state: TunerUiState,
    actions: TunerActions,
    modifier: Modifier,
    showTuningBadge: Boolean = false
) {
    GuitarHeadstock(
        state = state,
        onToggleLock = actions.onToggleLock,
        onTuningClick = actions.onTuningClick,
        showTuningBadge = showTuningBadge,
        modifier = modifier
    )
}

@Composable
private fun SettingsRow(state: TunerUiState, actions: TunerActions, modifier: Modifier = Modifier) {
    TunerSettingsRow(
        state = state,
        onA4Click = actions.onA4Click,
        onFlatsToggle = actions.onFlatsToggle,
        onManageClick = actions.onManageClick,
        modifier = modifier
    )
}

// ------------------------------------------------------------------ 1. 竖屏布局

@Composable
private fun PortraitTunerLayout(
    state: TunerUiState,
    hasPermission: Boolean,
    actions: TunerActions
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsRow(state, actions)

        state.errorMessage?.let { message ->
            ErrorBanner(message = message, onDismiss = actions.onDismissError)
        }

        Indicator(
            state = state,
            hasPermission = hasPermission,
            actions = actions,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.05f)
        )

        Headstock(
            state = state,
            actions = actions,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

// ------------------------------------------------------------------ 2. 横屏 / 帐篷专注调音布局

@Composable
private fun LandscapeTunerLayout(
    state: TunerUiState,
    hasPermission: Boolean,
    actions: TunerActions
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Indicator(
                state = state,
                hasPermission = hasPermission,
                actions = actions,
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight()
            )
            Headstock(
                state = state,
                actions = actions,
                showTuningBadge = true,
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
                ErrorBanner(message = message, onDismiss = actions.onDismissError)
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
    actions: TunerActions
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
            // 上半屏（折痕以上，立起面向演奏者）：横轴指示器
            Indicator(
                state = state,
                hasPermission = hasPermission,
                actions = actions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topPaneHeight)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )

            // 物理铰链安全避让区
            Spacer(Modifier.height(hingeGap))

            // 下半屏（折痕以下，平放桌面）：琴头 + 参数，满屏不滚动
            if (containerWidth >= 560.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Headstock(
                        state = state,
                        actions = actions,
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                    )
                    Column(
                        modifier = Modifier
                            .weight(0.8f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SettingsRow(state, actions)
                        state.errorMessage?.let { message ->
                            ErrorBanner(message = message, onDismiss = actions.onDismissError)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsRow(state, actions)
                    state.errorMessage?.let { message ->
                        ErrorBanner(message = message, onDismiss = actions.onDismissError)
                    }
                    Headstock(
                        state = state,
                        actions = actions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
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
    actions: TunerActions
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
            // 左栏：横轴指示器主舞台
            Indicator(
                state = state,
                hasPermission = hasPermission,
                actions = actions,
                modifier = (if (leftPaneFixedWidth != null) {
                    Modifier.width(leftPaneFixedWidth)
                } else {
                    Modifier.weight(1.05f)
                }).fillMaxHeight()
            )

            // 右栏：参数行 + 琴头
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsRow(state, actions)
                state.errorMessage?.let { message ->
                    ErrorBanner(message = message, onDismiss = actions.onDismissError)
                }
                Headstock(
                    state = state,
                    actions = actions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
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
