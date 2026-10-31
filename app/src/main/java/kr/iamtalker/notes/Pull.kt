package kr.iamtalker.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/** 목록을 맨 위에서 아래로 끌어당기면 폴더를 다시 읽어 새로고침한다(동기화로 들어온 노트가 바로 보이게) */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PullRefresh(model: NotesModel, content: @Composable () -> Unit) {
    var refreshing by remember { mutableStateOf(false) }
    val state = rememberPullRefreshState(refreshing, { refreshing = true })
    if (refreshing) {
        LaunchedEffect(true) {
            model.refresh()
            delay(600)
            while (model.loading) delay(200)
            refreshing = false
        }
    }
    Box(Modifier.fillMaxSize().pullRefresh(state)) {
        content()
        PullRefreshIndicator(refreshing, state, Modifier.align(Alignment.TopCenter))
    }
}
