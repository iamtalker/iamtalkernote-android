package kr.iamtalker.notes

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 폴더별 진행 상황 줄(Fork 마법사와 같은 표시) */
@Composable
fun FolderLines(r: ForkSync.Report) {
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

/** 내장 동기화 엔진을 쓰는 연결 마법사: 켜기 → 컴퓨터의 큐알 찍기 → 폴더 받기. 키도 기기 ID 도 복사하지 않는다 */
@Composable
fun EmbeddedWizard(model: NotesModel) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(EmbeddedSync.wanted(ctx)) }
    var allFiles by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var pcId by remember { mutableStateOf(prefs.getString("pcId", "") ?: "") }
    var manual by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val report = EmbeddedSync.report
    LaunchedEffect(Unit) { while (true) { allFiles = Environment.isExternalStorageManager(); delay(2000) } }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun pair(id: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { EmbeddedSync.api(ctx).putDevice(id, "컴퓨터") } }
            if (r.isSuccess) {
                pcId = id; prefs.edit().putString("pcId", id).apply()
                note = "컴퓨터를 등록했습니다. 컴퓨터 앱의 ‘폰 연결하기’ 창이 열려 있으면 바로 연결됩니다."
            } else note = "등록하지 못했습니다: ${r.exceptionOrNull()?.message}"
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("연결 마법사", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        Text("이 앱에 동기화 엔진이 들어 있어 다른 앱을 깔거나 키를 복사할 필요가 없습니다.", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))

        Text("1. 동기화 켜기", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
        if (!allFiles) {
            Text("노트 폴더에 접근하려면 ‘모든 파일 접근’ 허용이 필요합니다.", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 4.dp))
            Button(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
            }) { Text("모든 파일 접근 허용") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = {
                    if (on) { SyncService.stop(ctx); on = false }
                    else {
                        if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                        SyncService.start(ctx); on = true
                    }
                }) { Text(if (on) "동기화 끄기" else "동기화 켜기") }
                Text(if (on) "  켜져 있음 (알림이 떠 있는 동안 동작)" else "  꺼져 있음", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
            }
            Text("알림 표시와 배터리 최적화 제외를 허용해야 화면이 꺼져도 계속 동기화됩니다.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                modifier = Modifier.padding(top = 4.dp)) { Text("배터리 최적화 설정 열기") }
        }
        report?.error?.let { Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }

        Text("2. 컴퓨터와 연결", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 22.dp))
        Text("컴퓨터 앱 → 옵션 → 동기화 → [폰 연결하기…]를 누르면 큐알이 뜹니다. 아래 버튼으로 찍으세요.", fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 4.dp))
        val ready = on && report != null && report.myId.isNotEmpty()
        Button(enabled = ready, onClick = {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(ctx, opts).startScan()
                .addOnSuccessListener { b ->
                    val id = EmbeddedSync.parseDeviceId(b.rawValue.orEmpty())
                    if (id != null) pair(id) else note = "동기화 큐알이 아닙니다. 컴퓨터의 [폰 연결하기…] 큐알을 찍어 주세요."
                }
                .addOnFailureListener { note = "큐알 읽기를 열지 못했습니다(${it.message}). 아래에 컴퓨터 기기 ID 를 직접 넣어도 됩니다." }
        }) { Text("큐알 찍기") }
        if (!ready) Text("먼저 1번에서 동기화를 켜세요(시작에 몇 초 걸립니다).", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
        if (pcId.isNotEmpty()) Text("등록된 컴퓨터  ${pcId.take(7)}…  ·  " + if (report?.connected == true) "연결됨" else "연결 대기 중",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
        OutlinedTextField(value = manual, onValueChange = { manual = it }, singleLine = true, label = { Text("큐알이 안 될 때: 컴퓨터 기기 ID") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedButton(enabled = ready && EmbeddedSync.parseDeviceId(manual) != null, onClick = { EmbeddedSync.parseDeviceId(manual)?.let { pair(it) } },
            modifier = Modifier.padding(top = 4.dp)) { Text("직접 등록") }
        if (note.isNotEmpty()) Text(note, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))

        Text("3. 폴더 받기", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 22.dp))
        report?.note?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
        if (report == null || report.folders.isEmpty()) Text("연결되면 컴퓨터가 보낸 노트 폴더를 이 앱이 알아서 받습니다.", fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
        else FolderLines(report)
    }
}
