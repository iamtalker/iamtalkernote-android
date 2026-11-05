package kr.iamtalker.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
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
import androidx.compose.material3.OutlinedTextField
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

private val Green = Color(0xFF8A94A6)
private val Purple = Color(0xFF8A94A6)
private val Pink = Color(0xFF8A94A6)
private val Orange = Color(0xFF8A94A6)
private val Sky = Color(0xFF8A94A6)
private val Teal = Color(0xFF8A94A6)

/** 동그라미 안의 그림 + 이름 + 화살표(업노트 설정 목록 모양) */
@Composable
private fun SettingsRow(color: Color, label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { icon() }   // 설정 아이콘 원은 테마 강조색을 따른다
        Text(label, fontSize = 17.sp, modifier = Modifier.padding(start = 16.dp).weight(1f))
        ChevronRight()
    }
}

@Composable
private fun ChevronRight() {
    val c = MaterialTheme.colorScheme.outline   // 화살표 색도 테마를 따른다
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = 2.0.dp.toPx(); val s = size.width
        drawLine(c, androidx.compose.ui.geometry.Offset(s * .36f, s * .22f), androidx.compose.ui.geometry.Offset(s * .64f, s * .5f), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(c, androidx.compose.ui.geometry.Offset(s * .64f, s * .5f), androidx.compose.ui.geometry.Offset(s * .36f, s * .78f), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
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

/** 테마 한 줄: 라디오 + 색 견본(바탕색 원 안에 강조색 점) + 이름. 시스템은 밝음/어두움 반반 */
@Composable
private fun ThemeRow(name: String, selected: Boolean, palette: AppThemes.Palette?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        val p = palette
        val line = MaterialTheme.colorScheme.outlineVariant
        androidx.compose.foundation.Canvas(Modifier.padding(start = 4.dp, end = 12.dp).size(26.dp)) {
            val r = size.minDimension / 2
            if (p == null) {                                                       // 시스템: 왼쪽 밝게 · 오른쪽 어둡게
                drawArc(Color.White, 90f, 180f, true, size = size)
                drawArc(Color(0xFF15181C), -90f, 180f, true, size = size)
            } else {
                drawCircle(Color(p.bg), r)
                drawCircle(Color(p.accent), r * 0.5f)
            }
            drawCircle(line, r - 0.5.dp.toPx() / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        Text(tr(name), fontSize = 16.sp)
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

@Composable
fun SettingsScreen(model: NotesModel, page: String, onPage: (String) -> Unit, onBack: () -> Unit, onTool: (String) -> Unit, onPick: () -> Unit, onPickExt: () -> Unit = {}) {
    BackHandler { if (page.isEmpty()) onBack() else onPage("") }
    val ctx = LocalContext.current
    val titles = mapOf("sync" to tr("동기화"), "theme" to tr("테마"), "general" to tr("일반"), "scrap" to tr("스크랩"), "editor" to tr("에디터"), "tools" to tr("도구"), "help" to tr("도움말"), "info" to tr("정보"))
    Scaffold(topBar = {
        Column {
            Box(Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface)) {
                if (page.isNotEmpty()) Box(Modifier.align(Alignment.CenterStart)) { BarButton({ onPage("") }) { BackIcon(MaterialTheme.colorScheme.onSurface) } }
                Text(if (page.isEmpty()) tr("설정") else titles[page] ?: "", fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
                if (page.isEmpty()) TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { Text(tr("완료"), fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
            when (page) {
                "" -> {
                    SettingsRow(Sky, tr("동기화"), { onPage("sync") }) { GlyphIcon("sync") }
                    Gap()
                    var langDialog by remember { mutableStateOf(false) }
                    SettingsRow(Teal, "Language · " + Lang.names.getValue(Lang.code), { langDialog = true }) { Text(Lang.flags.getValue(Lang.code), fontSize = 18.sp) }
                    if (langDialog) androidx.compose.material3.AlertDialog(
                        onDismissRequest = { langDialog = false },
                        title = { Text("Language") },
                        text = { Column { for ((c, n) in Lang.names) ChoiceRow(Lang.flags.getValue(c) + "  " + n, Lang.code == c) { Lang.set(ctx, c); langDialog = false } } },
                        confirmButton = {},
                    )
                    Gap()
                    SettingsRow(Purple, tr("테마"), { onPage("theme") }) { GlyphIcon("theme") }
                    SettingsRow(Green, tr("일반"), { onPage("general") }) { GlyphIcon("general") }
                    SettingsRow(Orange, tr("스크랩"), { onPage("scrap") }) { Text("✂", color = Color.White, fontSize = 18.sp) }
                    SettingsRow(Pink, tr("에디터"), { onPage("editor") }) { Text("Aa", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    Gap()
                    SettingsRow(Orange, tr("도구") + if (model.conflicts.isEmpty()) "" else trf(" (충돌 %s)", model.conflicts.size), { onPage("tools") }) { GlyphIcon("tools") }
                    Gap()
                    SettingsRow(Teal, tr("도움말"), { onPage("help") }) { Text("?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    SettingsRow(Teal, tr("정보"), { onPage("info") }) { Text("i", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }
                "theme" -> {
                    for (name in AppThemes.names()) {
                        val pal = if (name == AppThemes.CUSTOM) AppThemes.custom(model.customBg, model.customAccent) else AppThemes.all[name]
                        ThemeRow(name, model.themeName == name, pal) { if (name == AppThemes.CUSTOM) model.changeCustom() else model.changeTheme(name) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(tr("내 테마 만들기"), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp))
                    var picking by remember { mutableStateOf<String?>(null) }
                    for ((key, label, color) in listOf(Triple("bg", tr("배경색"), model.customBg), Triple("accent", tr("강조색"), model.customAccent))) {
                        Row(Modifier.fillMaxWidth().clickable { picking = key }.heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(color))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape))
                            Text(label, fontSize = 16.sp, modifier = Modifier.padding(start = 14.dp).weight(1f))
                            Text("#%06X".format(color and 0xFFFFFF), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Text(tr("색 줄을 누르면 색 고르는 창이 뜹니다. 배경색이 어두우면 글자는 밝게, 밝으면 어둡게 저절로 맞춥니다."), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    picking?.let { k ->
                        ColorPickerDialog(if (k == "bg") tr("배경색 고르기") else tr("강조색 고르기"), if (k == "bg") model.customBg else model.customAccent,
                            onDismiss = { picking = null }) { c ->
                            if (k == "bg") model.changeCustom(bg = c) else model.changeCustom(accent = c)
                            picking = null
                        }
                    }
                }
                "general" -> {
                    SwitchRow(tr("앱으로 돌아올 때 자동 새로고침"), tr("동기화로 바뀐 노트를 마지막으로 읽은 지 1분이 지났으면 다시 읽습니다"), model.autoRefresh) { model.changeAutoRefresh(it) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(tr("위키 낱말 자동 연결"), tr("괄호 없이도 다른 노트 제목과 같은 낱말(2글자 이상)을 그 노트로 연결합니다. 같은 제목이 여럿이면 가장 최근 노트"), model.wikiAuto) { model.changeWikiAuto(it) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextRow(tr("지금 새로고침"), onClick = { model.refresh(); onBack() })
                    TextRow(tr("노트 폴더 다시 고르기"), onClick = onPick)
                    model.oldFolderPath()?.let { p ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TextRow(if (model.importing) tr("예전 폴더에서 가져오는 중…") else tr("예전 폴더에서 노트 가져오기"), onClick = { if (!model.importing) model.importFromOld() })
                        Text(trf("새 위치에 없는 파일만 복사합니다. 폰에서만 만든 노트를 지키려면 예전 폴더를 지우기 전에 한 번 하세요. 예전 폴더: %s", p), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
                "scrap" -> {
                    var dlg by remember { mutableStateOf(false) }
                    val cf = model.clipFolder
                    TextRow(tr("저장할 노트북"), if (cf == null) tr("지금 보던 노트북") else cf.ifEmpty { tr("(맨 위)") }.substringAfterLast('/'), onClick = { dlg = true })
                    Text(tr("다른 앱에서 공유(보내기)로 저장할 때 들어갈 노트북입니다. 공유 창에서 그때그때 바꿀 수도 있습니다."), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(tr("웹 클리퍼: 그림은 와이파이에서만"), tr("공유로 저장할 때 그림을 모바일 데이터로는 내려받지 않고 주소 그대로 둡니다(본문 글은 항상 가져옵니다)"), model.clipWifiOnly) { model.changeClipWifiOnly(it) }
                    if (dlg) {
                        var typed by remember { mutableStateOf("") }
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { dlg = false }, title = { Text(tr("저장할 노트북")) },
                            text = {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    ChoiceRow(tr("지금 보던 노트북"), cf == null) { model.changeClipFolder(null); dlg = false }
                                    ChoiceRow(tr("(맨 위)"), cf == "") { model.changeClipFolder(""); dlg = false }
                                    for (f in model.folders) ChoiceRow("  ".repeat(f.count { it == '/' }) + f.substringAfterLast('/'), cf == f) { model.changeClipFolder(f); dlg = false }
                                    androidx.compose.material3.OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, label = { Text(tr("새 노트북 이름(없으면 만듭니다)")) }, modifier = Modifier.padding(top = 8.dp))
                                }
                            },
                            confirmButton = { TextButton(enabled = typed.isNotBlank(), onClick = { model.changeClipFolder(typed.trim().trim('/')); dlg = false }) { Text(tr("이 이름으로")) } },
                            dismissButton = { TextButton(onClick = { dlg = false }) { Text(tr("취소")) } }
                        )
                    }
                }
                "editor" -> {
                    SwitchRow(tr("노트를 열 때 바로 편집"), tr("끄면 노트를 보기 모드로 열고 ‘편집’을 눌러 고칩니다"), model.openEditing) { model.changeOpenEditing(it) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(tr("편집 글자 크기"), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp))
                    for ((sz, label) in listOf(13 to tr("작게"), 15 to tr("보통"), 17 to tr("크게"), 19 to tr("아주 크게"))) ChoiceRow(label, model.editorFont == sz) { model.changeEditorFont(sz) }
                }
                "tools" -> {
                    for ((label, target) in listOf(tr("휴지통") to "trash", tr("전체 변경 이력") to "journal",
                        tr("동기화 충돌") + (if (model.conflicts.isEmpty()) "" else " (${model.conflicts.size})") to "conflicts",
                        tr("중복 노트 찾기") to "dups", tr("안 쓰는 미디어") to "media")) {
                        TextRow(label, onClick = { onTool(target) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                "sync" -> {
                    // 가장 중요한 규칙을 맨 위에 둔다
                    Text(tr("컴퓨터와 폰의 동기화 규칙(방향·함께 맞출 것)이 같아야 동기화됩니다. 다르면 그 폴더는 멈춥니다."), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                    SyncIdlePollSetting(ctx)
                    SyncWizard(model)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SyncControls(model)
                    TextRow(tr("외부 폴더 저장 위치"), model.extRoot?.substringAfterLast('/') ?: tr("자동"), onClick = onPickExt)
                    Text(trf("컴퓨터에서 공유한 외부 폴더(음악·PDF 등)를 받을 폴더입니다. 고른 폴더가 곧 받는 폴더이고, SD카드의 폴더도 고를 수 있습니다. 지금: %s. 위치를 바꾸면 받기 전용(컴퓨터 → 폰) 폴더는 새 위치로 다시 받습니다. 예전 위치의 파일은 지우지 않습니다.", model.extRoot ?: tr("자동(공유 저장소의 iamtalkernote-files)")), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(tr("폴더 경로(폰의 Syncthing-Fork): 노트 폴더, 그 안의 .resources(미디어), .history, .trash 는 앱이 자동으로 만들어 둡니다."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
                }
                "help" -> {
                    var helpShown by remember { mutableStateOf<Pair<String, String>?>(null) }
                    TextRow(tr("사용 설명서"), tr("열기"), onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ForkSync.MANUAL_URL))) } })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextRow(tr("컴퓨터 앱 내려받기"), tr("열기"), onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ForkSync.PC_URL))) } })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextRow(tr("검색 문법"), onClick = { helpShown = tr("검색 문법") to HelpText.search() })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextRow(tr("마크다운 문법"), onClick = { helpShown = tr("마크다운 문법") to HelpText.markdown() })
                    helpShown?.let { (t, body) -> ErrorDialog(body, t) { helpShown = null } }
                }
                "info" -> {
                    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
                    TextRow(tr("버전"), ver)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val upScope = rememberCoroutineScope()
                    var autoUp by remember { mutableStateOf(Updater.autoCheck(ctx)) }
                    LaunchedEffect(Unit) { Updater.check(ctx) }
                    Updater.available?.let { u ->
                        TextRow("새 버전 ${u.version} 받아서 설치", if (Updater.busy) tr("진행 중") else tr("업데이트"), onClick = { upScope.launch { Updater.updateNow(ctx) } })
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) { UpdateProgress(MaterialTheme.colorScheme.outline) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    TextRow(tr("업데이트 확인"), Updater.checkedMsg.ifEmpty { tr("누르세요") }, onClick = { upScope.launch { Updater.check(ctx, force = true) } })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchRow(tr("새 버전 자동 확인"), tr("앱을 열 때 6시간마다 새 버전이 있는지 보고, 있으면 노트 목록 위에 알립니다"), autoUp) { autoUp = it; Updater.setAutoCheck(ctx, it) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextRow(tr("컴퓨터 앱"), "github.com/iamtalker/iamtalkernote")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    var licShown by remember { mutableStateOf(false) }
                    TextRow(tr("오픈소스 라이선스"), tr("보기"), onClick = { licShown = true })
                    if (licShown) {
                        val text = remember { runCatching { ctx.assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() } }.getOrDefault(tr("라이선스 파일을 찾을 수 없습니다.")) }
                        ErrorDialog(text, tr("오픈소스 라이선스")) { licShown = false }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextRow(tr("노트 폴더"), model.tree?.lastPathSegment?.substringAfter(':') ?: "-")
                }
            }
        }
    }
}

/** 동기화가 완료된 뒤 상태를 다시 확인하는 간격. 전송 중 감시는 항상 5초로 유지한다. */
@Composable
private fun SyncIdlePollSetting(ctx: android.content.Context) {
    val prefs = remember { ctx.getSharedPreferences("settings", 0) }
    var text by remember { mutableStateOf(prefs.getLong(SyncPower.PREF_IDLE_SEC, SyncPower.DEFAULT_IDLE_MS / 1000L).toString()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(tr("완료 후 동기화 감시 간격(초)"), fontSize = 15.sp)
        Text(tr("전송 중에는 5초마다 확인하고, 완료 후에는 여기에 입력한 간격으로 확인합니다. 30초~86400초(24시간)."), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                val digits = value.filter(Char::isDigit).take(6)
                text = digits
                digits.toLongOrNull()?.let { sec ->
                    val normalized = sec.coerceIn(30L, 86_400L)
                    text = normalized.toString()
                    prefs.edit().putLong(SyncPower.PREF_IDLE_SEC, normalized).apply()
                }
            },
            singleLine = true,
            label = { Text(tr("초")) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}

/** 방향·함께 보낼 것의 한 줄 드롭다운(컴퓨터 옵션 → 동기화와 같은 모양). 눌러서 고른다 */
@Composable
private fun DropRow(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("$value ▾", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((k, v) in options) androidx.compose.material3.DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onSelect(k) })
        }
    }
}

/** 컴퓨터와 폰의 규칙이 어디가 어떻게 다른지(경고에 보일 글). id: [SyncSettings.NOTES] 또는 외부 폴더 ID */
private fun diffLines(pc: SyncSettings.Rules, eff: SyncSettings.Rules, id: String, names: Map<String, String>, includeLabels: Map<String, String>): List<String> {
    val out = ArrayList<String>()
    if (id == SyncSettings.NOTES) {
        if (pc.direction != eff.direction) out.add(trf("방향(컴퓨터 / 폰): %s / %s", names[pc.direction] ?: "?", names[eff.direction] ?: "?"))
        for (k in SyncSettings.KEYS) if (pc.include[k] != eff.include[k]) {
            val onoff = { v: Boolean? -> if (v == true) tr("켬") else tr("끔") }
            out.add(trf("%s(컴퓨터 / 폰): %s / %s", includeLabels[k] ?: k, onoff(pc.include[k]), onoff(eff.include[k])))
        }
    } else if (pc.ext[id] != eff.ext[id]) out.add(trf("방향(컴퓨터 / 폰): %s / %s", names[pc.ext[id]] ?: "?", names[eff.ext[id]] ?: "?"))
    return out
}

/** 동기화 규칙(방향·함께 보낼 것)을 폰에서 정한다. 컴퓨터(옵션 → 동기화)와 같아야 동기화되고, 다르면 그 폴더는 멈춘다(SyncSettings.kt) */
@Composable
private fun SyncControls(model: NotesModel) {
    val ctx = LocalContext.current
    val tree = model.tree ?: return
    val vault = remember(tree) { ForkSync.treePath(tree) } ?: return
    var pol by remember { mutableStateOf<JSONObject?>(null) }
    var pc by remember { mutableStateOf<SyncSettings.Rules?>(null) }
    var eff by remember { mutableStateOf<SyncSettings.Rules?>(null) }
    var again by remember { mutableStateOf(0) }
    // 컴퓨터가 바꾼 규칙(설정 폴더의 pc-config.json)을 몇 초마다 다시 읽어, 이 화면을 연 채로도 보이게 한다
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5000); again++ } }
    LaunchedEffect(again) {
        val r = withContext(Dispatchers.IO) {
            val p = runCatching { ForkSync.readPolicy(ctx, tree) }.getOrNull()
            val c = SyncSettings.pcRules(vault, p)
            Triple(p, c, c?.let { SyncSettings.effective(it, SyncSettings.seed(vault, it)) })
        }
        pol = r.first; pc = r.second; eff = r.third
    }
    val p = pol
    val c = pc
    val e = eff
    if (p == null || c == null || e == null) {   // 컴퓨터의 새 방식 정책(.sync/policy.json)이 아직 없다
        Text(tr("아직 컴퓨터의 정책 파일(.sync/policy.json)이 없습니다. 컴퓨터 앱 옵션 → 동기화에서 켜고 ‘적용’을 누르면 생깁니다."),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
        return
    }
    val scope = rememberCoroutineScope()
    fun write(block: () -> Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { if (block()) EmbeddedSync.api(ctx).scanSettingsFolder() }   // 컴퓨터에 바로 가게 설정 폴더를 즉시 검사
            again++
        }
    }
    val dirs = listOf("both" to tr("양방향"), "pc2mobile" to tr("컴퓨터 → 폰"), "mobile2pc" to tr("폰 → 컴퓨터"), "off" to tr("동기화 안 함"))
    val names = dirs.toMap()
    val includes = listOf(Triple("media", tr("미디어(그림·첨부)"), tr("끄면 그림은 폰으로 오지 않습니다")),
        Triple("history", tr("변경 이력 (고급)"), tr("파일이 아주 많습니다")), Triple("trash", tr("휴지통 (고급)"), tr("지운 노트 보관본")))
    val labels = includes.associate { it.first to it.second }
    val bad = SyncSettings.mismatched(c, e)
    @Composable
    fun Warn(id: String) {
        if (id in bad) Text("⚠ " + tr("컴퓨터와 규칙이 달라 이 폴더의 동기화를 멈췄습니다 — ") + diffLines(c, e, id, names, labels).joinToString("; ") + tr(". 컴퓨터나 폰을 같게 맞추면 다시 동기화됩니다."),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
    }
    @Composable
    fun Head(t: String) = Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp))
    Head(tr("노트 폴더"))
    DropRow(tr("방향"), names[e.direction] ?: "?", dirs) { d -> write { SyncSettings.change(vault, c, direction = d) } }
    Warn(SyncSettings.NOTES)
    Head(tr("함께 맞출 것"))
    for ((k, label, hint) in includes) SwitchRow(label, hint, e.include[k] == true) { on -> write { SyncSettings.change(vault, c, key = k, on = on) } }
    val exts = SyncSettings.extModules(p)
    if (exts.isNotEmpty()) {
        Head(tr("외부 폴더 (음악·PDF 등)"))
        for ((id, mid) in exts) {
            val name = p.getJSONObject("modules").getJSONObject(mid).optString("label", mid).substringAfter("외부 폴더 · ").trim()
            DropRow(name, names[e.ext[id]] ?: "?", dirs) { d -> write { SyncSettings.changeExt(vault, c, id, d) } }
            Warn(id)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
