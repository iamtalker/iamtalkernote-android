package kr.iamtalker.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

private val Red = Color(0xFFD64545)
private val Blue = Color(0xFF3B7DD8)

/** 달력·할일·공수 화면(PC 앱 planner/view.py 와 같은 구성): 위에 6주 달력(공휴일·그 날 할일 2개·공수), 아래에 할일 목록
 *  [추가한 날 | 완료한 날 | 할일], 공수달력을 켜면 단가·일비 칸과 월 요약. 날 길게 누르기 = PC 의 오른쪽 클릭 메뉴.
 *  파일은 PC 와 같은 `달력/` 마크다운. */
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
    var sel by remember { mutableStateOf(LocalDate.now().toString()) }
    var showGongsu by remember { mutableStateOf(prefs.getBoolean("gongsu", false)) }
    var wage by remember { mutableStateOf(prefs.getLong("wage", 0L)) }
    var meal by remember { mutableStateOf(prefs.getLong("meal", 0L)) }
    var hideDone by remember { mutableStateOf(prefs.getBoolean("hideDone", false)) }
    var dayMenu by remember { mutableStateOf<String?>(null) }           // 길게 누른 날(메뉴)
    var addDialog by remember { mutableStateOf<String?>(null) }         // 할일 만들기(날짜 "" = 날짜 없이)
    var textDialog by remember { mutableStateOf<Todo?>(null) }          // 할일 변경
    var dateDialog by remember { mutableStateOf<Pair<Todo, String>?>(null) }   // (할일, "date" | "done")
    var gongsuDialog by remember { mutableStateOf<String?>(null) }
    var priceDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    fun change(f: () -> Unit) { f(); rev++ }
    fun toast(s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show()

    val today = LocalDate.now()
    val hol = remember(month.year) { Holidays.of(month.year - 1) + Holidays.of(month.year) + Holidays.of(month.year + 1) }
    val todos = remember(rev) { PlannerCalc.ordered(store.todos) }
    val shown = remember(rev, hideDone) { if (hideDone) todos.filter { !it.done } else todos }
    val sum = remember(rev, month, wage, meal) { PlannerCalc.summary(store.gongsu, month.year, month.monthValue, wage, meal) }
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val soft = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("달력·할일") },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ 뒤로") } },
            actions = {
                TextButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 20.sp) }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(if (showGongsu) "✓ 공수달력" else "공수달력") }, onClick = { showGongsu = !showGongsu; prefs.edit().putBoolean("gongsu", showGongsu).apply(); menuOpen = false })
                    if (showGongsu) DropdownMenuItem(text = { Text("단가·일비 설정") }, onClick = { priceDialog = true; menuOpen = false })
                    DropdownMenuItem(text = { Text("파일 다시 읽기") }, onClick = { store.load(); rev++; menuOpen = false })
                }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 머리: ◀ 2026년 11월 ▶  오늘
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("◀", fontSize = 16.sp, color = outline, modifier = Modifier.clickable { month = month.minusMonths(1) }.padding(horizontal = 10.dp, vertical = 8.dp))
                Text("${month.year}년 ${month.monthValue}월", fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("▶", fontSize = 16.sp, color = outline, modifier = Modifier.clickable { month = month.plusMonths(1) }.padding(horizontal = 10.dp, vertical = 8.dp))
                Text("오늘", fontSize = 14.sp, color = accent, modifier = Modifier.clickable { month = YearMonth.now(); sel = today.toString() }.padding(horizontal = 10.dp, vertical = 8.dp))
            }
            if (showGongsu) {
                Text("공수 합계 ${PlannerMd.fmt(sum.gongsu)}  ·  출근 ${sum.days}일  ·  일당 ${"%,d".format(sum.pay)}원 + 일비 ${"%,d".format(sum.meal)}원 = 총 ${"%,d".format(sum.sum)}원",
                    fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
                Text("단가 ${"%,d".format(wage)}원 · 일비 ${"%,d".format(meal)}원 (⋮ → 단가·일비 설정)", fontSize = 11.sp, color = outline, modifier = Modifier.padding(horizontal = 14.dp))
            }
            // 요일 줄
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                "일월화수목금토".forEachIndexed { i, d ->
                    Text(d.toString(), fontSize = 12.sp, textAlign = TextAlign.Center, color = if (i == 0) Red else if (i == 6) Blue else outline, modifier = Modifier.weight(1f))
                }
            }
            // 6주 × 7일(앞뒤 달 날짜는 흐리게)
            val first = month.atDay(1)
            val start = first.minusDays((first.dayOfWeek.value % 7).toLong())
            Column(Modifier.padding(horizontal = 8.dp).border(BorderStroke(0.5.dp, line))) {
                for (r in 0 until 6) Row(Modifier.fillMaxWidth().height(66.dp)) {
                    for (c in 0 until 7) {
                        val d = start.plusDays((r * 7 + c).toLong()); val ds = d.toString()
                        val h = hol[ds]
                        val red = d.dayOfWeek.value == 7 || h != null
                        var fg = if (red) Red else if (d.dayOfWeek.value == 6) Blue else MaterialTheme.colorScheme.onSurface
                        if (d.monthValue != month.monthValue) fg = outline
                        val here = store.todos.filter { it.date == ds }
                        val g = store.gongsu[ds]
                        Box(Modifier.weight(1f).fillMaxSize().border(BorderStroke(if (d == today) 1.5.dp else 0.5.dp, if (d == today) accent else line))
                            .background(if (ds == sel) soft else Color.Transparent)
                            .combinedClickable(onClick = { sel = ds }, onLongClick = { sel = ds; dayMenu = ds }).padding(2.dp)) {
                            Column {
                                Text(d.dayOfMonth.toString() + (if (h != null) " " + h else ""), fontSize = 10.sp, color = fg, maxLines = 1, overflow = TextOverflow.Clip)
                                for (t in here.take(2)) Text((if (t.done) "✓" else "●") + t.text.take(5) + (if (t.text.length > 5) "…" else ""), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Clip,
                                    color = if (t.done) outline else MaterialTheme.colorScheme.onSurface)
                                if (here.size > 2) Text("+${here.size - 2}", fontSize = 9.sp, color = outline)
                                if (showGongsu && g != null) Text("공수 " + PlannerMd.fmt(g), fontSize = 9.sp, color = accent)
                            }
                            DropdownMenu(dayMenu == ds, { dayMenu = null }) {
                                if (showGongsu) {
                                    for (v in listOf(0.5, 1.0, 1.5, 2.0)) DropdownMenuItem(text = { Text("공수 " + PlannerMd.fmt(v)) }, onClick = { change { store.setGongsu(ds, v) }; dayMenu = null })
                                    DropdownMenuItem(text = { Text("공수 직접 입력…") }, onClick = { gongsuDialog = ds; dayMenu = null })
                                    DropdownMenuItem(text = { Text("공수 지우기") }, onClick = { change { store.setGongsu(ds, 0.0) }; dayMenu = null })
                                    HorizontalDivider()
                                }
                                DropdownMenuItem(text = { Text("이 날 할일 추가…") }, onClick = { addDialog = ds; dayMenu = null })
                                for (t in here) DropdownMenuItem(text = { Text("삭제: " + t.text.take(24)) }, onClick = { change { store.remove(t.id) }; dayMenu = null })
                            }
                        }
                    }
                }
            }
            // 할일 목록
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("할일", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { addDialog = "" }) { Text("＋", fontSize = 16.sp) }
                Box(Modifier.weight(1f))
                Checkbox(hideDone, { hideDone = it; prefs.edit().putBoolean("hideDone", it).apply() })
                Text("완료 숨기기", fontSize = 12.sp, color = outline)
            }
            Row(Modifier.fillMaxWidth().background(soft).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("추가한 날", fontSize = 11.sp, color = outline, modifier = Modifier.width(84.dp))
                Text("완료한 날", fontSize = 11.sp, color = outline, modifier = Modifier.width(76.dp))
                Text("할일  (☐ 를 누르면 완료 · 길게: 변경·삭제)", fontSize = 11.sp, color = outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (shown.isEmpty()) Text("할일이 없습니다.", fontSize = 14.sp, color = outline, modifier = Modifier.padding(16.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { t ->
                    var m by remember { mutableStateOf("") }   // text | date | done
                    val dl = PlannerCalc.dLabel(t, today)
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(84.dp).combinedClickable(onClick = {}, onLongClick = { m = "date" }).padding(vertical = 6.dp)) {
                            Column {
                                Text(t.date ?: "", fontSize = 12.sp, color = if (t.done) outline else MaterialTheme.colorScheme.onSurface)
                                if (dl.isNotEmpty()) Text(dl, fontSize = 10.sp, color = if (dl == "D-day") Red else outline)
                            }
                            DropdownMenu(m == "date", { m = "" }) {
                                DropdownMenuItem(text = { Text(if (t.date != null) "날짜 변경" else "날짜 만들기") }, onClick = { dateDialog = t to "date"; m = "" })
                                if (t.date != null) DropdownMenuItem(text = { Text("날짜 삭제") }, onClick = { change { store.setDate(t.id, null) }; m = "" })
                            }
                        }
                        Box(Modifier.width(76.dp).combinedClickable(onClick = {}, onLongClick = { m = "done" }).padding(vertical = 6.dp)) {
                            Text(t.doneOn ?: "", fontSize = 12.sp, color = outline)
                            DropdownMenu(m == "done", { m = "" }) {
                                DropdownMenuItem(text = { Text(if (t.done) "날짜 변경" else "날짜 만들기") }, onClick = { dateDialog = t to "done"; m = "" })
                                if (t.done) DropdownMenuItem(text = { Text("날짜 삭제") }, onClick = { change { store.setDone(t.id, false) }; m = "" })
                            }
                        }
                        Box(Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = { m = "text" })) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(if (t.done) "☑ " else "☐ ", fontSize = 18.sp, modifier = Modifier.clickable { change { store.setDone(t.id, !t.done) } }.padding(vertical = 4.dp))
                                Text(t.text, fontSize = 14.sp, color = if (t.done) outline else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 8.dp))
                            }
                            DropdownMenu(m == "text", { m = "" }) {
                                DropdownMenuItem(text = { Text("할일 생성") }, onClick = { addDialog = ""; m = "" })
                                DropdownMenuItem(text = { Text("할일 변경") }, onClick = { textDialog = t; m = "" })
                                DropdownMenuItem(text = { Text("할일 삭제") }, onClick = { change { store.remove(t.id) }; m = "" })
                            }
                        }
                    }
                    HorizontalDivider(color = line)
                }
            }
        }
    }

    addDialog?.let { ds ->
        var text by remember(ds) { mutableStateOf("") }
        var date by remember(ds) { mutableStateOf(ds) }
        AlertDialog(onDismissRequest = { addDialog = null }, title = { Text("할일 만들기") },
            text = { Column {
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("할일 내용") })
                OutlinedTextField(date, { date = it }, singleLine = true, placeholder = { Text("날짜(비우면 날짜 없이) 예: 20261105") }, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton(onClick = {
                val d = if (date.isBlank()) null else PlannerCalc.parseLoose(date)
                if (date.isNotBlank() && d == null) toast("날짜를 읽을 수 없습니다. 예: 2026-09-14 또는 20260914")
                else { change { store.add(text, d) }; addDialog = null }
            }) { Text("만들기") } },
            dismissButton = { TextButton(onClick = { addDialog = null }) { Text("취소") } })
    }
    textDialog?.let { t ->
        var text by remember(t.id) { mutableStateOf(t.text) }
        AlertDialog(onDismissRequest = { textDialog = null }, title = { Text("할일 바꾸기") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) change { store.rename(t.id, text.trim().split(Regex("\\s+")).joinToString(" ")) }; textDialog = null }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { textDialog = null }) { Text("취소") } })
    }
    dateDialog?.let { (t, field) ->
        var s by remember(t.id, field) { mutableStateOf((if (field == "date") t.date else t.doneOn) ?: today.toString()) }
        AlertDialog(onDismissRequest = { dateDialog = null }, title = { Text(if (field == "date") "추가한 날" else "완료한 날") },
            text = { OutlinedTextField(s, { s = it }, singleLine = true, placeholder = { Text("예: 2026-09-14, 20260914") }) },
            confirmButton = { TextButton(onClick = {
                val d = PlannerCalc.parseLoose(s)
                when {
                    d == null -> toast("날짜를 읽을 수 없습니다. 예: 2026-09-14 또는 20260914")
                    field == "date" && t.done && t.doneOn != null && d > t.doneOn -> toast("추가한 날이 완료한 날보다 늦을 수 없습니다.")
                    field == "done" && t.date != null && d < t.date -> toast("완료한 날이 추가한 날보다 빠를 수 없습니다.")
                    else -> { change { if (field == "date") store.setDate(t.id, d) else store.setDone(t.id, true, d) }; dateDialog = null }
                }
            }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { dateDialog = null }) { Text("취소") } })
    }
    gongsuDialog?.let { ds ->
        var s by remember(ds) { mutableStateOf(store.gongsu[ds]?.let { PlannerMd.fmt(it) } ?: "") }
        AlertDialog(onDismissRequest = { gongsuDialog = null }, title = { Text("공수") },
            text = { OutlinedTextField(s, { s = it }, singleLine = true, label = { Text("$ds 공수(예: 1, 1.5, 2)") }) },
            confirmButton = { TextButton(onClick = { s.toDoubleOrNull()?.let { v -> change { store.setGongsu(ds, v) } }; gongsuDialog = null }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { gongsuDialog = null }) { Text("취소") } })
    }
    if (priceDialog) {
        var w by remember { mutableStateOf(wage.toString()) }
        var ml by remember { mutableStateOf(meal.toString()) }
        AlertDialog(onDismissRequest = { priceDialog = false }, title = { Text("단가·일비") },
            text = { Column {
                OutlinedTextField(w, { w = it.filter(Char::isDigit) }, singleLine = true, label = { Text("단가(1공수)") })
                OutlinedTextField(ml, { ml = it.filter(Char::isDigit) }, singleLine = true, label = { Text("일비(하루)") }, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton(onClick = {
                wage = w.toLongOrNull() ?: 0L; meal = ml.toLongOrNull() ?: 0L
                prefs.edit().putLong("wage", wage).putLong("meal", meal).apply(); priceDialog = false
            }) { Text("저장") } },
            dismissButton = { TextButton(onClick = { priceDialog = false }) { Text("취소") } })
    }
}
