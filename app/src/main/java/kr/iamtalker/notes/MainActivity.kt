package kr.iamtalker.notes

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: NotesModel by viewModels()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        model.handleSend(intent)
    }

    override fun onResume() {
        super.onResume()
        model.refreshIfStale()   // 백그라운드에서 동기화로 바뀐 노트·노트북이 앱으로 돌아오면 보이게
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        model.handleSend(intent)
        model.watchFork()   // Fork 연결을 해 둔 경우 컴퓨터가 보낸 공유 폴더를 어느 화면에서든 자동으로 받는다
        setContent {
            val dark = when (model.themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF5AAEF0), background = Color(0xFF15181C), surface = Color(0xFF15181C), surfaceVariant = Color(0xFF22262B),
                outline = Color(0xFF8C939B), outlineVariant = Color(0xFF30353B), onSurface = Color(0xFFE6E8EB))
            else lightColorScheme(primary = Accent, background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFF2F4F6),
                outline = Color(0xFF8A9099), outlineVariant = Color(0xFFE5E8EB), onSurface = Color(0xFF22262B))
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                window.statusBarColor = scheme.surface.toArgb()
                window.navigationBarColor = scheme.surface.toArgb()
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
            }
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { App(model) } }
        }
    }
}


val Bar = Color(0xFF32373F)
val Accent = Color(0xFF0B72C9)   // 업노트처럼 차분한 파랑

/** 위 막대의 눌리는 칸(아이콘용) */
@Composable
fun BarButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/** 위 막대에서 눌러 고르는 목록 */
@Composable
fun BarMenu(icon: @Composable () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarButton({ open = true }, icon)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

@Composable
fun DrawerHeading(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
}

/** 서랍의 한 줄: 납작하고 고른 줄만 옅게 칠한다 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerRow(label: String, selected: Boolean = false, badge: String? = null, indent: Int = 0, trailing: (@Composable () -> Unit)? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 16.dp + indent.dp, end = 16.dp).heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (badge != null) Text(badge, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 10.dp))
        trailing?.invoke()
    }
}


@Composable
fun NavHeading(text: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

private val NbColors = listOf(Color(0xFF0A90C2), Color(0xFF0E9F6E), Color(0xFF7C5CD6), Color(0xFFE8833A), Color(0xFFD6558B))

/** 노트북 한 줄: 색 네모 + 이름 + 개수(+ 접기 화살표) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotebookRow(name: String, count: Int, depth: Int, selected: Boolean, key: String, collapsed: Boolean?, onToggle: () -> Unit, onLong: () -> Unit, onClick: () -> Unit) {
    val base = NbColors[(key.substringBefore('/').hashCode() and 0x7fffffff) % NbColors.size]
    Row(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong).heightIn(min = 52.dp).padding(start = 16.dp + (depth * 24).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(Brush.verticalGradient(listOf(base, base.copy(alpha = 0.78f)))))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp, end = 8.dp).weight(1f, fill = false))
            Text("$count", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
        }
        if (collapsed != null) BarButton(onToggle) { ChevronIcon(collapsed, MaterialTheme.colorScheme.outline) }
    }
}

/** 노트 목록의 한 줄(업노트처럼 제목·한 줄 미리보기·날짜) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteRow(n: Note, pinned: Boolean, chosen: Boolean, fmt: SimpleDateFormat, onClick: () -> Unit, onLong: () -> Unit, fav: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) { PinIcon(MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)) }
                Text(n.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (n.snip.isNotEmpty()) Text(n.snip, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f), modifier = Modifier.padding(top = 6.dp))
            Text(
                fmt.format(Date(n.date)) + (if (n.folder.isEmpty()) "" else "  ·  " + n.folder.substringAfterLast('/')) +
                    (if (n.tags.isEmpty()) "" else "  " + n.tags.take(3).joinToString(" ") { "#$it" }),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 아래 ⋯ 메뉴: 업노트처럼 오른쪽 가장자리에 붙여 뜨고, 새 노트(연필) 버튼을 완전히 덮는다. 바깥을 누르면 닫힌다 */
@Composable
fun BoxScope.MoreMenu(onDismiss: () -> Unit, items: List<Pair<String, () -> Unit>>, dividerBefore: Int) {
    Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() })
    Surface(
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 58.dp).width(200.dp),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            items.forEachIndexed { i, (label, act) ->
                if (i == dividerBefore) HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(label, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { onDismiss(); act() }.padding(horizontal = 20.dp, vertical = 14.dp))
            }
        }
    }
}

/** 눌러서 고르는 목록 단추 */
@Composable
fun MenuButton(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) { if (label == "⋮") DotsIcon() else Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

/** 검색창 아래 후보: 비었으면 최근 검색, 아니면 마지막 낱말에 맞는 #태그 또는 노트 제목 → (보이는 글, 눌렀을 때의 검색식) */
fun suggestions(model: NotesModel): List<Pair<String, String>> {
    val q = model.query
    if (q.isBlank()) return model.recent.take(8).map { it to it }
    val token = q.substringAfterLast(' ')
    val head = q.dropLast(token.length)
    if (token.startsWith("#")) {
        if (token.length < 2) return emptyList()
        return model.tagCounts.map { it.first }.filter { it.startsWith(token.drop(1), ignoreCase = true) && !it.equals(token.drop(1), true) }
            .take(8).map { "#$it" to head + "#$it " }
    }
    if (token.length < 1 || token in listOf("AND", "OR", "NOT", "&", "|", "!") || token.startsWith("(") || token.startsWith("-") || token.startsWith("\"")) return emptyList()
    return model.notes.asSequence().map { it.title }.filter { it.contains(token, ignoreCase = true) && !it.equals(token, true) }.distinct().take(8)
        .map { t -> t to head + (if (t.contains(' ')) "\"$t\"" else t) + " " }.toList()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun App(model: NotesModel) {
    var open by remember { mutableStateOf<Note?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> if (uri != null) model.pick(uri) }
    val ctx = LocalContext.current

    var crash by remember { mutableStateOf(CrashLog.take(ctx)) }
    crash?.let { text ->
        AlertDialog(
            onDismissRequest = { crash = null },
            title = { Text("지난 실행에서 오류가 났습니다") },
            text = { SelectionContainer { Text(text.take(1800), fontSize = 11.sp, modifier = Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = { crash = null }) { Text("닫기") } }
        )
    }

    var screen by remember { mutableStateOf("") }
    var fromSettings by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf("") }
    fun closeTool() { screen = if (fromSettings) "settings" else ""; fromSettings = false }
    var openDoc by remember { mutableStateOf<Doc?>(null) }
    LaunchedEffect(Unit) {
        Updater.justUpdated(ctx)?.let { android.widget.Toast.makeText(ctx, "iamtalkernote 가 $it 로 업데이트되었습니다", android.widget.Toast.LENGTH_LONG).show() }
        Updater.check(ctx)   // 새 버전 자동 확인(6시간에 한 번)
        PlannerAlert.schedule(ctx); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { PlannerAlert.check(ctx) } }   // 달력·할일 알림
    }
    UpdateDialog()
    LaunchedEffect(model.openSync) { if (model.openSync) { model.openSync = false; openDoc = null; open = null; fromSettings = false; settingsPage = "sync"; screen = "settings" } }
    openDoc?.let { dd -> DocScreen(dd, model, onBack = { openDoc = null }); return }
    if (screen == "trash") { TrashScreen(model, onBack = { closeTool(); model.refresh() }); return }
    if (screen == "conflicts") { ConflictsScreen(model, onBack = { closeTool() }); return }
    if (screen == "dups") { DuplicatesScreen(model, onBack = { closeTool() }, onOpen = { screen = ""; open = it }); return }
    if (screen == "media") { UnusedMediaScreen(model, onBack = { closeTool(); model.refresh() }); return }
    if (screen == "planner") { PlannerScreen(model, onBack = { closeTool() }); return }
    if (screen == "journal") { JournalScreen(model, onBack = { closeTool() }, onOpen = { screen = ""; open = it }); return }
    if (screen == "settings") { SettingsScreen(model, settingsPage, { settingsPage = it }, onBack = { screen = ""; settingsPage = "" }, onTool = { fromSettings = true; screen = it }, onPick = { picker.launch(null) }); return }
    val cur = open
    if (cur != null) {
        androidx.compose.runtime.key(cur.uri) {
            Editor(cur, model, onClose = { open = null; model.refresh() }, onOpenTitle = { title ->
                val n = NoteText.findLatestByTitle(model.notes, title, { it.title }, { it.modified })
                if (n != null) open = n else android.widget.Toast.makeText(ctx, "‘$title’ 노트를 찾지 못했습니다", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
        return
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var batchDialog by remember { mutableStateOf("") }          // move / tag / trash
    var saveDialog by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf<String?>(null) }
    var notebookDialog by remember { mutableStateOf(false) }
    var notebookMenu by remember { mutableStateOf<String?>(null) }   // ⋮ 를 누른 노트북
    var deleteSaved by remember { mutableStateOf<Saved?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var selectMode by remember { mutableStateOf(false) }
    var hadSel by remember { mutableStateOf(false) }
    val selecting = selectMode || model.selected.isNotEmpty()
    LaunchedEffect(model.selected.size) { if (model.selected.isNotEmpty()) hadSel = true else if (hadSel) { selectMode = false; hadSel = false } }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = selecting && !drawer.isOpen) { selectMode = false; model.clearSelect() }

    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var searching by remember { mutableStateOf(false) }
    val inSearch = searching || model.query.isNotBlank()
    var moreOpen by remember { mutableStateOf(false) }
    var pinnedOpen by remember { mutableStateOf(true) }
    var listDialog by remember { mutableStateOf("") }              // sort / filter
    val vaultName = remember(model.tree) { model.tree?.let { ForkSync.treePath(it) }?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null } ?: "저장 폴더" }
    var tagsOpen by remember { mutableStateOf(false) }
    var docsOpen by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }  // 접어 둔 노트북
    BackHandler(enabled = moreOpen) { moreOpen = false }
    BackHandler(enabled = inSearch && !drawer.isOpen && !selecting) { searching = false; model.changeQuery(""); focusManager.clearFocus() }
    LaunchedEffect(searching) { if (searching) runCatching { searchFocus.requestFocus() } }
    val fmt = remember { SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA) }
    val noteListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val fg = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val soft = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = model.tree != null,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface, drawerTonalElevation = 0.dp, drawerShape = androidx.compose.ui.graphics.RectangleShape, modifier = Modifier.fillMaxWidth(0.80f)) {
                fun go(block: () -> Unit) { block(); scope.launch { drawer.close() } }
                val allSel = !model.showDocs && model.folder == null && model.tag == null && !model.favOnly
                LazyColumn {
                    item { Spacer(Modifier.height(8.dp)) }
                    item {
                        Row(Modifier.fillMaxWidth().background(if (allSel) soft else Color.Transparent).clickable { go { model.changeFolder(null); model.changeTag(null) } }
                            .heightIn(min = 52.dp).padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            NotesIcon(accent)
                            Text("모든 파일", fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 14.dp))
                            Text("${model.notes.size}", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        }
                    }
                    item { Text("바로가기", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, top = 10.dp)) }
                    item {
                        Row(Modifier.fillMaxWidth().background(if (model.favOnly) soft else Color.Transparent).clickable { go { model.changeFav(true) } }
                            .heightIn(min = 44.dp).padding(start = 40.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("즐겨찾기", fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text("${model.favs.size}", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if ("아웃라이너" in model.folders) item {
                        Row(Modifier.fillMaxWidth().background(if (!model.showDocs && model.folder == "아웃라이너" && model.tag == null) soft else Color.Transparent)
                            .clickable { go { model.changeFolder("아웃라이너"); model.changeTag(null) } }.heightIn(min = 44.dp).padding(start = 40.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("아웃라이너", fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Text("${model.folderCounts["아웃라이너"] ?: 0}", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().clickable { go { screen = "planner" } }.heightIn(min = 44.dp).padding(start = 40.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("달력·할일", fontSize = 16.sp, modifier = Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().background(if (!model.showDocs && model.folder == "" && model.tag == null && !model.favOnly) soft else Color.Transparent)
                            .clickable { go { model.changeFolder(""); model.changeTag(null) } }.heightIn(min = 52.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(vaultName, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            BarButton({ go { notebookDialog = true } }) { Text("+", fontSize = 26.sp, color = accent) }
                        }
                    }
                    val visible = model.folders.filter { f -> collapsed.none { c -> f.startsWith("$c/") } && f != "아웃라이너" && !f.startsWith("아웃라이너/") }
                    items(visible, key = { "f:$it" }) { f ->
                        val depth = f.count { it == '/' } + 1
                        val hasKids = model.folders.any { it.startsWith("$f/") }
                        NotebookRow(f.substringAfterLast('/'), model.folderCounts[f] ?: 0, depth, model.folder == f, f,
                            if (hasKids) (f in collapsed) else null, { collapsed = if (f in collapsed) collapsed - f else collapsed + f },
                            onLong = { notebookMenu = f }) { go { model.changeFolder(f); model.changeTag(null) } }
                    }
                    if (model.tagCounts.isNotEmpty()) {
                        item { NavHeading("태그", trailing = { ChevronIcon(!tagsOpen, MaterialTheme.colorScheme.outline) }) { if (model.tag != null) go { model.changeTag(null) } else tagsOpen = !tagsOpen } }
                        if (tagsOpen) items(model.tagCounts, key = { "t:" + it.first }) { (t, n) ->
                            DrawerRow("#$t", selected = model.tag.equals(t, ignoreCase = true), badge = "$n", indent = 8, onLongClick = { tagDialog = t }) {
                                go { model.changeFolder(null); model.changeTag(t) }
                            }
                        }
                    }
                    // 데스크톱처럼 노트북(md 폴더)이 위, 가져온 파일(컴퓨터)이 그 아래
                    if (model.docs.isNotEmpty()) {
                        item { NavHeading("가져온 파일 (컴퓨터)", trailing = { ChevronIcon(!docsOpen, MaterialTheme.colorScheme.outline) }) { docsOpen = !docsOpen } }
                        if (docsOpen) {
                            item { DrawerRow("모든 가져온 파일", selected = model.showDocs && model.docFolder == null, badge = "${model.docs.size}", indent = 8) { go { model.selectDocs(null) } } }
                            items(model.docFolders, key = { "d:" + it.first }) { (name, n) ->
                                DrawerRow(name, selected = model.showDocs && model.docFolder == name, badge = "$n", indent = 24) { go { model.selectDocs(name) } }
                            }
                        }
                    }
                    if (model.saved.isNotEmpty()) {
                        item { NavHeading("저장된 검색") {} }
                        items(model.saved, key = { "s:" + it.name }) { sv -> DrawerRow(sv.name, onLongClick = { deleteSaved = sv }) { go { model.applySaved(sv) } } }
                    }
                    if (model.conflicts.isNotEmpty()) item { NavHeading("동기화 충돌 (${model.conflicts.size})") { go { screen = "conflicts" } } }
                    item { NavHeading("휴지통") { go { screen = "trash" } } }
                    item { NavHeading("설정") { go { screen = "settings" } } }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Column {
                    Box(Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface)) {
                        if (selecting) {
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                BarButton({ selectMode = false; model.clearSelect() }) { CloseIcon(fg) }
                                Text("${model.selected.size}개 선택", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { model.selectAll() }) { Text("전체") }
                                BarMenu({ Text("작업", color = accent, fontSize = 15.sp) }) { close ->
                                    DropdownMenuItem(text = { Text("노트북으로 이동") }, onClick = { batchDialog = "move"; close() })
                                    DropdownMenuItem(text = { Text("태그 추가") }, onClick = { batchDialog = "tag"; close() })
                                    DropdownMenuItem(text = { Text("휴지통으로") }, onClick = { batchDialog = "trash"; close() })
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        } else if (inSearch && model.tree != null) {
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                BarButton({ searching = false; model.changeQuery(""); focusManager.clearFocus() }) { BackIcon(fg) }
                                BasicTextField(
                                    value = model.query, onValueChange = { model.changeQuery(it) }, singleLine = true,
                                    textStyle = TextStyle(color = fg, fontSize = 17.sp), cursorBrush = SolidColor(accent),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { model.addRecent(model.query); focusManager.clearFocus() }),
                                    modifier = Modifier.weight(1f).focusRequester(searchFocus).onFocusChanged { searchFocused = it.isFocused },
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (model.query.isEmpty()) Text("검색  (\"구절\"  -제외  #태그)", color = MaterialTheme.colorScheme.outline, fontSize = 15.sp, maxLines = 1)
                                            inner()
                                        }
                                    }
                                )
                                if (model.query.isNotEmpty()) BarButton({ model.changeQuery("") }) { CloseIcon(MaterialTheme.colorScheme.outline) }
                            }
                        } else {
                            if (model.tree != null) Box(Modifier.align(Alignment.CenterStart)) { BarButton({ scope.launch { drawer.open() } }) { HamburgerIcon(MaterialTheme.colorScheme.outline) } }
                            Text(
                                if (model.showDocs) "가져온 파일" + (model.docFolder?.let { " · $it" } ?: "")
                                else model.tag?.let { "#$it" } ?: model.folder?.let { if (it.isEmpty()) vaultName else it.substringAfterLast('/') } ?: "모든 노트",
                                fontSize = 20.sp, fontWeight = FontWeight.Medium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = line)
                }
            },
            bottomBar = {
                if (model.tree != null && !selecting && !inSearch) Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    HorizontalDivider(color = line)
                    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        BarButton({ searching = true }) { SearchIcon(MaterialTheme.colorScheme.outline) }
                        BarButton({ moreOpen = true }) { HDotsIcon() }
                    }
                }
            },
            floatingActionButton = {
                if (model.tree != null && !selecting && !inSearch) FloatingActionButton(onClick = { model.create("")?.let { open = it } }, shape = CircleShape,
                    containerColor = accent, contentColor = Color.White) { PencilIcon(Color.White) }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (model.tree == null) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("노트 폴더를 골라 주세요", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("컴퓨터의 iamtalkernote 와 같은 폴더(동기화 도구로 폰에 받은 폴더)를 고르면 됩니다.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { picker.launch(null) }) { Text("폴더 고르기") }
                    }
                    return@Column
                }
                if (inSearch && !selecting) {
                    val sugg = if (searchFocused) suggestions(model) else emptyList()
                    if (sugg.isNotEmpty()) {
                        Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                if (model.query.isBlank()) Text("최근 검색", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
                                for ((label, next) in sugg) Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().clickable { model.changeQuery(next); if (model.query.isBlank()) focusManager.clearFocus() }.padding(horizontal = 16.dp, vertical = 10.dp))
                            }
                        }
                    }
                    Row(Modifier.padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = model.inBody, onCheckedChange = { model.changeInBody(it) })
                        Text("본문까지", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(if (model.showDocs) "${model.shownDocs.size}개" else "${model.shown.size}개", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        TextButton(enabled = model.query.isNotBlank(), onClick = { saveDialog = true }) { Text("검색 저장") }
                    }
                    HorizontalDivider(color = line)
                } else if (model.status.isNotBlank() && !Regex("노트 \\d+개").matches(model.status.trim())) {
                    Text(model.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdateBanner()
                if (model.conflicts.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().clickable { screen = "conflicts" }) {
                        Text("동기화 충돌 ${model.conflicts.size}개 — 눌러서 해결", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp)
                    }
                }
                val pinnedList = if (inSearch) emptyList() else model.shown.filter { it.uri.toString() in model.pins }
                val restList = if (pinnedList.isEmpty()) model.shown else model.shown.filter { it.uri.toString() !in model.pins }
                PullRefresh(model) {
                if (model.showDocs) DocList(model) { openDoc = it } else LazyColumn(Modifier.fillMaxSize().listScrollbar(noteListState, MaterialTheme.colorScheme.outline), state = noteListState) {
                    if (model.shown.isEmpty() && !model.loading) item {
                        Text(if (inSearch) "찾은 노트가 없습니다" else "노트가 없습니다. 오른쪽 아래 연필로 만들어 보세요.", color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxWidth().padding(32.dp))
                    }
                    if (pinnedList.isNotEmpty()) {
                        item(key = "pin-head") {
                            Row(Modifier.fillMaxWidth().background(soft).clickable { pinnedOpen = !pinnedOpen }.heightIn(min = 46.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("고정됨", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text("${pinnedList.size}", fontSize = 15.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 10.dp).weight(1f))
                                ChevronIcon(!pinnedOpen, MaterialTheme.colorScheme.outline)
                            }
                            HorizontalDivider(color = line)
                        }
                        if (pinnedOpen) items(pinnedList, key = { "p:" + it.uri.toString() }) { n ->
                            NoteRow(n, true, n.uri.toString() in model.selected, fmt,
                                onClick = { if (selecting) model.toggleSelect(n) else { model.addRecent(model.query); open = n } },
                                onLong = { selectMode = true; model.toggleSelect(n) }, fav = Favorites.relOf(n) in model.favs)
                        }
                        item(key = "pin-end") { Box(Modifier.fillMaxWidth().height(10.dp).background(soft)) }
                    }
                    items(restList, key = { it.uri.toString() }) { n ->
                        NoteRow(n, false, n.uri.toString() in model.selected, fmt,
                            onClick = { if (selecting) model.toggleSelect(n) else { model.addRecent(model.query); open = n } },
                            onLong = { selectMode = true; model.toggleSelect(n) }, fav = Favorites.relOf(n) in model.favs)
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
                }
            }
        }
        if (moreOpen) MoreMenu(
            onDismiss = { moreOpen = false }, dividerBefore = 4,
            items = listOf("새 노트북" to { notebookDialog = true }, "노트 필터" to { listDialog = "filter" }, "노트 정렬" to { listDialog = "sort" },
                "노트 선택" to { selectMode = true }, "설정" to { screen = "settings" })
        )
        }
    }
    if (listDialog.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { listDialog = "" }, title = { Text(if (listDialog == "sort") "노트 정렬" else "노트 필터") },
            text = {
                Column {
                    if (listDialog == "sort") for (s in Sort.values()) ChoiceRow(s.label, s == model.sort) { model.changeSort(s); listDialog = "" }
                    else for (p in Period.values()) ChoiceRow(p.label, p == model.period) { model.changePeriod(p); listDialog = "" }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { listDialog = "" }) { Text("닫기") } }
        )
    }
    tagDialog?.let { tg ->
        var name by remember(tg) { mutableStateOf(tg) }
        var confirmDelete by remember(tg) { mutableStateOf(false) }
        val count = model.tagCounts.firstOrNull { it.first == tg }?.second ?: 0
        AlertDialog(
            onDismissRequest = { tagDialog = null }, title = { Text("#$tg") },
            text = {
                Column {
                    Text("이 태그가 든 노트 ${count}개에 적용됩니다. 머리말 tags·글 아래 ‘태그:’ 줄·본문 #태그를 모두 고치고, 이전 내용은 노트 버전으로 남습니다.", fontSize = 12.sp)
                    if (confirmDelete) Text("‘#$tg’ 를 모든 노트에서 지울까요? 본문에 쓴 #태그는 글에서도 지워집니다.", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                    else OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("새 이름 (이미 있는 이름이면 합쳐짐)") }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                if (confirmDelete) TextButton(onClick = { tagDialog = null; model.retagAll(tg, null) }) { Text("지우기") }
                else TextButton(enabled = name.isNotBlank() && name.trim().trimStart('#') != tg, onClick = { tagDialog = null; model.retagAll(tg, name) }) { Text("이름 바꾸기") }
            },
            dismissButton = {
                Row {
                    if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("삭제") }
                    TextButton(onClick = { tagDialog = null }) { Text("취소") }
                }
            }
        )
    }
    model.shared?.let { sh ->
        if (model.tree == null) {
            AlertDialog(onDismissRequest = { model.shared = null }, text = { Text("먼저 노트 폴더를 골라 주세요.") }, confirmButton = { TextButton(onClick = { model.shared = null }) { Text("닫기") } })
        } else {
            val first = sh.first.ifBlank { sh.second.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }.trim().take(60)
            var title by remember(sh) { mutableStateOf(first) }
            var folder by remember(sh) { mutableStateOf(model.folder ?: "") }
            AlertDialog(
                onDismissRequest = { model.shared = null }, title = { Text("공유받은 글을 저장") },
                text = {
                    Column {
                        OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("제목") })
                        Text(sh.second.take(200), fontSize = 12.sp, maxLines = 4, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                        MenuButton("노트북: " + (folder.ifEmpty { "(맨 위)" }.substringAfterLast('/'))) { close ->
                            DropdownMenuItem(text = { Text("(맨 위)") }, onClick = { folder = ""; close() })
                            for (f in model.folders) DropdownMenuItem(text = { Text("  ".repeat(f.count { it == '/' }) + f.substringAfterLast('/')) }, onClick = { folder = f; close() })
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { model.saveShared(title, sh.second, folder) }) { Text("저장") } },
                dismissButton = { TextButton(onClick = { model.shared = null }) { Text("취소") } }
            )
        }
    }
    if (notebookDialog) {
        var name by remember { mutableStateOf("") }
        var parent by remember { mutableStateOf(model.folder ?: "") }
        AlertDialog(
            onDismissRequest = { notebookDialog = false }, title = { Text("새 노트북") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("노트북 이름") })
                    MenuButton("위치: " + (if (parent.isEmpty()) "(맨 위)" else parent.substringAfterLast('/'))) { close ->
                        DropdownMenuItem(text = { Text("(맨 위)") }, onClick = { parent = ""; close() })
                        for (f in model.folders) DropdownMenuItem(text = { Text("  ".repeat(f.count { it == '/' }) + f.substringAfterLast('/')) }, onClick = { parent = f; close() })
                    }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { notebookDialog = false; model.createNotebook(parent, name); scope.launch { drawer.close() } }) { Text("만들기") } },
            dismissButton = { TextButton(onClick = { notebookDialog = false }) { Text("취소") } }
        )
    }
    notebookMenu?.let { nb ->
        AlertDialog(
            onDismissRequest = { notebookMenu = null }, title = { Text(nb.substringAfterLast('/')) },
            text = { Text("노트 ${model.folderCounts[nb] ?: 0}개가 든 이 노트북(하위 노트북 포함)을 통째로 휴지통으로 보낼까요? 컴퓨터의 iamtalkernote 에서 복원할 수 있습니다.") },
            confirmButton = { TextButton(onClick = { notebookMenu = null; model.deleteNotebook(nb) }) { Text("휴지통으로") } },
            dismissButton = { TextButton(onClick = { notebookMenu = null }) { Text("취소") } }
        )
    }
    model.message?.let { m ->
        AlertDialog(onDismissRequest = { model.message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { model.message = null }) { Text("닫기") } })
    }
    deleteSaved?.let { sv ->
        AlertDialog(
            onDismissRequest = { deleteSaved = null }, title = { Text("저장된 검색 삭제") }, text = { Text("‘${sv.name}’ 을 지울까요?") },
            confirmButton = { TextButton(onClick = { model.deleteSaved(sv); deleteSaved = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleteSaved = null }) { Text("취소") } }
        )
    }
    if (saveDialog) {
        var name by remember { mutableStateOf(model.query.trim().take(20)) }
        AlertDialog(
            onDismissRequest = { saveDialog = false }, title = { Text("검색 저장") },
            text = { Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("이름") })
                Text("검색식: " + model.query.ifBlank { "(비어 있음)" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton(enabled = model.query.isNotBlank(), onClick = { saveDialog = false; model.saveSearch(name) }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("취소") } }
        )
    }
    when (batchDialog) {
        "move" -> AlertDialog(
            onDismissRequest = { batchDialog = "" }, title = { Text("${model.selected.size}개를 이동") },
            text = {
                LazyColumn {
                    items(listOf("") + model.folders) { f ->
                        Text(if (f.isEmpty()) "(맨 위)" else "  ".repeat(f.count { it == '/' }) + f.substringAfterLast('/'),
                            modifier = Modifier.fillMaxWidth().clickable { batchDialog = ""; model.batch("이동") { n -> model.moveNote(n, f) } }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { batchDialog = "" }) { Text("취소") } }
        )
        "tag" -> {
            var input by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { batchDialog = "" }, title = { Text("${model.selected.size}개에 태그 추가") },
                text = { OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("공백·쉼표로 나눠 쓰기") }) },
                confirmButton = {
                    TextButton(onClick = {
                        batchDialog = ""
                        val tags = input.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
                        if (tags.isNotEmpty()) model.batch("태그 추가") { n -> model.addTagsTo(n, tags) }
                    }) { Text("추가") }
                },
                dismissButton = { TextButton(onClick = { batchDialog = "" }) { Text("취소") } }
            )
        }
        "trash" -> AlertDialog(
            onDismissRequest = { batchDialog = "" }, title = { Text("${model.selected.size}개를 휴지통으로?") },
            text = { Text("컴퓨터의 iamtalkernote 에서 복원할 수 있습니다.") },
            confirmButton = { TextButton(onClick = { batchDialog = ""; model.batch("휴지통") { n -> model.trashNote(n) } }) { Text("휴지통으로") } },
            dismissButton = { TextButton(onClick = { batchDialog = "" }) { Text("취소") } }
        )
    }
}
