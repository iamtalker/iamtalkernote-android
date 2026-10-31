package kr.iamtalker.notes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 긴 목록의 스크롤 막대. 스크롤 중에 오른쪽에 나타나고, 오른쪽 가장자리를 잡고 끌면 빠르게 이동한다 */
fun Modifier.listScrollbar(state: LazyListState, color: Color): Modifier = composed {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (state.isScrollInProgress || dragging) 0.85f else 0f, tween(if (dragging || state.isScrollInProgress) 80 else 700), label = "sb")
    this
        .pointerInput(state) {
            val zone = 40.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.x < size.width - zone) return@awaitEachGesture
                down.consume()
                dragging = true
                drag(down.id) { ch ->
                    ch.consume()
                    val total = state.layoutInfo.totalItemsCount
                    if (total > 0) {
                        val f = (ch.position.y / size.height).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((f * (total - 1)).toInt()) }
                    }
                }
                dragging = false
            }
        }
        .drawWithContent {
            drawContent()
            val info = state.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo.size
            if (alpha <= 0f || total <= visible || total == 0) return@drawWithContent
            val h = size.height
            val thumb = (h * visible / total).coerceAtLeast(40.dp.toPx()).coerceAtMost(h)
            val span = (total - visible).coerceAtLeast(1)
            val top = (h - thumb) * (state.firstVisibleItemIndex.toFloat() / span).coerceIn(0f, 1f)
            val w = 5.dp.toPx()
            drawRoundRect(color.copy(alpha = alpha), Offset(size.width - w - 3.dp.toPx(), top), Size(w, thumb), CornerRadius(w / 2))
        }
}
