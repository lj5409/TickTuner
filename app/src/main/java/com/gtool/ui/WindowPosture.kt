package com.gtool.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.CancellationException

enum class AppPosture {
    PORTRAIT,
    LANDSCAPE_COMPACT,
    UNFOLDED_EXPANDED,
    TABLETOP
}

data class FoldHingeBounds(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
    val isHorizontal: Boolean
)

data class WindowPostureState(
    val posture: AppPosture = AppPosture.PORTRAIT,
    val hingeBounds: FoldHingeBounds? = null,
    val isVerticalBookFold: Boolean = false
)

fun classifyPosture(
    widthDp: Float,
    heightDp: Float,
    isHalfOpened: Boolean,
    isHorizontalFold: Boolean
): AppPosture = when {
    isHalfOpened && isHorizontalFold -> AppPosture.TABLETOP
    widthDp >= 600f && heightDp >= 550f -> AppPosture.UNFOLDED_EXPANDED
    widthDp > heightDp && heightDp < 550f -> AppPosture.LANDSCAPE_COMPACT
    else -> AppPosture.PORTRAIT
}

val LocalWindowPosture = staticCompositionLocalOf { WindowPostureState() }

@Composable
fun ProvideWindowPosture(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val foldingFeature by produceState<FoldingFeature?>(initialValue = null, key1 = activity) {
        if (activity == null) {
            value = null
            return@produceState
        }
        runCatching {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { layoutInfo ->
                    value = layoutInfo.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull()
                }
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            value = null
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        val feature = foldingFeature
        val isHalfOpened = feature?.state == FoldingFeature.State.HALF_OPENED
        val isHorizontalFold = feature?.orientation == FoldingFeature.Orientation.HORIZONTAL

        val posture = classifyPosture(
            widthDp = widthDp,
            heightDp = heightDp,
            isHalfOpened = isHalfOpened,
            isHorizontalFold = isHorizontalFold
        )

        val activeFold = feature?.takeIf {
            it.isSeparating || it.state == FoldingFeature.State.HALF_OPENED
        }
        val hingeBounds = activeFold?.let { fold ->
            val bounds = fold.bounds
            FoldHingeBounds(
                leftPx = bounds.left,
                topPx = bounds.top,
                rightPx = bounds.right,
                bottomPx = bounds.bottom,
                isHorizontal = fold.orientation == FoldingFeature.Orientation.HORIZONTAL
            )
        }
        val isVerticalBookFold = activeFold?.orientation == FoldingFeature.Orientation.VERTICAL

        val postureState = remember(posture, hingeBounds, isVerticalBookFold) {
            WindowPostureState(
                posture = posture,
                hingeBounds = hingeBounds,
                isVerticalBookFold = isVerticalBookFold
            )
        }

        CompositionLocalProvider(LocalWindowPosture provides postureState) {
            content()
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
    return current as? Activity
}
