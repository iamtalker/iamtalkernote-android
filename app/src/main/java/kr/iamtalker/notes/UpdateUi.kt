package kr.iamtalker.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 업데이트 진행 줄: 안내 문구와 진행 막대(받는 동안은 비율, 설치 준비 중에는 계속 도는 막대) */
@Composable
fun UpdateProgress(color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    if (Updater.status.isNotEmpty()) Text(Updater.status, fontSize = 12.sp, color = color, modifier = Modifier.padding(top = 2.dp))
    // 설치 확인·설치 중(3·4)에는 이 앱이 일을 다 마친 뒤라 busy 가 꺼져 있다: 그래도 막대는 계속 보여, 되고 있다는 것을 알린다
    if (Updater.busy || Updater.step in 2..4) {
        if (Updater.progress >= 0f) LinearProgressIndicator(progress = { Updater.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

/** 업데이트 창: 어느 화면에서든 단계와 진행 막대가 보이게 한다(받기 → 설치 준비 → 설치 확인 → 설치 후 재시작). 설치 자체는 안드로이드 설치 화면이 하고 그동안 이 앱은 교체되므로, 그 단계는 안내 글로 보인다 */
@Composable
fun UpdateDialog() {
    if (!Updater.dialog) return
    val steps = listOf(
        tr("새 버전 받기") to 1,
        tr("설치 준비") to 2,
        tr("설치 확인 — 안드로이드 화면에서 ‘설치’(또는 ‘업데이트’)를 누르세요") to 3,
        tr("설치 중 — 앱이 잠깐 꺼졌다가 새 버전으로 다시 켜집니다") to 4,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!Updater.busy) Updater.dialog = false },
        title = { Text(tr("앱 업데이트")) },
        text = {
            Column {
                for ((label, n) in steps) {
                    val mark = when { Updater.step > n || Updater.step == 5 -> "✓ "; Updater.step == n -> "▶ "; else -> "○ " }
                    Text(mark + label, fontSize = 14.sp, fontWeight = if (Updater.step == n) FontWeight.Medium else FontWeight.Normal,
                        color = if (Updater.step >= n) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 3.dp))
                }
                if (Updater.busy || Updater.step in 1..4) {   // 설치 확인·설치 중에도 막대를 이어 보인다(진행률을 알 수 없는 단계는 도는 막대)
                    if (Updater.progress >= 0f) LinearProgressIndicator(progress = { Updater.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                if (Updater.status.isNotEmpty()) Text(Updater.status, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { Updater.dialog = false }, enabled = !Updater.busy) { Text(if (Updater.step == 3) tr("숨기기") else tr("닫기")) } },
    )
}

/** 새 버전이 있을 때 노트 목록 맨 위에 뜨는 띠: 누르면 받아서 설치 화면까지 연다 */
@Composable
fun UpdateBanner() {
    val info = Updater.available ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().clickable(enabled = !Updater.busy) { scope.launch { Updater.updateNow(ctx) } }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(trf("새 버전 %s 이 있습니다 — 눌러서 업데이트", info.version), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            UpdateProgress()
        }
    }
}
