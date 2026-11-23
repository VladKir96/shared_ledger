package dev.sharedledger.presentation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sharedledger.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverviewReset(
    list: LazyListState, enabled: Boolean, reset: () -> Unit, content: @Composable () -> Unit
) {
    val pull = rememberPullToRefreshState()
    var startedAtTop by remember(list) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()
        .pointerInput(list) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                startedAtTop = !list.canScrollBackward
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            }
        }
        .pullToRefresh(
            isRefreshing = false,
            state = pull,
            enabled = enabled && startedAtTop,
            onRefresh = reset
        )
    ) {
        content()
        if (enabled && pull.distanceFraction > .08f) {
            val ready = pull.distanceFraction >= 1f
            Surface(Modifier.align(Alignment.TopCenter)
                .offset(y = (pull.distanceFraction.coerceAtMost(1.25f) * 30).dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
                shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 4.dp,
                shadowElevation = 3.dp
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp))
                    Text(stringResource(if (ready) R.string.overview_release else R.string.overview_pull),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
