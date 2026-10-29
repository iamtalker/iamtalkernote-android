package kr.iamtalker.notes

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Editor(start: Note, model: NotesModel, onClose: () -> Unit, onOpenTitle: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf(start) }
    var tfv by remember { mutableStateOf<TextFieldValue?>(null) }
    var saved by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(start.size == 0L) }   // 새 노트는 바로 편집, 기존 노트는 보기부터
    var dialog by remember { mutableStateOf("") }                   // rename / move / delete / props / tags
    var error by remember { mutableStateOf<String?>(null) }
    var findOpen by remember { mutableStateOf(false) }
    var findQ by remember { mutableStateOf("") }
    var findIdx by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            try {
                val path = withContext(Dispatchers.IO) { model.attachImage(uri, note) }
                val v = tfv ?: return@launch
                val at = v.selection.end.coerceIn(0, v.text.length)
                val ins = "\n![](" + path + ")\n"
                editing = true
                tfv = TextFieldValue(v.text.substring(0, at) + ins + v.text.substring(at), TextRange(at + ins.length))
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    LaunchedEffect(start.uri) {
        val t = withContext(Dispatchers.IO) { NoteRepo.read(ctx, start.uri) }
        tfv = TextFieldValue(t); saved = t
    }

    /** 바뀐 글이 있으면 파일에 쓴다(머리말에 updated 가 있으면 지금 시각으로, 데스크톱과 같게) */
    var firstSave by remember { mutableStateOf(true) }   // 노트를 연 뒤 처음 고칠 때는 그 직전 내용을 꼭 버전으로 남긴다(데스크톱과 같게)
    fun persist(t: String) {
        if (t == saved) return
        val old = saved
        val force = firstSave
        val n = note
        NoteRepo.write(ctx, n.uri, NoteText.touchUpdated(t, System.currentTimeMillis()))
        saved = t
        firstSave = false
        val tree = model.tree ?: return
        model.bg {
            History.snapshot(ctx, tree, History.relOf(n), old, t, force)
            History.journal(ctx, tree, "edit", History.relOf(n))
        }
    }
    fun save() { tfv?.let { persist(it.text) } }

    // 자동 저장: 입력을 멈추고 0.6초 뒤(데스크톱과 같음)
    LaunchedEffect(tfv?.text) {
        val t = tfv?.text ?: return@LaunchedEffect
        if (t == saved) return@LaunchedEffect
        delay(600)
        withContext(Dispatchers.IO) { persist(t) }
    }
    LaunchedEffect(editing) { if (editing && tfv != null) runCatching { focus.requestFocus() } }

    /** 노트를 바꾸는 일은 파일 시스템을 만지므로 백그라운드에서 하고, 실패하면 이유를 보여 준다(노트는 그대로) */
    fun act(work: () -> Note?) {
        save()
        scope.launch {
            try {
                val n = withContext(Dispatchers.IO) { work() }
                if (n == null) onClose() else note = n
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }
    fun apply(e: NoteText.Edit) { tfv = TextFieldValue(e.text, TextRange(e.start, e.end)) }
    fun format(f: (String, Int, Int) -> NoteText.Edit) {
        val v = tfv ?: return
        editing = true
        apply(f(v.text, v.selection.start, v.selection.end))
    }
    fun goFind(delta: Int) {
        val v = tfv ?: return
        val hits = NoteText.findAll(v.text, findQ)
        if (hits.isEmpty()) return
        findIdx = ((findIdx + delta) % hits.size + hits.size) % hits.size
        editing = true
        tfv = v.copy(selection = TextRange(hits[findIdx], hits[findIdx] + findQ.length))
        runCatching { focus.requestFocus() }
    }

    BackHandler { save(); onClose() }
    val pinned = note.uri.toString() in model.pins
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = { save(); onClose() }) { Text("←", fontSize = 20.sp) } },
                actions = {
                    TextButton(onClick = { save(); editing = !editing }) { Text(if (editing) "보기" else "편집") }
                    MenuButton("⋮") { close ->
                        DropdownMenuItem(text = { Text("문서에서 찾기") }, onClick = { close(); findOpen = true })
                        DropdownMenuItem(text = { Text("태그 편집") }, onClick = { close(); dialog = "tags" })
                        DropdownMenuItem(text = { Text("이전 버전") }, onClick = { close(); dialog = "versions" })
                        DropdownMenuItem(text = { Text("속성") }, onClick = { close(); dialog = "props" })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(if (pinned) "고정 해제" else "맨 위에 고정") }, onClick = { model.togglePin(note); close() })
                        DropdownMenuItem(text = { Text("공유") }, onClick = {
                            close()
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, note.title).putExtra(Intent.EXTRA_TEXT, tfv?.text ?: "")
                            ctx.startActivity(Intent.createChooser(send, "공유"))
                        })
                        DropdownMenuItem(text = { Text("이름 바꾸기") }, onClick = { close(); dialog = "rename" })
                        DropdownMenuItem(text = { Text("노트북으로 이동") }, onClick = { close(); dialog = "move" })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("휴지통으로") }, onClick = { close(); dialog = "delete" })
                    }
                }
            )
        },
        bottomBar = {
            if (editing && tfv != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { format { t, s, e -> NoteText.wrap(t, s, e, "**") } }) { Text("B") }
                        TextButton(onClick = { format { t, s, e -> NoteText.wrap(t, s, e, "*") } }) { Text("I") }
                        TextButton(onClick = { format { t, s, e -> NoteText.wrap(t, s, e, "~~") } }) { Text("S") }
                        TextButton(onClick = { format { t, s, e -> NoteText.wrap(t, s, e, "`") } }) { Text("코드") }
                        TextButton(onClick = { format { t, s, e -> NoteText.linePrefix(t, s, e, "## ", heading = true) } }) { Text("제목") }
                        TextButton(onClick = { format { t, s, e -> NoteText.linePrefix(t, s, e, "- ") } }) { Text("•목록") }
                        TextButton(onClick = { format { t, s, e -> NoteText.linePrefix(t, s, e, "- [ ] ") } }) { Text("☐체크") }
                        TextButton(onClick = { format { t, s, e -> NoteText.linePrefix(t, s, e, "> ") } }) { Text("인용") }
                        TextButton(onClick = { format { t, s, e -> NoteText.wrap(t, s, e, "[", "]()") } }) { Text("링크") }
                        TextButton(onClick = { format { t, s, e -> NoteText.wrap(t, s, e, "[[", "]]") } }) { Text("[[노트]]") }
                        TextButton(onClick = { pickImage.launch("image/*") }) { Text("그림") }
                    }
                }
            }
        }
    ) { pad ->
        val v = tfv
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (findOpen) {
                val hits = if (v == null) emptyList() else NoteText.findAll(v.text, findQ)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = findQ, onValueChange = { findQ = it; findIdx = -1 }, singleLine = true,
                        placeholder = { Text("찾기") }, modifier = Modifier.weight(1f)
                    )
                    Text(if (findQ.isEmpty()) "" else if (hits.isEmpty()) " 0" else " ${findIdx.coerceAtLeast(0) + 1}/${hits.size}", fontSize = 12.sp)
                    TextButton(onClick = { goFind(-1) }) { Text("▲") }
                    TextButton(onClick = { goFind(1) }) { Text("▼") }
                    TextButton(onClick = { findOpen = false; findQ = "" }) { Text("×") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (v == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (editing) {
                    BasicTextField(
                        value = v, onValueChange = { tfv = it },
                        textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxSize().padding(16.dp).focusRequester(focus)
                    )
                } else {
                    MarkdownView(
                        v.text, Modifier.fillMaxSize(),
                        loadImage = { src ->
                            withContext(Dispatchers.IO) {
                                if (src.startsWith("http") || src.startsWith(":/")) null
                                else NoteRepo.resolve(ctx, model.tree!!, note.folder, src)?.let { NoteRepo.loadBitmap(ctx, it)?.asImageBitmap() }
                            }
                        },
                        onToggle = { line ->
                            val n = toggleCheckbox(v.text, line)
                            tfv = TextFieldValue(n, v.selection)
                            runCatching { persist(n) }
                        },
                        onNoteLink = { title -> save(); onOpenTitle(title) }
                    )
                }
            }
        }
    }
    error?.let { msg ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("할 수 없습니다") }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("닫기") } })
    }
    when (dialog) {
        "rename" -> {
            var name by remember { mutableStateOf(note.title) }
            AlertDialog(
                onDismissRequest = { dialog = "" }, title = { Text("이름 바꾸기") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
                confirmButton = { TextButton(onClick = { dialog = ""; act { model.renameNote(note, name) } }) { Text("바꾸기") } },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("취소") } }
            )
        }
        "tags" -> {
            val cur = tfv?.text ?: ""
            var input by remember { mutableStateOf(NoteText.tagLineTags(cur).joinToString(" ")) }
            AlertDialog(
                onDismissRequest = { dialog = "" }, title = { Text("태그 편집") },
                text = {
                    Column {
                        OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("공백·쉼표로 나눠 쓰기") })
                        Text("글 맨 아래 ‘태그: #a #b’ 줄에 적힙니다. 본문에 직접 쓴 #태그나 머리말 tags 는 여기서 지울 수 없습니다.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = ""
                        val list = input.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
                        tfv = TextFieldValue(NoteText.setTagLine(cur, list))
                    }) { Text("저장") }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("취소") } }
            )
        }
        "props" -> {
            val t = tfv?.text ?: ""
            val fmt = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA)
            val info = Meta.parse(t)
            val fm = Meta.split(t).first
            val created = Regex("(?m)^created:[ \\t]*(.*)$").find(fm)?.groupValues?.get(1)?.let { Meta.parseDate(it) } ?: 0L
            val url = Regex("(?m)^source:[ \\t]*(\\S+)").find(fm)?.groupValues?.get(1)
                ?: Regex("(?m)^원본:\\s*<?(https?://[^\\s>]+)").find(t.take(2000))?.groupValues?.get(1) ?: ""
            fun d(ms: Long) = if (ms > 0) fmt.format(Date(ms)) else "-"
            AlertDialog(
                onDismissRequest = { dialog = "" }, title = { Text("속성") },
                text = {
                    Column {
                        Text("위치: " + (note.folder.ifEmpty { "(맨 위)" }))
                        Text("파일: ${note.name}")
                        Text("갱신: " + d(if (info.updated > 0) info.updated else note.modified))
                        Text("생성: " + d(created))
                        if (url.isNotEmpty()) Text("주소: $url")
                        Text("태그: " + (if (info.tags.isEmpty()) "-" else info.tags.joinToString(" ") { "#$it" }))
                        Text("크기: %,d자".format(t.length))
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = "" }) { Text("닫기") } }
            )
        }
        "versions" -> {
            val tree = model.tree!!
            var vs by remember { mutableStateOf<List<History.Version>?>(null) }
            var pick by remember { mutableStateOf<History.Version?>(null) }
            var preview by remember { mutableStateOf("") }
            val fmt = remember { SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA) }
            LaunchedEffect(note.uri) {
                vs = withContext(Dispatchers.IO) { runCatching { History.versions(ctx, tree, History.relOf(note)) }.getOrDefault(emptyList()) }
            }
            LaunchedEffect(pick) {
                val p = pick ?: return@LaunchedEffect
                preview = withContext(Dispatchers.IO) { runCatching { History.readVersion(ctx, tree, p) }.getOrDefault("(읽지 못했습니다)") }
            }
            AlertDialog(
                onDismissRequest = { dialog = "" }, title = { Text("이전 버전") },
                text = {
                    val list = vs
                    if (list == null) CircularProgressIndicator()
                    else if (list.isEmpty()) Text("저장된 이전 버전이 없습니다.")
                    else Column {
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(list) { v ->
                                Text(fmt.format(Date(v.ms)) + "   %,dB".format(v.size),
                                    color = if (v == pick) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth().clickable { pick = v }.padding(vertical = 8.dp))
                            }
                        }
                        if (pick != null) Text(preview.take(300), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 8,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = {
                    TextButton(enabled = pick != null, onClick = {
                        val p = pick ?: return@TextButton
                        dialog = ""
                        scope.launch {
                            val old = withContext(Dispatchers.IO) { runCatching { History.readVersion(ctx, tree, p) }.getOrNull() }
                            if (old == null) { error = "버전을 읽지 못했습니다"; return@launch }
                            save()
                            // 지금 내용은 새 버전으로 남기고(다시 돌아올 수 있게), 고른 버전으로 바꾼다
                            val cur = tfv?.text ?: ""
                            withContext(Dispatchers.IO) { runCatching { History.snapshot(ctx, tree, History.relOf(note), cur, old, true) } }
                            tfv = TextFieldValue(old)
                            firstSave = false
                        }
                    }) { Text("이 버전으로 되돌리기") }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("닫기") } }
            )
        }
        "move" -> AlertDialog(
            onDismissRequest = { dialog = "" }, title = { Text("노트북으로 이동") },
            text = {
                LazyColumn {
                    items(listOf("") + model.folders) { f ->
                        Text(if (f.isEmpty()) "(맨 위)" else "  ".repeat(f.count { it == '/' }) + f.substringAfterLast('/'),
                            modifier = Modifier.fillMaxWidth().clickable { dialog = ""; act { model.moveNote(note, f) } }.padding(vertical = 10.dp),
                            color = if (f == note.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("취소") } }
        )
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = "" }, title = { Text("휴지통으로 보낼까요?") },
            text = { Text("‘${note.title}’ 을 휴지통(.trash)으로 옮깁니다. 컴퓨터의 iamtalkernote 에서 복원할 수 있습니다.") },
            confirmButton = { TextButton(onClick = { dialog = ""; act { model.trashNote(note); null } }) { Text("휴지통으로") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("취소") } }
        )
    }
}
