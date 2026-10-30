package kr.iamtalker.notes

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun Step(n: Int, title: String, done: Boolean, content: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (done) accent else MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) {
            Text("$n", color = if (done) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))

/** 설정 → 동기화 맨 위: Syncthing-Fork 를 이 앱이 대신 설정하는 마법사 */
@Composable
fun SyncWizard(model: NotesModel) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var forkOn by remember { mutableStateOf(ForkSync.installed(ctx)) }
    var key by remember { mutableStateOf(prefs.getString("forkKey", "") ?: "") }
    var pcId by remember { mutableStateOf(prefs.getString("pcId", "") ?: "") }
    var err by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ForkSync.Report?>(null) }
    val tree = model.tree
    val api = remember(key) { key.trim().takeIf { it.length >= 20 }?.let { ForkSync.Api(it) } }

    // 5초마다: 설치 여부 확인, 컴퓨터가 보낸 공유 요청 받기, 방향 맞추기, 상태 갱신
    LaunchedEffect(api, pcId) {
        while (true) {
            forkOn = ForkSync.installed(ctx)
            if (api != null && tree != null) {
                val r = withContext(Dispatchers.IO) {
                    runCatching { ForkSync.tick(ctx, api, pcId.trim(), tree) }.getOrElse { ForkSync.Report(error = ForkSync.friendly(it)) }
                }
                report = r
                err = r.error ?: ""
            }
            delay(5000)
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("연결 마법사", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        Hint("Syncthing-Fork 를 이 앱이 대신 설정합니다. 처음 한 번만 하면 됩니다.")

        // API 키가 통하면 Fork 가 있다는 뜻이다(앱 이름이 달라 못 찾는 기기도 있어서)
        Step(1, "Syncthing-Fork 설치", forkOn || (report != null && err.isEmpty())) {
            if (!forkOn && report == null) {
                Hint("폰에 Syncthing-Fork 앱이 필요합니다(파일을 실제로 옮기는 앱). 이미 설치했다면 이 단계는 건너뛰고 아래 2번에 API 키를 넣으세요.")
                Button(onClick = {
                    fun open(uri: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }.isSuccess
                    // 구글 플레이 앱 → 플레이 웹페이지 → F-Droid → GitHub 순서로, 열리는 첫 곳
                    open("market://details?id=${ForkSync.STORE_PKG}") || open("https://play.google.com/store/apps/details?id=${ForkSync.STORE_PKG}") ||
                        open("https://f-droid.org/packages/${ForkSync.STORE_PKG}/") || open("https://github.com/Catfriend1/syncthing-android/releases")
                }) { Text("설치 페이지 열기") }
            } else {
                Hint("설치됨. 처음 실행하면 나오는 권한 안내(모든 파일 접근, 배터리 최적화 제외)를 모두 허용하세요.")
                ForkSync.installedPkg(ctx)?.let { pkg ->
                    OutlinedButton(onClick = { ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) } }) { Text("Syncthing-Fork 열기") }
                }
            }
        }

        Step(2, "API 키 붙여넣기", err.isEmpty() && report != null) {
            Hint("Syncthing-Fork 앱 → 왼쪽 위 메뉴(☰) → Settings(설정) → Syncthing Options → ‘Syncthing API Key (click to copy)’ 를 눌러 복사한 뒤 아래에 붙여넣으세요. (Fork 가 실행 중이어야 보입니다. 기기 ID 와는 다른 값입니다.)")
            OutlinedTextField(value = key, onValueChange = { key = ForkSync.keyFrom(it); prefs.edit().putString("forkKey", key).apply() }, singleLine = true,
                label = { Text("API 키") }, modifier = Modifier.fillMaxWidth())
            if (key.isNotEmpty()) Text("${key.length}자" + (if (key.length != 32) " — API 키는 영문·숫자 32자입니다" else ""), fontSize = 12.sp,
                color = if (key.length == 32) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Step(3, "컴퓨터와 연결", report?.connected == true) {
            Hint("컴퓨터 앱 → 옵션 → 동기화의 ‘이 컴퓨터의 기기 ID’ 를 복사해 붙여넣고 ‘컴퓨터 추가’를 누르세요. 그다음 컴퓨터 앱에 ‘연결을 요청한 기기’가 뜨면 [허용]을 누릅니다.")
            OutlinedTextField(value = pcId, onValueChange = { pcId = it.replace(Regex("\\s"), "").uppercase(); prefs.edit().putString("pcId", pcId).apply() },
                singleLine = true, label = { Text("컴퓨터 기기 ID") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 8.dp)) {
                Button(enabled = api != null && pcId.length >= 56, onClick = {
                    scope.launch {
                        note = ""
                        val r = withContext(Dispatchers.IO) { runCatching { api!!.putDevice(pcId, "컴퓨터") } }
                        note = if (r.isSuccess) "컴퓨터를 추가했습니다. 컴퓨터 앱에서 허용해 주세요." else "추가하지 못했습니다: ${r.exceptionOrNull()?.message}"
                    }
                }) { Text("컴퓨터 추가") }
            }
            if (note.isNotEmpty()) Text(note, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            report?.takeIf { it.myId.isNotEmpty() }?.let { r ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("내 폰 ID  ${r.myId.take(7)}…", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { clip.setText(AnnotatedString(r.myId)) }) { Text("복사") }
                }
            }
        }

        Step(4, "폴더 받기", (report?.folders?.isNotEmpty() == true)) {
            val r = report
            if (r == null || r.folders.isEmpty()) Hint("연결이 허용되면 컴퓨터가 보낸 폴더를 이 앱이 알아서 받아 정해진 위치에 둡니다.")
            else {
                Hint(if (r.connected) "컴퓨터와 연결됨" else "컴퓨터와 연결 안 됨(같은 와이파이인지, 컴퓨터 앱이 켜져 있는지 확인)")
                for (f in r.folders) {
                    val pct = if (f.totalBytes > 0) ((f.totalBytes - f.needBytes) * 100 / f.totalBytes).toInt() else 100
                    val s = when {
                        f.state == "idle" && f.needBytes == 0L -> "최신"
                        f.state == "idle" -> "대기 중 $pct%"
                        f.state.startsWith("sync") -> "받는 중 $pct%"
                        f.state.startsWith("scan") -> "확인 중"
                        else -> f.state
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(f.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(s, fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
