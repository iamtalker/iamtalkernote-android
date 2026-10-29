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
import androidx.compose.foundation.clickable
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFB9A6FF)) else lightColorScheme(primary = Color(0xFF6D4AFF))) {
                Surface(Modifier.fillMaxSize()) { App(model) }
            }
        }
    }
}

/** 눌러서 고르는 목록 단추 */
@Composable
fun MenuButton(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    if (screen == "trash") { TrashScreen(model, onBack = { screen = ""; model.refresh() }); return }
    if (screen == "journal") { JournalScreen(model, onBack = { screen = "" }, onOpen = { screen = ""; open = it }); return }
    val cur = open
    if (cur != null) {
        androidx.compose.runtime.key(cur.uri) {
            Editor(cur, model, onClose = { open = null; model.refresh() }, onOpenTitle = { title ->
                val n = NoteText.findByTitle(model.notes, title) { it.title }
                if (n != null) open = n else android.widget.Toast.makeText(ctx, "‘$title’ 노트를 찾지 못했습니다", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
        return
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var newDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = model.tree != null,
        drawerContent = {
            ModalDrawerSheet {
                LazyColumn {
                    item {
                        Text("iamtalkernote", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp))
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("모든 노트") }, selected = model.folder == null && model.tag == null,
                            badge = { Text("${model.notes.size}") },
                            onClick = { model.changeFolder(null); model.changeTag(null); scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    item { Text("노트북", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp)) }
                    items(model.folders, key = { "f:$it" }) { f ->
                        val depth = f.count { it == '/' }
                        NavigationDrawerItem(
                            label = { Text(f.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = model.folder == f, badge = { Text("${model.folderCounts[f] ?: 0}") },
                            onClick = { model.changeFolder(f); model.changeTag(null); scope.launch { drawer.close() } },
                            modifier = Modifier.padding(start = (12 + depth * 16).dp, end = 12.dp)
                        )
                    }
                    if (model.tagCounts.isNotEmpty()) {
                        item { Text("태그", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp)) }
                        items(model.tagCounts, key = { "t:" + it.first }) { (t, n) ->
                            NavigationDrawerItem(
                                label = { Text("#$t", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                selected = model.tag.equals(t, ignoreCase = true), badge = { Text("$n") },
                                onClick = { model.changeFolder(null); model.changeTag(t); scope.launch { drawer.close() } },
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { if (model.tree != null) TextButton(onClick = { scope.launch { drawer.open() } }) { Text("☰", fontSize = 20.sp) } },
                    title = {
                        Text(model.tag?.let { "#$it" } ?: model.folder?.substringAfterLast('/') ?: "모든 노트", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    actions = {
                        if (model.tree != null) {
                            MenuButton("정렬") { close ->
                                for (s in Sort.values()) DropdownMenuItem(
                                    text = { Text((if (s == model.sort) "✓ " else "    ") + s.label) }, onClick = { model.changeSort(s); close() })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("휴지통") }, onClick = { screen = "trash"; close() })
                                DropdownMenuItem(text = { Text("전체 변경 이력") }, onClick = { screen = "journal"; close() })
                                DropdownMenuItem(text = { Text("새로고침") }, onClick = { model.refresh(); close() })
                                DropdownMenuItem(text = { Text("다른 폴더 고르기") }, onClick = { picker.launch(null); close() })
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                if (model.tree != null) FloatingActionButton(onClick = { newDialog = true }) { Text("+", fontSize = 24.sp) }
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
                OutlinedTextField(
                    value = model.query, onValueChange = { model.changeQuery(it) }, singleLine = true,
                    placeholder = { Text("검색  (공백=AND  \"구절\"  -제외  #태그)") },
                    trailingIcon = { if (model.query.isNotEmpty()) TextButton(onClick = { model.changeQuery("") }) { Text("✕") } },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Row(Modifier.padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = model.inBody, onCheckedChange = { model.changeInBody(it) })
                    Text("본문까지", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(model.status + if (model.shown.size != model.notes.size) " · 검색 ${model.shown.size}개" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val fmt = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(model.shown, key = { it.uri.toString() }) { n ->
                        Card(
                            onClick = { open = n }, modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text((if (n.uri.toString() in model.pins) "★ " else "") + n.title, fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (n.snip.isNotEmpty()) Text(n.snip, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), modifier = Modifier.padding(top = 2.dp))
                                Text(
                                    (if (n.folder.isEmpty()) "" else n.folder + " · ") + fmt.format(Date(n.date)) +
                                        (if (n.tags.isEmpty()) "" else "  " + n.tags.take(4).joinToString(" ") { "#$it" }),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }
    if (newDialog) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDialog = false },
            title = { Text("새 노트" + (model.folder?.let { " — " + it.substringAfterLast('/') } ?: "")) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, placeholder = { Text("제목") }) },
            confirmButton = {
                TextButton(onClick = {
                    newDialog = false
                    model.create(title)?.let { open = it }
                }) { Text("만들기") }
            },
            dismissButton = { TextButton(onClick = { newDialog = false }) { Text("취소") } }
        )
    }
}
