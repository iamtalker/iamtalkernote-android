package kr.iamtalker.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 휴지통 화면: 데스크톱과 같은 .trash 폴더의 노트를 보고 복원·완전 삭제한다 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(model: NotesModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tree = model.tree!!
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<History.TrashItem>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA) }
    fun load() {
        scope.launch {
            items = withContext(Dispatchers.IO) { runCatching { History.trashItems(ctx, tree) }.getOrDefault(emptyList()) }
        }
    }
    LaunchedEffect(Unit) { load() }
    fun run(work: () -> String?) {
        scope.launch {
            try {
                val m = withContext(Dispatchers.IO) { work() }
                if (m != null) message = m
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            }
            load()
        }
    }
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("휴지통") },
            navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 20.sp) } },
            actions = {
                MenuButton("⋮") { close ->
                    DropdownMenuItem(text = { Text("30일 지난 것 정리") }, onClick = { close(); confirm = "old" })
                    DropdownMenuItem(text = { Text("휴지통 비우기") }, onClick = { close(); confirm = "all" })
                }
            }
        )
    }) { pad ->
        val list = items
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (list == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (list.isEmpty()) Text("휴지통이 비어 있습니다.", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline)
            else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { it ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(it.rel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("지운 날 " + fmt.format(Date((it.deleted * 1000).toLong())) + if (it.isNote) "" else "  · 컴퓨터에서 복원하세요",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            if (it.isNote) TextButton(onClick = { run { "복원했습니다: " + History.restore(ctx, tree, it).also { rel -> History.journal(ctx, tree, "restore", rel, detail = "휴지통에서") } } }) { Text("복원") }
                            TextButton(onClick = { run { History.purge(ctx, tree, it); null } }) { Text("완전 삭제") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text("닫기") } })
    }
    if (confirm.isNotEmpty()) {
        val old = confirm == "old"
        AlertDialog(
            onDismissRequest = { confirm = "" },
            title = { Text(if (old) "30일 지난 것을 지울까요?" else "휴지통을 비울까요?") },
            text = { Text("지운 노트는 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = ""
                    val limit = System.currentTimeMillis() / 1000.0 - 30 * 86400
                    val targets = (items ?: emptyList()).filter { !old || it.deleted < limit }
                    run { for (t in targets) runCatching { History.purge(ctx, tree, t) }; "${targets.size}개를 지웠습니다" }
                }) { Text("지우기") }
            },
            dismissButton = { TextButton(onClick = { confirm = "" }) { Text("취소") } }
        )
    }
}

/** 전체 변경 이력 화면: 모든 노트의 수정·생성·삭제·이름 바꿈·이동·복원을 시간순으로 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(model: NotesModel, onBack: () -> Unit, onOpen: (Note) -> Unit) {
    val ctx = LocalContext.current
    val tree = model.tree!!
    var recs by remember { mutableStateOf<List<History.Rec>?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA) }
    val names = mapOf("edit" to "수정", "create" to "새 노트", "delete" to "삭제", "restore" to "복원", "rename" to "이름 바꿈", "move" to "이동",
        "cleanup" to "군더더기 정리", "tags" to "태그 정리", "external" to "앱 밖 수정")
    LaunchedEffect(Unit) {
        recs = withContext(Dispatchers.IO) { runCatching { History.readJournal(ctx, tree) }.getOrDefault(emptyList()) }
    }
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("전체 변경 이력") }, navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 20.sp) } })
    }) { pad ->
        val list = recs
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (list == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (list.isEmpty()) Text("아직 기록이 없습니다.", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline)
            else LazyColumn(Modifier.fillMaxSize()) {
                items(list.size) { i ->
                    val r = list[i]
                    val target = r.to ?: r.path
                    Column(Modifier.fillMaxWidth().clickable {
                        model.notes.firstOrNull { History.relOf(it) == target }?.let(onOpen)
                    }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(r.path + (if (r.to != null) " → " + r.to else ""), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fmt.format(Date((r.t * 1000).toLong())) + "  ·  " + (names[r.ev] ?: r.ev) + (if (r.d.isNotEmpty()) "  ·  " + r.d else ""),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 중복 노트 찾기: 제목이 같고(끝의 -1, (2) 는 무시) 본문이 완전히 같은 노트끼리 묶어, 최근 것만 남기고 휴지통으로 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(model: NotesModel, onBack: () -> Unit, onOpen: (Note) -> Unit) {
    var groups by remember { mutableStateOf<List<List<Note>>?>(null) }
    var confirm by remember { mutableStateOf<List<Note>?>(null) }   // 휴지통으로 보낼 노트들
    val fmt = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA) }
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { runCatching { model.findDuplicates() }.getOrDefault(emptyList()) } }
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("중복 노트 찾기") },
            navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 20.sp) } },
            actions = {
                val g = groups
                if (!g.isNullOrEmpty()) TextButton(onClick = { confirm = g.flatMap { it.drop(1) } }) { Text("모두 정리") }
            }
        )
    }) { pad ->
        val g = groups
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (g == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (g.isEmpty()) Text("본문까지 똑같은 중복 노트가 없습니다.", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline)
            else LazyColumn(Modifier.fillMaxSize()) {
                item { Text("제목이 같고 본문이 완전히 같은 노트 ${g.size}묶음. ‘정리’는 가장 최근 것만 남기고 나머지를 휴지통으로 보냅니다.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
                items(g.size) { i ->
                    val group = g[i]
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        for ((k, n) in group.withIndex()) Text((if (k == 0) "남김  " else "중복  ") + History.relOf(n) + "  · " + fmt.format(Date(n.date)),
                            maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                            color = if (k == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(n) }.padding(vertical = 3.dp))
                        TextButton(onClick = { confirm = group.drop(1) }, modifier = Modifier.align(Alignment.End)) { Text("정리") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    confirm?.let { list ->
        AlertDialog(
            onDismissRequest = { confirm = null }, title = { Text("${list.size}개를 휴지통으로?") },
            text = { Text("가장 최근 노트만 남깁니다. 컴퓨터의 iamtalkernote 에서 복원할 수 있습니다.") },
            confirmButton = { TextButton(onClick = { confirm = null; model.trashMany(list); onBack() }) { Text("휴지통으로") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } }
        )
    }
}

/** 안 쓰는 미디어 파일 정리: 어떤 노트(휴지통 속 노트 포함)도 가리키지 않는 .resources 파일을 찾아 지운다 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnusedMediaScreen(model: NotesModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<Cleanup.Media>?>(null) }
    var status by remember { mutableStateOf("준비 중…") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun mb(n: Long) = if (n >= 1_048_576) "%.1fMB".format(n / 1_048_576.0) else "%,dKB".format(n / 1024)
    LaunchedEffect(Unit) {
        found = withContext(Dispatchers.IO) { runCatching { model.findUnusedMedia { s -> main.post { status = s } } }.getOrDefault(emptyList()) }
        picked = found.orEmpty().map { it.id }.toSet()
    }
    BackHandler { onBack() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("안 쓰는 미디어") },
            navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 20.sp) } },
            actions = { if (!found.isNullOrEmpty()) TextButton(enabled = picked.isNotEmpty(), onClick = { confirm = true }) { Text("지우기") } }
        )
    }) { pad ->
        val list = found
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (list == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Text(status, Modifier.padding(top = 12.dp), fontSize = 12.sp)
            }
            else if (list.isEmpty()) Text("안 쓰는 미디어 파일이 없습니다.", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline)
            else LazyColumn(Modifier.fillMaxSize()) {
                item { Text("${list.size}개, 합계 ${mb(list.sumOf { it.size })}. 지우면 휴지통 없이 바로 삭제됩니다. 노트의 이전 버전 기록(.history)이 가리키는 그림은 확인하지 않으므로, 오래된 버전으로 되돌릴 때 그림이 빠질 수 있습니다.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
                items(list, key = { it.id }) { m ->
                    Row(Modifier.fillMaxWidth().clickable { picked = if (m.id in picked) picked - m.id else picked + m.id }.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = m.id in picked, onCheckedChange = { picked = if (it) picked + m.id else picked - m.id })
                        Text(m.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        Text(mb(m.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, title = { Text("${picked.size}개를 완전히 지울까요?") },
            text = { Text("휴지통 없이 바로 삭제되어 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val targets = found.orEmpty().filter { it.id in picked }
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { model.deleteMedia(targets) }
                        result = "${n}개를 지웠습니다"
                        found = found.orEmpty().filter { it.id !in picked }
                        picked = emptySet()
                    }
                }) { Text("지우기") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } }
        )
    }
    result?.let { m -> AlertDialog(onDismissRequest = { result = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { result = null }) { Text("닫기") } }) }
}
