package kr.iamtalker.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Green = Color(0xFF7DC25A)
private val Purple = Color(0xFF8B5FD6)
private val Pink = Color(0xFFFF5C9E)
private val Orange = Color(0xFFFF8A3D)
private val Sky = Color(0xFF2A9DF0)
private val Teal = Color(0xFF4DC0A3)

/** 동그라미 안의 그림 + 이름 + 화살표(업노트 설정 목록 모양) */
@Composable
private fun SettingsRow(color: Color, label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { icon() }
        Text(label, fontSize = 17.sp, modifier = Modifier.padding(start = 16.dp).weight(1f))
        ChevronRight()
    }
}

@Composable
private fun ChevronRight() {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = 2.0.dp.toPx(); val s = size.width
        drawLine(Color(0xFFB0B5BC), androidx.compose.ui.geometry.Offset(s * .36f, s * .22f), androidx.compose.ui.geometry.Offset(s * .64f, s * .5f), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(Color(0xFFB0B5BC), androidx.compose.ui.geometry.Offset(s * .64f, s * .5f), androidx.compose.ui.geometry.Offset(s * .36f, s * .78f), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
private fun Gap() { Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant)) }

/** 라디오 한 줄 */
@Composable
fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.heightIn(min = 60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            if (hint.isNotEmpty()) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TextRow(label: String, value: String = "", onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
    }
}

/** 컴퓨터 앱이 노트 폴더 안 .sync/policy.json 에 저장한 동기화 정책을 읽는다 → (모듈 이름, 방향) 목록. 없으면 null */
private fun readPolicy(ctx: android.content.Context, tree: android.net.Uri): List<Pair<String, String>>? {
    val root = android.provider.DocumentsContract.getTreeDocumentId(tree)
    val dir = History.list(ctx, tree, root).firstOrNull { it.isDir && it.name == ".sync" } ?: return null
    val file = History.list(ctx, tree, dir.id).firstOrNull { !it.isDir && it.name == "policy.json" } ?: return null
    val mods = JSONObject(NoteRepo.read(ctx, History.docUri(tree, file.id))).optJSONObject("modules") ?: return null
    val names = mapOf("both" to "양방향", "pc2mobile" to "PC → 모바일", "mobile2pc" to "모바일 → PC", "off" to "동기화 안 함")
    return mods.keys().asSequence().map { k -> mods.getJSONObject(k).let { it.optString("label", k) to (names[it.optString("direction")] ?: "?") } }.toList()
}

@Composable
fun SettingsScreen(model: NotesModel, page: String, onPage: (String) -> Unit, onBack: () -> Unit, onTool: (String) -> Unit, onPick: () -> Unit) {
    BackHandler { if (page.isEmpty()) onBack() else onPage("") }
    val ctx = LocalContext.current
    val titles = mapOf("sync" to "동기화", "theme" to "테마", "general" to "일반", "editor" to "에디터", "tools" to "도구", "info" to "정보")
    Scaffold(topBar = {
        Column {
            Box(Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface)) {
                if (page.isNotEmpty()) Box(Modifier.align(Alignment.CenterStart)) { BarButton({ onPage("") }) { BackIcon(MaterialTheme.colorScheme.onSurface) } }
                Text(if (page.isEmpty()) "설정" else titles[page] ?: "", fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
                if (page.isEmpty()) TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { Text("완료", fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
            when (page) {
                "" -> {
                    SettingsRow(Sky, "동기화", { onPage("sync") }) { GlyphIcon("sync") }
                    Gap()
                    SettingsRow(Purple, "테마", { onPage("theme") }) { GlyphIcon("theme") }
                    SettingsRow(Green, "일반", { onPage("general") }) { GlyphIcon("general") }
                    SettingsRow(Pink, "에디터", { onPage("editor") }) { Text("Aa", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    Gap()
                    SettingsRow(Orange, "도구" + if (model.conflicts.isEmpty()) "" else " (충돌 ${model.conflicts.size})", { onPage("tools") }) { GlyphIcon("tools") }
                    Gap()
                    SettingsRow(Teal, "정보", { onPage("info") }) { Text("i", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
                "theme" -> {
                    for ((i, label) in listOf("시스템 설정을 따라가기", "밝게", "어둡게").withIndex()) ChoiceRow(label, model.themeMode == i) { model.changeTheme(i) }
                }
                "general" -> {
                    SwitchRow("앱으로 돌아올 때 자동 새로고침", "동기화로 바뀐 노트를 마지막으로 읽은 지 1분이 지났으면 다시 읽습니다", model.autoRefresh) { model.changeAutoRefresh(it) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextRow("지금 새로고침", onClick = { model.refresh(); onBack() })
                    TextRow("노트 폴더 다시 고르기", onClick = onPick)
                }
                "editor" -> {
                    SwitchRow("노트를 열 때 바로 편집", "끄면 노트를 보기 모드로 열고 ‘편집’을 눌러 고칩니다", model.openEditing) { model.changeOpenEditing(it) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("편집 글자 크기", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp))
                    for ((sz, label) in listOf(13 to "작게", 15 to "보통", 17 to "크게", 19 to "아주 크게")) ChoiceRow(label, model.editorFont == sz) { model.changeEditorFont(sz) }
                }
                "tools" -> {
                    for ((label, target) in listOf("휴지통" to "trash", "전체 변경 이력" to "journal",
                        "동기화 충돌" + (if (model.conflicts.isEmpty()) "" else " (${model.conflicts.size})") to "conflicts",
                        "중복 노트 찾기" to "dups", "안 쓰는 미디어" to "media")) {
                        TextRow(label, onClick = { onTool(target) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                "sync" -> {
                    SyncWizard(model)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SyncControls(model)
                    var policy by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
                    var loaded by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        policy = withContext(Dispatchers.IO) { runCatching { model.tree?.let { readPolicy(ctx, it) } }.getOrNull() }
                        loaded = true
                    }
                    Text("컴퓨터의 iamtalkernote(옵션 → 동기화)에서 정한 방향입니다. 파일은 폰의 Syncthing-Fork 가 맞추고, 이 앱은 그 폴더를 읽고 씁니다.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val list = policy
                    if (!loaded) Text("읽는 중…", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
                    else if (list == null) Text("아직 정책 파일(.sync/policy.json)이 없습니다. 컴퓨터 앱(0.5.1 이상)의 옵션 → 동기화에서 켜고 ‘적용’을 누르면 생깁니다.",
                        modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
                    else for ((name, dir) in list) { TextRow(name, dir); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                    Text("폴더 경로(폰의 Syncthing-Fork): 노트 폴더, 그 안의 .resources(미디어), .history, .trash, .imported 는 앱이 자동으로 만들어 둡니다.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
                }
                "info" -> {
                    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
                    TextRow("버전", ver)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextRow("컴퓨터 앱", "github.com/iamtalker/iamtalkernote")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextRow("사용 설명서", "열기", onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ForkSync.MANUAL_URL))) } })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextRow("노트 폴더", model.tree?.lastPathSegment?.substringAfter(':') ?: "-")
                }
            }
        }
    }
}

/** 동기화 방향과 함께 보낼 항목을 폰에서 고친다. 컴퓨터와 '가장 최근에 바꾼 쪽'이 적용된다(설정 폴더로 주고받음, SyncSettings.kt) */
@Composable
private fun SyncControls(model: NotesModel) {
    val ctx = LocalContext.current
    val tree = model.tree ?: return
    val vault = remember(tree) { ForkSync.treePath(tree) } ?: return
    var pol by remember { mutableStateOf<JSONObject?>(null) }
    var eff by remember { mutableStateOf<SyncSettings.S?>(null) }
    var again by remember { mutableStateOf(0) }
    LaunchedEffect(again) {
        val p = withContext(Dispatchers.IO) { runCatching { ForkSync.readPolicy(ctx, tree) }.getOrNull() }
        pol = p
        eff = p?.let { SyncSettings.fromPolicy(it) }?.let { SyncSettings.effective(it, withContext(Dispatchers.IO) { SyncSettings.read(vault) }) }
    }
    val p = pol
    val e = eff
    if (p == null || e == null) return   // 컴퓨터의 새 방식 정책(.sync/policy.json)이 아직 없다
    val scope = rememberCoroutineScope()
    fun change(direction: String? = null, key: String? = null, on: Boolean? = null) {
        scope.launch { withContext(Dispatchers.IO) { SyncSettings.change(vault, p, direction, key, on) }; again++ }
    }
    Text("동기화 방향", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp))
    for ((d, label) in listOf("both" to "양방향", "pc2mobile" to "컴퓨터 → 폰 (받기만)", "mobile2pc" to "폰 → 컴퓨터 (보내기만)", "off" to "동기화 안 함"))
        ChoiceRow(label, e.direction == d) { change(direction = d) }
    Text("함께 맞출 것", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp))
    for ((k, label, hint) in listOf(Triple("media", "미디어(그림·첨부)", "끄면 그림은 폰으로 오지 않습니다"), Triple("imported", "가져온 문서 요약", "폰 앱이 읽는 가져온 문서 파일"),
        Triple("history", "변경 이력 (고급)", "파일이 아주 많습니다"), Triple("trash", "휴지통 (고급)", "지운 노트 보관본")))
        SwitchRow(label, hint, e.include[k] == true) { change(key = k, on = it) }
    Text("여기서 바꾸면 몇 초 안에 적용되고, 컴퓨터(0.6.3 이상)에도 반영됩니다. 양쪽에서 바꾸면 가장 최근에 바꾼 쪽이 이깁니다.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

