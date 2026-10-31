package kr.iamtalker.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

private val Red = Color(0xFFD64545)
private val Blue = Color(0xFF3B7DD8)

/** 달력(위, 일요일 시작·공휴일) + 할일 목록(아래, 맨 위가 가장 과거). 파일은 PC 와 같은 `달력/` 마크다운 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlannerScreen(model: NotesModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val vault = remember(model.tree) { model.tree?.let { ForkSync.treePath(it) } }
    if (vault == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("달력·할일") }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ 뒤로") } }) }) { Box(Modifier.padding(it).padding(24.dp)) { Text("노트 폴더를 먼저 고르세요.") } }
        return
    }
    val store = remember(vault) { PlannerStore(vault) }
    val prefs = remember { ctx.getSharedPreferences("planner", 0) }
    var rev by remember { mutableStateOf(0) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var showGongsu by remember { mutableStateOf(prefs.getBoolean("gongsu", false)) }
    var wage by remember { mutableStateOf(prefs.getLong("wage", 0L)) }
    var meal by remember { mutableStateOf(prefs.getLong("meal", 0L)) }
    var hideDone by remember { mutableStateOf(prefs.getBoolean("hideDone", false)) }
    var dayDialog by remember { mutableStateOf<String?>(null) }
    var addDialog by remember { mutableStateOf<String?>(null) }    // 날짜("" = 날짜 없이), null = 닫힘
    var editTodo by remember { mutableStateOf<Todo?>(null) }
    var priceDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    fun change(f: () -> Unit) { f(); rev++ }

    val today = LocalDate.now()
    val holidays = remember(month.year) { Holidays.of(month.year) }
    val todos = remember(rev) { PlannerCalc.ordered(store.todos) }
    val shown = remember(rev, hideDone) { if (hideDone) todos.filter { !it.done } else todos }
    val sum = remember(rev, month, wage, meal) { PlannerCalc.summary(store.gongsu, month.year, month.monthValue, wage, meal) }
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("달력·할일") },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ 뒤로") } },
            actions = {
                TextButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 20.sp) }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(if (hideDone) "✓ 완료 항목 숨김" else "완료 항목 숨기기") }, onClick = { hideDone = !hideDone; prefs.edit().putBoolean("hideDone", hideDone).apply(); menuOpen = false })
                    DropdownMenuItem(text = { Text(if (showGongsu) "✓ 공수달력" else "공수달력") }, onClick = { showGongsu = !showGongsu; prefs.edit().putBoolean("gongsu", showGongsu).apply(); menuOpen = false })
                    if (showGongsu) DropdownMenuItem(text = { Text("단가·일비 설정") }, onClick = { priceDialog = true; menuOpen = false })
                    DropdownMenuItem(text = { Text("파일 다시 읽기") }, onClick = { store.load(); rev++; menuOpen = false })
                }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹", fontSize = 22.sp) }
                Text("${month.year}년 ${month.monthValue}월", fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).clickable { month = YearMonth.now() })
                TextButton(onClick = { month = month.plusMonths(1) }) { Text("›", fontSize = 22.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
                    Text(d, fontSize = 12.sp, textAlign = TextAlign.Center, color = if (i == 0) Red else if (i == 6) Blue else outline, modifier = Modifier.weight(1f))
                }
            }
            val lead = month.atDay(1).dayOfWeek.value % 7           // 일요일=0
            val rows = (lead + month.lengthOfMonth() + 6) / 7
            val byDate = todos.filter { it.date != null && !it.done }.groupingBy { it.date!! }.eachCount()
            Column(Modifier.padding(horizontal = 4.dp)) {
                for (r in 0 until rows) Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val dayNo = r * 7 + c - lead + 1
                        if (dayNo < 1 || dayNo > month.lengthOfMonth()) { Box(Modifier.weight(1f).aspectRatio(1.15f)); continue }
                        val d = month.atDay(dayNo); val ds = d.toString()
                        val hol = holidays[ds]
                        val g = store.gongsu[ds]
                        Column(Modifier.weight(1f).aspectRatio(1.15f).padding(1.dp)
                            .background(if (d == today) accent.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { dayDialog = ds }.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$dayNo", fontSize = 14.sp, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                color = if (c == 0 || hol != null) Red else if (c == 6) Blue else MaterialTheme.colorScheme.onSurface)
                            if (hol != null) Text(hol, fontSize = 8.sp, color = Red, maxLines = 1, overflow = TextOverflow.Clip)
                            if (showGongsu && g != null) Text(PlannerMd.fmt(g), fontSize = 11.sp, color = accent, fontWeight = FontWeight.Medium)
                            val n = byDate[ds] ?: 0
                            if (n > 0) Text("●".repeat(minOf(n, 3)), fontSize = 7.sp, color = accent)
                        }
                    }
                }
            }
            if (showGongsu) Text("공수 ${PlannerMd.fmt(sum.gongsu)} · 출근 ${sum.days}일 · 일당 ${"%,d".format(sum.pay)} · 일비 ${"%,d".format(sum.meal)} · 합계 ${"%,d".format(sum.sum)}",
                fontSize = 12.sp, color = outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("할일", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = accent, modifier = Modifier.weight(1f))
                TextButton(onClick = { addDialog = "" }) { Text("＋ 할일", fontSize = 14.sp) }
            }
            if (shown.isEmpty()) Text("할일이 없습니다.", fontSize = 14.sp, color = outline, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { t ->
                    var m by remember { mutableStateOf(false) }
                    Box {
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { change { store.setDone(t.id, !t.done) } }, onLongClick = { m = true })
                            .padding(start = 8.dp, end = 16.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(t.done, { v -> change { store.setDone(t.id, v) } })
                            Text(t.date?.substring(5) ?: "—", fontSize = 12.sp, color = outline, modifier = Modifier.padding(end = 8.dp))
                            Text(t.text, fontSize = 15.sp, color = if (t.done) outline else MaterialTheme.colorScheme.onSurface,
                                textDecoration = if (t.done) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
                            val dl = PlannerCalc.dLabel(t, today)
                            if (dl.isNotEmpty()) Text(dl, fontSize = 12.sp, color = if (dl == "D-day") Red else outline)
                            val on = t.doneOn
                            if (t.done && on != null && on.length >= 10) Text(on.substring(5), fontSize = 12.sp, color = outline)
                        }
                        DropdownMenu(m, { m = false }) {
                            DropdownMenuItem(text = { Text("내용·날짜 바꾸기") }, onClick = { editTodo = t; m = false })
                            if (t.date != null) DropdownMenuItem(text = { Text("날짜 없애기") }, onClick = { change { store.setDate(t.id, null) }; m = false })
                            DropdownMenuItem(text = { Text("삭제") }, onClick = { change { store.remove(t.id) }; m = false })
                        }
                    }
                }
            }
        }
    }

    dayDialog?.let { ds ->
        val here = todos.filter { it.date == ds }
        AlertDialog(onDismissRequest = { dayDialog = null },
            title = { Text(ds.replace('-', '.') + (holidays[ds]?.let { " · $it" } ?: "")) },
            text = {
                Column {
                    if (here.isEmpty()) Text("이 날의 할일이 없습니다.", fontSize = 14.sp, color = outline)
                    here.forEach { Text((if (it.done) "☑ " else "☐ ") + it.text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                    if (showGongsu) {
                        Text("공수", fontSize = 13.sp, color = outline, modifier = Modifier.padding(top = 12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(0.5, 1.0, 1.5, 2.0).forEach { v ->
                                TextButton(onClick = { change { store.setGongsu(ds, if (store.gongsu[ds] == v) 0.0 else v) }; dayDialog = null }) {
                                    Text(PlannerMd.fmt(v), fontWeight = if (store.gongsu[ds] == v) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addDialog = ds; dayDialog = null }) { Text("＋ 할일") } },
            dismissButton = { TextButton(onClick = { dayDialog = null }) { Text("닫기") } })
    }
    addDialog?.let { ds ->
        var text by remember(ds) { mutableStateOf("") }
        var date by remember(ds) { mutableStateOf(ds) }
        AlertDialog(onDismissRequest = { addDialog = null }, title = { Text("할일 만들기") },
            text = { Column {
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("내용") })
                OutlinedTextField(date, { date = it }, singleLine = true, placeholder = { Text("날짜(비우면 날짜 없이) 예: 20261105") }, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton(onClick = {
                val d = if (date.isBlank()) null else PlannerCalc.parseLoose(date)
                if (date.isNotBlank() && d == null) android.widget.Toast.makeText(ctx, "날짜를 읽을 수 없습니다", android.widget.Toast.LENGTH_SHORT).show()
                else { change { store.add(text, d) }; addDialog = null }
            }) { Text("만들기") } },
            dismissButton = { TextButton(onClick = { addDialog = null }) { Text("취소") } })
    }
    editTodo?.let { t ->
        var text by remember(t.id) { mutableStateOf(t.text) }
        var date by remember(t.id) { mutableStateOf(t.date ?: "") }
        AlertDialog(onDismissRequest = { editTodo = null }, title = { Text("바꾸기") },
            text = { Column {
                OutlinedTextField(text, { text = it }, singleLine = true)
                OutlinedTextField(date, { date = it }, singleLine = true, placeholder = { Text("날짜(비우면 없음)") }, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton(onClick = {
                val d = if (date.isBlank()) null else PlannerCalc.parseLoose(date)
                if (date.isNotBlank() && d == null) android.widget.Toast.makeText(ctx, "날짜를 읽을 수 없습니다", android.widget.Toast.LENGTH_SHORT).show()
                else { change { if (text.isNotBlank()) store.update(t.id) { it.copy(text = text.trim(), date = d) } }; editTodo = null }
            }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { editTodo = null }) { Text("취소") } })
    }
    if (priceDialog) {
        var w by remember { mutableStateOf(wage.toString()) }
        var ml by remember { mutableStateOf(meal.toString()) }
        AlertDialog(onDismissRequest = { priceDialog = false }, title = { Text("단가·일비") },
            text = { Column {
                OutlinedTextField(w, { w = it.filter(Char::isDigit) }, singleLine = true, label = { Text("공수 1당 단가") })
                OutlinedTextField(ml, { ml = it.filter(Char::isDigit) }, singleLine = true, label = { Text("출근일당 일비") }, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton(onClick = {
                wage = w.toLongOrNull() ?: 0L; meal = ml.toLongOrNull() ?: 0L
                prefs.edit().putLong("wage", wage).putLong("meal", meal).apply(); priceDialog = false
            }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { priceDialog = false }) { Text("취소") } })
    }
}
