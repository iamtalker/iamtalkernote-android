package kr.iamtalker.notes

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: NotesModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6D4AFF))) {
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

    val cur = open
    if (cur != null) {
        Editor(cur, onClose = { open = null; model.refresh() })
        return
    }
    var newDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("iamtalkernote") },
                actions = {
                    if (model.tree != null) {
                        TextButton(onClick = { model.refresh() }) { Text("새로고침") }
                        TextButton(onClick = { picker.launch(null) }) { Text("폴더") }
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MenuButton(model.folder?.substringAfterLast('/') ?: "모든 노트북", Modifier.weight(1f, fill = false)) { close ->
                    DropdownMenuItem(text = { Text("모든 노트북") }, onClick = { model.changeFolder(null); close() })
                    for (f in model.folders) DropdownMenuItem(
                        text = { Text("  ".repeat(f.count { it == '/' }) + f.substringAfterLast('/')) },
                        onClick = { model.changeFolder(f); close() })
                }
                MenuButton(model.tag?.let { "#$it" } ?: "태그", Modifier.weight(1f, fill = false)) { close ->
                    DropdownMenuItem(text = { Text("모든 태그") }, onClick = { model.changeTag(null); close() })
                    for ((t, n) in model.tagCounts) DropdownMenuItem(text = { Text("#$t  ($n)") }, onClick = { model.changeTag(t); close() })
                }
                MenuButton(model.sort.label, Modifier.weight(1f, fill = false)) { close ->
                    for (s in Sort.values()) DropdownMenuItem(text = { Text(s.label) }, onClick = { model.changeSort(s); close() })
                }
            }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = model.inBody, onCheckedChange = { model.changeInBody(it) })
                Text("본문까지", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(model.status + if (model.shown.size != model.notes.size) " · 검색 ${model.shown.size}개" else "",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
            }
            if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val fmt = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(model.shown, key = { it.uri.toString() }) { n ->
                    Column(Modifier.fillMaxWidth().clickable { open = n }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(n.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (n.folder.isEmpty()) "" else n.folder + " · ") + fmt.format(Date(n.date)) +
                                (if (n.tags.isEmpty()) "" else "  " + n.tags.take(4).joinToString(" ") { "#$it" }),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(note: Note, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(note.size == 0L) }   // 새 노트는 바로 편집, 기존 노트는 보기부터
    LaunchedEffect(note.uri) {
        val t = withContext(Dispatchers.IO) { NoteRepo.read(ctx, note.uri) }
        text = t; saved = t
    }
    fun save() {
        val t = text ?: return
        if (t != saved) {
            NoteRepo.write(ctx, note.uri, t)
            saved = t
        }
    }
    BackHandler { save(); onClose() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onClick = { save(); onClose() }) { Text("←") } },
            actions = {
                TextButton(onClick = { if (editing) save(); editing = !editing }) { Text(if (editing) "보기" else "편집") }
                if (editing) TextButton(onClick = { save() }) { Text("저장") }
            }
        )
    }) { pad ->
        val t = text
        if (t == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (editing) {
            BasicTextField(
                value = t, onValueChange = { text = it },
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(pad).fillMaxSize().padding(16.dp).imePadding()
            )
        } else {
            MarkdownView(t, Modifier.padding(pad).fillMaxSize())
        }
    }
}
