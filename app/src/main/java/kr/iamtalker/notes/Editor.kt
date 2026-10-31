package kr.iamtalker.notes

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
    var viewing by remember { mutableStateOf<String?>(null) }   // 크게 보는 그림
    var saved by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(start.size == 0L || model.openEditing) }   // 새 노트는 바로 편집, 기존 노트는 보기부터
    var dialog by remember { mutableStateOf("") }                   // rename / move / delete / props / tags
    var error by remember { mutableStateOf<String?>(null) }
    val isOutl = note.folder == "아웃라이너" || note.folder.startsWith("아웃라이너/")   // PC 와 같게: 아웃라이너 폴더의 노트만
    var rawEdit by remember { mutableStateOf(false) }                // 아웃라이너 노트를 항목 편집기 대신 원문으로 고치기
    var sheet by remember { mutableStateOf(false) }                 // ⋮ 메뉴(아래에서 올라오는 시트)
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
        // 목록에는 있는데 파일은 이미 없어진 노트(동기화·삭제 직후)를 열어도 앱이 꺼지지 않게: 안내하고 목록으로 돌아간다
        val t = withContext(Dispatchers.IO) { runCatching { NoteRepo.read(ctx, start.uri) } }.getOrElse { e ->
            android.util.Log.w("iat", "노트를 읽지 못함", e)
            android.widget.Toast.makeText(ctx, "이 노트는 이미 없어졌습니다. 목록을 새로 고칩니다", android.widget.Toast.LENGTH_LONG).show()
            model.forget(start); model.refresh(); onClose()
            return@LaunchedEffect
        }
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
    // 조플린처럼 제목은 본문 위의 입력칸. 새 노트는 비워 두고(파일 이름은 '제목 없음'), 쓰고 나면 그 이름으로 바꾼다
    var titleText by remember { mutableStateOf(if (start.size == 0L) "" else start.title) }
    fun commitTitle() {
        var t = titleText.trim()
        if (t.isEmpty() && start.size == 0L && note.title.startsWith("제목 없음")) t = NoteText.titleFromBody(tfv?.text ?: "")   // 제목 없이 본문만 썼다 → 첫 줄이 제목
        if (t.isEmpty() || t == note.title) return
        runCatching { note = model.renameNote(note, t) }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    fun save() { tfv?.let { persist(it.text) }; commitTitle() }

    // 자동 저장: 입력을 멈추고 0.6초 뒤(데스크톱과 같음)
    LaunchedEffect(tfv?.text) {
        val t = tfv?.text ?: return@LaunchedEffect
        if (t == saved) return@LaunchedEffect
        delay(600)
        withContext(Dispatchers.IO) { persist(t) }
    }
    LaunchedEffect(editing, tfv != null) { if (editing && tfv != null) { kotlinx.coroutines.delay(60); runCatching { focus.requestFocus() } } }   // 글을 다 읽은 뒤(칸이 생긴 뒤)에도 한 번 더 — 새 노트를 열면 바로 키보드가 뜬다

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

    /** 나가기: 저장하고, 새로 만든 뒤 제목도 본문도 쓰지 않았으면 그 빈 노트를 지운다(실수로 연필을 눌러도 '제목 없음' 이 쌓이지 않게) */
    fun leave() {
        val empty = start.size == 0L && titleText.isBlank() && (tfv?.text ?: "").isBlank() && (saved).isBlank()
        if (empty) {
            runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, note.uri) }.onFailure { android.util.Log.w("iat", "빈 노트 지우기 실패", it) }
            model.forget(note)                  // 목록에서도 바로 뺀다(남아 있으면 눌렀을 때 없는 파일을 열게 된다)
            model.refresh()
        } else save()
        onClose()
    }
    BackHandler { leave() }
    fun share() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, note.title).putExtra(Intent.EXTRA_TEXT, tfv?.text ?: "")
        ctx.startActivity(Intent.createChooser(send, "공유"))
    }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusMgr = androidx.compose.ui.platform.LocalFocusManager.current
    val pinned = note.uri.toString() in model.pins
    val isFav = Favorites.relOf(note) in model.favs
    Scaffold(
        topBar = {
            Column {
                Surface(color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        val fg = MaterialTheme.colorScheme.onSurface
                        if (editing) TextButton(onClick = { save(); editing = false; keyboard?.hide(); focusMgr.clearFocus() }) {
                            Text("완료", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        } else BarButton({ leave() }) { BackIcon(fg) }
                        val untitled = start.size == 0L && titleText.isBlank() && note.title.startsWith("제목 없음")
                        Text(if (untitled) "" else note.title, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = fg,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp).clickable { dialog = "rename" })
                        if (!editing) BarButton({ editing = true }) { PencilIcon(fg) }
                        BarButton({ share() }) { ShareIcon(fg) }
                        BarButton({ dialog = "props" }) { InfoIcon(fg) }
                        BarButton({ sheet = true }) { DotsIcon(fg) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            if (editing && tfv != null && !(isOutl && !rawEdit)) {
                Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                            TBtn({ format { t, s, e -> NoteText.linePrefix(t, s, e, "- [ ] ") } }) { CheckBoxIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
                            TBtn({ pickImage.launch("image/*") }) { ImageIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
                            TBtn({ format { t, s, e -> NoteText.linePrefix(t, s, e, "## ", heading = true) } }) { HeadingIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
                            TBtn({ format { t, s, e -> NoteText.linePrefix(t, s, e, "- ") } }) { ListIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
                            TBtn({ format { t, s, e -> ListEnter.shift(t, s, e, true) } }) { IndentIcon(true, MaterialTheme.colorScheme.onSurfaceVariant) }
                            TBtn({ format { t, s, e -> ListEnter.shift(t, s, e, false) } }) { IndentIcon(false, MaterialTheme.colorScheme.onSurfaceVariant) }
                            TBtn({ format { t, s, e -> NoteText.wrap(t, s, e, "**") } }) { TGlyph("B", bold = true) }
                            TBtn({ format { t, s, e -> NoteText.wrap(t, s, e, "*") } }) { TGlyph("I", italic = true) }
                            TBtn({ format { t, s, e -> NoteText.wrap(t, s, e, "~~") } }) { TGlyph("S", strike = true) }
                            TBtn({ format { t, s, e -> NoteText.wrap(t, s, e, "`") } }) { CodeIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
                            TBtn({ format { t, s, e -> NoteText.linePrefix(t, s, e, "> ") } }) { QuoteIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
                            TBtn({ format { t, s, e -> NoteText.wrap(t, s, e, "[", "]()") } }) { LinkIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
                            TBtn({ format { t, s, e -> NoteText.wrap(t, s, e, "[[", "]]") } }) { WikiLinkIcon(MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        TBtn({ keyboard?.hide(); focusMgr.clearFocus() }) { ChevronIcon(true, MaterialTheme.colorScheme.primary) }
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
                    BarButton({ goFind(-1) }) { ChevronIcon(false, MaterialTheme.colorScheme.onSurface) }
                    BarButton({ goFind(1) }) { ChevronIcon(true, MaterialTheme.colorScheme.onSurface) }
                    BarButton({ findOpen = false; findQ = "" }) { CloseIcon(MaterialTheme.colorScheme.onSurface) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (v == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (editing) {
                    if (isOutl && !rawEdit) OutlineEditor(v.text, model.editorFont, { t -> tfv = TextFieldValue(t, TextRange(t.length)) }, Modifier.fillMaxSize())
                    else BasicTextField(
                        value = v, onValueChange = { n -> tfv = ListEnter.apply(v, n) },   // Enter 로 목록 이어가기
                        textStyle = TextStyle(fontSize = model.editorFont.sp, lineHeight = (model.editorFont * 1.5f).sp, color = MaterialTheme.colorScheme.onSurface),
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
                        onNoteLink = { title -> save(); onOpenTitle(title) },
                        cacheKey = note.uri.toString(),
                        outliner = isOutl,
                        onImage = { src -> if (!(src.startsWith("http") || src.startsWith(":/"))) viewing = src }
                    )
                    viewing?.let { src ->
                        val u = remember(src) { NoteRepo.resolve(ctx, model.tree!!, note.folder, src) }
                        if (u != null) ImageViewer(u, Uri.decode(src.substringBefore('?').substringAfterLast('/')), onClose = { viewing = null }) else viewing = null
                    }
                }
            }
        }
    }

    if (sheet) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                @Composable fun item(label: String, red: Boolean = false, onClick: () -> Unit) {
                    Text(label, fontSize = 16.sp, color = if (red) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { sheet = false; onClick() }.padding(horizontal = 24.dp, vertical = 14.dp))
                }
                item("태그") { dialog = "tags" }
                item("문서에서 찾기") { findOpen = true }
                item("이전 버전") { dialog = "versions" }
                if (isOutl) item(if (rawEdit) "✓ 원문으로 편집" else "원문으로 편집") { rawEdit = !rawEdit; editing = true }
                item(if (isFav) "즐겨찾기 해제" else "즐겨찾기 추가") { model.toggleFavorite(note) }
                item(if (pinned) "고정 해제" else "맨 위에 고정") { model.togglePin(note) }
                item("이름 바꾸기") { dialog = "rename" }
                item("노트북으로 이동") { dialog = "move" }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                item("삭제", red = true) { dialog = "delete" }
            }
        }
    }
    error?.let { msg ->
        ErrorDialog(msg, "할 수 없습니다") { error = null }
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


@Composable
private fun TBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.width(46.dp).height(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun TGlyph(t: String, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false) {
    Text(t, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
        textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
}
