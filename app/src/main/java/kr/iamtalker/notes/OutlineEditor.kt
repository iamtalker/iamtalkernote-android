package kr.iamtalker.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 아웃라이너 편집기: 항목마다 입력칸이 있어 눌러서 바로 고친다(컴퓨터 앱 WorkFlowy 식 편집기와 같은 생각).
 *  Enter(키보드 다음) = 새 항목, 점을 누르면 그 항목으로 줌인(편집한 채로), 아래 막대 = 들여쓰기·내어쓰기(하위 함께)·완료·삭제.
 *  글은 그대로 마크다운 목록이라 다른 편집기에서도 열린다. */
@Composable
fun OutlineEditor(text: String, fontSize: Int, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    // 가장 최근 글: 키를 빠르게 치면 한 번 그려지기 전에 입력 신호가 여러 개 올 수 있어, 낡은 줄 목록으로 덮어쓰지 않게 매번 이것을 읽는다
    val latest = remember { arrayOf(text) }
    if (latest[0] != text) latest[0] = text
    val parts = remember(text) { Meta.split(text) }
    val head = text.substring(0, text.length - parts.second.length)
    val lines = remember(text) { parts.second.split("\n") }
    fun cur(): List<String> = Meta.split(latest[0]).second.split("\n")
    fun put(new: List<String>) {
        val t = head + new.joinToString("\n")
        latest[0] = t
        onChange(t)
    }
    /** 편집 계산이 어떤 이유로든 실패해도 앱이 꺼지지 않게 한다(글은 그대로 둔다) */
    fun safe(block: () -> Unit) { runCatching(block).onFailure { android.util.Log.w("iat", "아웃라이너 편집 실패", it) } }

    var focusIdx by remember { mutableIntStateOf(-1) }
    var pending by remember { mutableIntStateOf(-1) }          // 새로 만든 항목에 커서를 옮기라는 요청
    var zoom by remember { mutableIntStateOf(-1) }             // 줌인한 항목의 줄 번호(-1 = 노트 전체)
    if (zoom >= 0 && !OutlineEdit.isItem(lines, zoom)) zoom = -1
    BackHandler(enabled = zoom >= 0) { zoom = OutlineZoom.crumbs(lines, zoom).lastOrNull() ?: -1 }   // 뒤로가기는 한 단계 위로

    val color = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    val zoomDepth = if (zoom >= 0) OutlineEdit.parse(lines[zoom])?.depth ?: 0 else -1
    val shown: List<Int> = if (zoom >= 0) OutlineZoom.range(lines, zoom).toList() else lines.indices.toList()

    Column(modifier) {
        if (zoom >= 0) {
            // 경로: 노트 › 조상 › …
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("노트"), color = accent, fontSize = 14.sp, modifier = Modifier.clickable { zoom = -1 }.padding(end = 2.dp))
                for (a in OutlineZoom.crumbs(lines, zoom)) {
                    Text("  ›  ", color = muted, fontSize = 14.sp)
                    val t = OutlineEdit.parse(lines[a])?.text.orEmpty()
                    Text(t.take(24) + (if (t.length > 24) "…" else ""), color = accent, fontSize = 14.sp, maxLines = 1, modifier = Modifier.clickable { zoom = a })
                }
            }
            val zt = OutlineEdit.parse(lines[zoom])?.text.orEmpty()
            val zreq = remember(zoom) { FocusRequester() }
            BasicTextField(
                value = zt, onValueChange = { v -> safe { put(OutlineEdit.setText(cur(), zoom, v)) } }, singleLine = true,
                textStyle = TextStyle(fontSize = fontSize.sp, color = color), cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { safe { val (n, at) = OutlineEdit.enter(cur(), zoom); put(n); pending = at } }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(zreq).onFocusChanged { if (it.isFocused) focusIdx = zoom }
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            itemsIndexed(shown) { _, i ->
                val line = lines.getOrNull(i) ?: return@itemsIndexed
                val p = OutlineEdit.parse(line)
                if (p == null) {
                    if (line.isNotBlank()) Text(line, fontSize = fontSize.sp, color = muted, modifier = Modifier.padding(vertical = 4.dp))
                    else Spacer(Modifier.height(4.dp))
                } else {
                    val req = remember(i) { FocusRequester() }
                    LaunchedEffect(pending, i) { if (pending == i) { runCatching { req.requestFocus() }; pending = -1 } }
                    val kids = OutlineEdit.hasChildren(lines, i)
                    val depth = maxOf(0, p.depth - (zoomDepth + 1))
                    Row(Modifier.padding(start = (minOf(depth, 8) * 14).dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        // 점을 누르면 그 항목으로 줌인(편집한 채로)
                        Box(Modifier.size(32.dp).clickable { zoom = i; focusIdx = -1 }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(if (kids) 9.dp else 7.dp).clip(CircleShape).background(if (kids) accent else muted))
                        }
                        BasicTextField(
                            value = p.text, onValueChange = { v -> safe { put(OutlineEdit.setText(cur(), i, v)) } }, singleLine = true,
                            textStyle = TextStyle(fontSize = fontSize.sp, color = color),
                            cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { safe { val (n, at) = OutlineEdit.enter(cur(), i); put(n); pending = at } }),
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp).focusRequester(req).onFocusChanged { if (it.isFocused) focusIdx = i }
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clickable {
                    safe {
                        val l = cur()
                        if (zoom >= 0) {                                              // 줌인한 항목의 마지막 하위 뒤에 새 항목(하위로)
                            val end = OutlineEdit.subtreeEnd(l, zoom)
                            val zl = OutlineEdit.parse(l[zoom])!!
                            val childLead = if (end > zoom + 1) OutlineEdit.parse(l[zoom + 1])!!.lead else zl.lead + OutlineEdit.UNIT
                            val n = l.toMutableList().also { it.add(end, childLead + "- ") }
                            put(n); pending = end
                        } else {
                            val items = l.indices.filter { OutlineEdit.isItem(l, it) }
                            if (items.isEmpty()) { val keep = l.filter { it.isNotBlank() }; put(keep + "- "); pending = keep.size }
                            else {
                                val top = items.last { OutlineEdit.parse(l[it])!!.depth == 0 }.let { it }
                                val (n, idx) = OutlineEdit.enter(l, top); put(n); pending = idx
                            }
                        }
                    }
                }.padding(vertical = 14.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlusIcon(accent); Text(tr("  항목 추가"), fontSize = 14.sp, color = accent)
                }
                Spacer(Modifier.height(60.dp))
            }
        }
        if (focusIdx in lines.indices && OutlineEdit.isItem(lines, focusIdx)) {
            val i = focusIdx
            val canOut = zoom < 0 || (OutlineEdit.parse(lines[i])?.depth ?: 0) > zoomDepth + 1       // 줌인한 항목의 바로 아래 항목은 더 못 내어쓴다(줌 밖으로 나가므로)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { safe { if (canOut) OutlineEdit.outdent(cur(), i)?.let { put(it) } } }) { Text(tr("내어쓰기")) }
                    TextButton(onClick = { safe { OutlineEdit.indent(cur(), i)?.let { put(it) } } }) { Text(tr("들여쓰기")) }
                    if (i != zoom) TextButton(onClick = { safe { val (n, f) = OutlineEdit.delete(cur(), i); put(n); focusIdx = -1; if (f >= 0) pending = f } }) { Text(tr("삭제")) }
                }
            }
        }
    }
}
