package com.gtool.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gtool.R
import com.gtool.feature.acoustic.AcousticScreen
import com.gtool.feature.chords.ChordsScreen
import com.gtool.feature.metronome.MetronomeScreen
import com.gtool.feature.tuner.TunerScreen
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.TextMuted

/** 允许无参子页面（如声学横屏全屏对比）请求暂时隐藏底部导航壳，不破坏 NavHost 单一槽位。 */
val LocalImmersiveChromeSetter = compositionLocalOf<(Boolean) -> Unit> { {} }

private data class Tab(val route: String, val label: Int, val icon: Int)

private val tabs = listOf(
    Tab("tuner", R.string.tab_tuner, R.drawable.ic_tuner),
    Tab("metronome", R.string.tab_metronome, R.drawable.ic_metronome),
    Tab("chords", R.string.tab_chords, R.drawable.ic_chords),
    Tab("acoustic", R.string.tab_acoustic, R.drawable.ic_acoustic)
)

private const val NAV_PREFS_NAME = "gtool_nav"
private const val KEY_LAST_TAB_ROUTE = "last_tab_route"
private const val DEFAULT_TAB_ROUTE = "tuner"

internal fun resolveSavedRoute(saved: String?): String =
    if (saved != null && tabs.any { it.route == saved }) saved else DEFAULT_TAB_ROUTE

@Composable
fun GToolApp() {
    val context = LocalContext.current.applicationContext
    val navPrefs = remember(context) {
        context.getSharedPreferences(NAV_PREFS_NAME, Context.MODE_PRIVATE)
    }
    // 同步读取上次离开时的页面作为冷启动首屏，零闪烁且避免误触发调音器麦克风权限。
    val startDestination = remember(navPrefs) {
        resolveSavedRoute(navPrefs.getString(KEY_LAST_TAB_ROUTE, null))
    }
    val navController = rememberNavController()
    ProvideWindowPosture {
        GToolAppShell(
            navController = navController,
            startDestination = startDestination,
            onPersistRoute = { route ->
                navPrefs.edit { putString(KEY_LAST_TAB_ROUTE, route) }
            }
        )
    }
}

@Composable
private fun GToolAppShell(
    navController: NavHostController,
    startDestination: String,
    onPersistRoute: (String) -> Unit
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val postureState = LocalWindowPosture.current
    var acousticImmersive by remember { mutableStateOf(false) }

    LaunchedEffect(currentRoute) {
        if (currentRoute != "acoustic") {
            acousticImmersive = false
        }
        if (currentRoute != null && tabs.any { it.route == currentRoute }) {
            onPersistRoute(currentRoute)
        }
    }

    val hideNavChrome = ((currentRoute == "metronome" || currentRoute == "tuner") && (
        postureState.posture == AppPosture.LANDSCAPE_COMPACT ||
            postureState.posture == AppPosture.TABLETOP
        )) || (currentRoute == "acoustic" && acousticImmersive)
    val useRail = !hideNavChrome && postureState.posture == AppPosture.UNFOLDED_EXPANDED

    val onSelectTab: (String, Boolean) -> Unit = { route, selected ->
        if (!selected) {
            onPersistRoute(route)
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Keep NavHost in a single structural slot across all posture changes so
    // NavBackStackEntry and ViewModels are never destroyed on rotation/fold.
    CompositionLocalProvider(LocalImmersiveChromeSetter provides { hide -> acousticImmersive = hide }) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = if (hideNavChrome || useRail) {
                WindowInsets.safeDrawing
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
            bottomBar = {
                if (!hideNavChrome && !useRail) {
                    GToolBottomBar(
                        currentRoute = currentRoute,
                        onSelectTab = onSelectTab
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    GToolNavigationRail(
                        currentRoute = currentRoute,
                        onSelectTab = onSelectTab
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = startDestination,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable("tuner") { TunerScreen() }
                        composable("metronome") { MetronomeScreen() }
                        composable("chords") { ChordsScreen() }
                        composable("acoustic") { AcousticScreen() }
                    }
                }
            }
        }
    }
}

@Composable
private fun GToolBottomBar(
    currentRoute: String?,
    onSelectTab: (route: String, selected: Boolean) -> Unit
) {
    NavigationBar(containerColor = Surface1, tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(tab.route, selected) },
                icon = {
                    Icon(
                        painter = painterResource(tab.icon),
                        contentDescription = stringResource(tab.label)
                    )
                },
                label = {
                    Text(
                        text = stringResource(tab.label),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}

@Composable
private fun GToolNavigationRail(
    currentRoute: String?,
    onSelectTab: (route: String, selected: Boolean) -> Unit
) {
    NavigationRail(
        containerColor = Surface1,
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        Spacer(Modifier.weight(1f))
        tabs.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(tab.route, selected) },
                icon = {
                    Icon(
                        painter = painterResource(tab.icon),
                        contentDescription = stringResource(tab.label)
                    )
                },
                label = {
                    Text(
                        text = stringResource(tab.label),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
        Spacer(Modifier.weight(1f))
    }
}
