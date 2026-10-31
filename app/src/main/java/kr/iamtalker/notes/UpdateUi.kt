package kr.iamtalker.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/** 새 버전이 있을 때 노트 목록 맨 위에 뜨는 띠: 누르면 받아서 설치 화면까지 연다 */
@Composable
fun UpdateBanner() {
    val info = Updater.available ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().clickable(enabled = !Updater.busy) { scope.launch { Updater.updateNow(ctx) } }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("새 버전 ${info.version} 이 있습니다 — 눌러서 업데이트", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (Updater.status.isNotEmpty()) Text(Updater.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
