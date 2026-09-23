package com.water.widget.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.RefreshState
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState

private val RefreshCircleSize = 22.dp
private const val FullStretchHapticProgress = 0.99f

@Composable
internal fun PullRefreshOffsetContent(
    pullOffset: Dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth().offset(y = pullOffset),
        content = content
    )
}

/**
 * 手势、阻尼、回弹和指示器由 miuix 0.9.3 PullToRefresh 处理，本组件负责页面位移与状态衔接。
 */
@Composable
fun WaterPullRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorTopOffset: Dp = 0.dp,
    content: @Composable BoxScope.(Dp) -> Unit
) {
    val state = rememberPullToRefreshState()
    val refreshCompleteProgress = remember { Animatable(1f) }
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val visualThresholdPx = windowHeightPx / 24f
    val fullDragRangePx = windowHeightPx / 3f
    val stretchExtra = with(density) {
        (state.dragOffset - visualThresholdPx).coerceAtLeast(0f).toDp()
    }
    val headerHeight = when (state.refreshState) {
        RefreshState.Idle -> 0.dp
        RefreshState.Pulling -> (RefreshCircleSize + 36.dp) * state.pullProgress
        RefreshState.ThresholdReached -> RefreshCircleSize + 36.dp + stretchExtra
        RefreshState.Refreshing -> (RefreshCircleSize + 36.dp) * state.pullProgress
        RefreshState.RefreshComplete ->
            (RefreshCircleSize + 36.dp) *
                (1f - refreshCompleteProgress.value)
    }
    val hapticFeedback = LocalHapticFeedback.current
    val fullyStretched = state.refreshState == RefreshState.ThresholdReached &&
        fullDragRangePx > 0f && state.dragOffset / fullDragRangePx >= FullStretchHapticProgress

    LaunchedEffect(fullyStretched) {
        if (fullyStretched) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.refreshState }.collectLatest { refreshState ->
            if (refreshState == RefreshState.RefreshComplete) {
                refreshCompleteProgress.snapTo(1f - state.pullProgress)
                refreshCompleteProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = CubicBezierEasing(0f, 0f, 0f, 0.37f)
                    )
                )
            } else {
                refreshCompleteProgress.snapTo(1f)
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val viewportHeightPx = constraints.maxHeight
        PullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            pullToRefreshState = state,
            contentPadding = PaddingValues(top = indicatorTopOffset),
            color = WaterThemeTokens.colors.waterStrong,
            circleSize = RefreshCircleSize,
            refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新完成"),
            refreshTextStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        ) {
            val headerHeightPx = with(density) { headerHeight.roundToPx() }
            Layout(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content(headerHeight)
                    }
                }
            ) { measurables, constraints ->
                val contentPlaceable = measurables.single().measure(
                    Constraints.fixed(
                        width = constraints.maxWidth,
                        height = viewportHeightPx
                    )
                )
                layout(
                    width = constraints.maxWidth,
                    height = constraints.maxHeight
                ) {
                    contentPlaceable.placeRelative(0, -headerHeightPx)
                }
            }
        }
    }
}
